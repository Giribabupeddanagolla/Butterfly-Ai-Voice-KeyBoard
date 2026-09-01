import os
import uuid
import shutil
import logging
from typing import Optional
from pathlib import Path

from fastapi import FastAPI, File, UploadFile, Form, HTTPException, BackgroundTasks, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from config import config
from memory import memory_manager
from ai_agent import ai_agent
from speech_to_text import stt_service, detect_language_from_text, translate_text
from text_to_speech import tts_service
from services.openai_service import openai_service
from services.whisper_service import whisper_service
from services.transcription_service import transcription_service

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("api")

# Startup Validation
if config.OPENAI_API_KEY and config.OPENAI_API_KEY.startswith("sk-"):
    logger.info("OpenAI API configuration loaded successfully.")
else:
    logger.warning("WARNING: OPENAI_API_KEY is not configured.")

app = FastAPI(
    title="Multilingual Voice AI Agent API",
    description="Backend API for Multilingual Voice & Text AI Assistant",
    version="1.0.0"
)

# CORS configuration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount audio output directory statically
app.mount("/audio/output", StaticFiles(directory=config.AUDIO_OUTPUT_DIR), name="audio_output")

# Pydantic Schemas
class ChatRequest(BaseModel):
    session_id: Optional[str] = None
    message: str

class TranslateRequest(BaseModel):
    session_id: Optional[str] = None
    text: str
    source_language: Optional[str] = "auto"
    translation_language: Optional[str] = "en"
    target_language: Optional[str] = None
    text_language: Optional[str] = None

class TTSRequest(BaseModel):
    text: str
    language: Optional[str] = "en"

class APIKeyRequest(BaseModel):
    api_key: str

class RecordRequest(BaseModel):
    text: str
    source_language: Optional[str] = "auto"
    target_language: Optional[str] = "en"
    translated_text: Optional[str] = None

# Endpoints

@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "Butterfly AI",
        "openai_configured": openai_service.is_configured()
    }

@app.get("/api/openai/status")
def openai_status():
    if openai_service.is_configured():
        return {
            "success": True,
            "configured": True,
            "message": "OpenAI API is configured"
        }
    else:
        return {
            "success": False,
            "configured": False,
            "message": "OpenAI API key is not configured"
        }

@app.post("/api/settings/key")
def update_api_key(request: APIKeyRequest):
    new_key = request.api_key.strip()
    if not new_key:
        raise HTTPException(status_code=400, detail="API key cannot be empty")
    
    # 1. Update config & services in memory
    config.OPENAI_API_KEY = new_key
    openai_service.api_key = new_key
    openai_service.client = None
    
    tts_service.api_key = new_key
    tts_service.client = None
    tts_service.openai_tts_failed = False
    
    stt_service.api_key = new_key
    stt_service.client = None
    stt_service.openai_stt_failed = False

    ai_agent.api_key = new_key
    ai_agent.client = None

    # 2. Persist key to backend/.env
    env_path = Path(__file__).resolve().parent / ".env"
    try:
        if env_path.exists():
            content = env_path.read_text(encoding="utf-8")
            if "OPENAI_API_KEY=" in content:
                lines = content.splitlines()
                new_lines = [f"OPENAI_API_KEY={new_key}" if l.startswith("OPENAI_API_KEY=") else l for l in lines]
                env_path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
            else:
                env_path.write_text(content.strip() + f"\nOPENAI_API_KEY={new_key}\n", encoding="utf-8")
        else:
            env_path.write_text(f"OPENAI_API_KEY={new_key}\n", encoding="utf-8")
    except Exception as e:
        logger.warning(f"Could not persist API key to .env: {e}")

    return {"success": True, "message": "OpenAI API Key updated successfully"}

@app.post("/api/text-translate")
def text_translate_endpoint(request: TranslateRequest):
    session_id = request.session_id or f"session_{uuid.uuid4().hex[:8]}"
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    tgt_lang = request.target_language or request.translation_language or "en"
    result = ai_agent.process_translation(
        session_id=session_id,
        user_message=request.text,
        source_language=request.source_language or "auto",
        translation_language=tgt_lang,
        text_language=request.text_language or tgt_lang,
        input_type="text"
    )
    return result

@app.get("/api/health")
def api_health():
    return {
        "status": "ok",
        "openai_configured": openai_service.is_configured()
    }

@app.post("/api/voice/translate")
@app.post("/api/translate")
def translate_endpoint(request: TranslateRequest):
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    
    src_lang = request.source_language or "auto"
    tgt_lang = request.target_language or request.translation_language or request.text_language or "en"
    
    if openai_service.is_configured():
        trans_res = openai_service.translate_text(
            text=request.text,
            source_language=src_lang,
            target_language=tgt_lang
        )
        if trans_res.get("success") and trans_res.get("translated_text"):
            return {
                "success": True,
                "translation": trans_res["translated_text"],
                "translated_text": trans_res["translated_text"],
                "original_text": request.text,
                "source_language": src_lang,
                "target_language": tgt_lang
            }
            
    translated = translate_text(
        text=request.text,
        target_language=tgt_lang,
        source_language=src_lang
    )
    
    return {
        "success": True,
        "translation": translated,
        "translated_text": translated,
        "original_text": request.text,
        "source_language": src_lang,
        "target_language": tgt_lang
    }

@app.post("/api/transcribe")
@app.post("/api/voice/transcribe")
@app.post("/api/voice-translate")
@app.post("/speech-to-text")
async def transcribe_endpoint(
    audio: Optional[UploadFile] = File(None),
    fallback_text: Optional[str] = Form(None),
    session_id: Optional[str] = Form(None),
    source_language: Optional[str] = Form("auto"),
    translation_language: Optional[str] = Form("en"),
    target_language: Optional[str] = Form(None),
    text_language: Optional[str] = Form(None),
    language: Optional[str] = Form(None)
):
    """
    OpenAI Whisper API Speech-to-Text Endpoint.
    Transcribes audio using Whisper API preserving spoken language.
    Does NOT generate an AI answer, response, or chatbot completion.
    """
    session_id = session_id or f"session_{uuid.uuid4().hex[:8]}"
    spoken_text = ""
    req_lang = language or source_language or "auto"
    detected_lang = req_lang
    clean_fallback = fallback_text.strip() if (fallback_text and fallback_text.strip()) else ""
    
    if audio:
        logger.info(f"Received audio file upload: {audio.filename}, content_type={audio.content_type}")
        temp_filename = f"whisper_{uuid.uuid4().hex[:10]}_{audio.filename or 'recording.webm'}"
        temp_path = os.path.join(config.AUDIO_INPUT_DIR, temp_filename)
        try:
            with open(temp_path, "wb") as buffer:
                shutil.copyfileobj(audio.file, buffer)
            
            file_size = os.path.getsize(temp_path)
            logger.info(f"Audio file saved to {temp_path} ({file_size} bytes)")
            
            if file_size > 0:
                res = whisper_service.transcribe_audio(
                    audio_file_path=temp_path,
                    language=req_lang,
                    fallback_text=clean_fallback
                )
                if res.get("success") and res.get("text"):
                    spoken_text = res["text"]
                    detected_lang = res.get("language", detected_lang)
                elif clean_fallback:
                    spoken_text = clean_fallback
            else:
                logger.warning("Uploaded audio file is empty (0 bytes)")
        except Exception as e:
            logger.error(f"Whisper transcription endpoint error: {e}")
            if clean_fallback:
                spoken_text = clean_fallback
        finally:
            if os.path.exists(temp_path):
                try: os.remove(temp_path)
                except Exception: pass

    if not spoken_text and clean_fallback:
        spoken_text = clean_fallback

    if not spoken_text:
        logger.warning("No speech transcribed from audio upload")
        return JSONResponse(
            status_code=200,
            content={
                "success": False,
                "error": "No speech detected. Please speak again.",
                "text": "",
                "transcription": "",
                "language": "en"
            }
        )
        
    if detected_lang == "auto":
        detected_lang = detect_language_from_text(spoken_text)

    # Save to session memory without LLM response generation
    memory_manager.save_message(
        session_id=session_id,
        role="user",
        content=spoken_text,
        language=detected_lang,
        original_text=spoken_text,
        source_language=detected_lang,
        translation_language=translation_language or "en",
        text_language=detected_lang,
        translated_text=spoken_text,
        input_type="voice"
    )

    logger.info(f"Transcription result: '{spoken_text}' (language: {detected_lang})")
    return {
        "success": True,
        "text": spoken_text,
        "transcription": spoken_text,
        "original_text": spoken_text,
        "language": detected_lang
    }

@app.post("/text-to-speech")
def text_to_speech_endpoint(request: TTSRequest):
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    result = tts_service.generate_speech(text=request.text, language=request.language or "en")
    if not result.get("success"):
        raise HTTPException(status_code=500, detail=result.get("error", "TTS synthesis failed"))
    return result

@app.get("/conversations")
def get_conversations():
    sessions = memory_manager.get_all_sessions()
    return {"sessions": sessions}

@app.get("/conversation/{session_id}")
def get_conversation_history(session_id: str):
    messages = memory_manager.get_session_messages(session_id)
    return {"session_id": session_id, "messages": messages}

@app.delete("/conversation/{session_id}")
def delete_conversation(session_id: str):
    success = memory_manager.delete_session(session_id)
    return {"success": success, "session_id": session_id}

@app.get("/api/search")
async def execute_search(q: str = Query("", alias="q")):
    query = q.strip()
    if not query:
        return {"success": True, "query": "", "results": []}
        
    results = [
        {
            "title": f"Search Results for '{query}'",
            "snippet": f"Showing comprehensive search results and information for '{query}'.",
            "url": f"https://www.google.com/search?q={query}"
        },
        {
            "title": f"Explore '{query}' on Wikipedia",
            "snippet": f"Read detailed articles and encyclopedic knowledge about '{query}'.",
            "url": f"https://en.wikipedia.org/wiki/Special:Search?search={query}"
        }
    ]
    return {
        "success": True,
        "query": query,
        "results": results
    }

# --- FULL VOICE RECORD CRUD ENDPOINTS ---

@app.post("/api/records")
def create_voice_record(request: RecordRequest):
    """ CREATE: Add a new voice record entry. """
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    record = memory_manager.create_record(
        text=request.text.strip(),
        source_language=request.source_language or "auto",
        target_language=request.target_language or "en",
        translated_text=request.translated_text
    )
    return {"success": True, "record": record}

@app.get("/api/records")
def list_voice_records():
    """ READ ALL: Fetch all saved voice records. """
    records = memory_manager.get_all_records()
    return {"success": True, "records": records, "count": len(records)}

@app.get("/api/records/{record_id}")
def get_voice_record(record_id: int):
    """ READ ONE: Get a single record by ID. """
    record = memory_manager.get_record_by_id(record_id)
    if not record:
        raise HTTPException(status_code=404, detail="Record not found")
    return {"success": True, "record": record}

@app.put("/api/records/{record_id}")
def update_voice_record(record_id: int, request: RecordRequest):
    """ UPDATE: Update text or translation of a record. """
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    updated = memory_manager.update_record(
        record_id=record_id,
        text=request.text.strip(),
        translated_text=request.translated_text
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Record not found")
    return {"success": True, "record_id": record_id, "message": "Record updated successfully"}

@app.delete("/api/records/{record_id}")
def delete_voice_record(record_id: int):
    """ DELETE: Delete a voice record by ID. """
    deleted = memory_manager.delete_record_by_id(record_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Record not found")
    return {"success": True, "record_id": record_id, "message": "Record deleted successfully"}

# Mount Frontend directory at root `/`
FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
if FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")

if __name__ == "__main__":
    import uvicorn
    from config import get_free_port
    
    target_port = get_free_port(config.HOST, config.PORT)
    print(f"\n[+] Starting Multilingual AI Agent server on http://{config.HOST}:{target_port}\n")
    uvicorn.run("app:app", host=config.HOST, port=target_port, reload=True)
