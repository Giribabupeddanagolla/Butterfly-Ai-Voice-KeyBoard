import os
import uuid
import shutil
import logging
import re
import urllib.parse
import urllib.request
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

# Filter out repetitive endpoint access logs from flooding terminal console
class EndpointFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if "/api/translate" in msg or "/health" in msg or "/api/openai/status" in msg:
            return False
        return True

logging.getLogger("uvicorn.access").addFilter(EndpointFilter())

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
    voice: Optional[str] = "nova"
    speed: Optional[float] = 1.0

class VoiceSpeakRequest(BaseModel):
    text: str
    language: Optional[str] = "en"
    voice: Optional[str] = "nova"
    speed: Optional[float] = 1.0

class AssistantRequest(BaseModel):
    text: str
    language: Optional[str] = "en"
    source_language: Optional[str] = "auto"

class PolishRequest(BaseModel):
    text: str
    language: Optional[str] = "auto"

class SnippetRequest(BaseModel):
    name: str
    text: str
    voice_trigger: Optional[str] = None

class APIKeyRequest(BaseModel):
    api_key: str

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
    openai_service.openai_failed = False
    
    tts_service.api_key = new_key
    tts_service.client = None
    tts_service.openai_tts_failed = False
    
    stt_service.api_key = new_key
    stt_service.client = None
    stt_service.openai_stt_failed = False

    whisper_service.api_key = new_key
    whisper_service.client = None
    whisper_service.whisper_failed = False

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

@app.post("/api/voice/speak")
@app.post("/text-to-speech")
def text_to_speech_endpoint(request: VoiceSpeakRequest):
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    result = tts_service.generate_speech(
        text=request.text,
        language=request.language or "en",
        voice=request.voice or "nova",
        speed=request.speed or 1.0
    )
    if not result.get("success"):
        raise HTTPException(status_code=500, detail=result.get("error", "TTS synthesis failed"))
    return result

@app.post("/api/voice/assistant")
@app.post("/api/assistant")
def ai_assistant_endpoint(request: AssistantRequest):
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty")
    
    session_id = f"session_{uuid.uuid4().hex[:8]}"
    prompt = request.text.strip()
    src_lang = request.source_language or "auto"
    tgt_lang = request.language or "en"
    
    answer = ""
    if openai_service.is_configured():
        try:
            from languages import get_language_name
            lang_name = get_language_name(tgt_lang)
            sys_msg = (
                f"You are Butterfly AI, an intelligent, helpful voice assistant. "
                f"Answer the user's question clearly, concisely, and accurately in {lang_name}."
            )
            response = openai_service.get_client().chat.completions.create(
                model=config.AI_MODEL,
                messages=[
                    {"role": "system", "content": sys_msg},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.7,
                max_tokens=400
            )
            answer = response.choices[0].message.content.strip()
        except Exception as e:
            logger.warning(f"OpenAI Assistant completion error: {e}")
            
    if not answer:
        answer = f"Butterfly AI response: I received your question '{prompt}'. Please configure your OpenAI API Key for full AI Assistant completions."
        
    memory_manager.save_message(
        session_id=session_id,
        role="user",
        content=prompt,
        language=src_lang,
        original_text=prompt,
        translated_text=answer,
        input_type="assistant"
    )
    
    return {
        "success": True,
        "session_id": session_id,
        "question": prompt,
        "answer": answer,
        "language": tgt_lang
    }

@app.post("/api/voice/polish")
@app.post("/api/polish")
def ai_polish_endpoint(request: PolishRequest):
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    original = request.text.strip()
    polished = original
    
    if openai_service.is_configured():
        try:
            prompt = (
                f"Clean up filler words (um, uh, actually, like, you know, mhm), fix grammar, add proper punctuation, "
                f"and correct capitalization for the provided text.\n"
                f"STRICT RULES:\n"
                f"- PRESERVE the exact original language and intended meaning.\n"
                f"- Do NOT translate the text.\n"
                f"- Do NOT summarize or add new information.\n"
                f"- Return ONLY the polished text.\n\n"
                f"Text:\n{original}"
            )
            response = openai_service.get_client().chat.completions.create(
                model=config.AI_MODEL,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1
            )
            res_text = response.choices[0].message.content.strip()
            if res_text:
                polished = res_text
        except Exception as e:
            logger.warning(f"AI Polish completion error: {e}")
            
    if polished == original:
        # Simple regex polish fallback
        cleaned = re.sub(r'\b(um+|uh+|er+|ah+|like|actually|you know)\b', '', original, flags=re.IGNORECASE)
        cleaned = re.sub(r'\s+', ' ', cleaned).strip()
        if cleaned:
            polished = cleaned[0].upper() + cleaned[1:]
            if not polished.endswith(('.', '!', '?')):
                polished += '.'

    return {
        "success": True,
        "original_text": original,
        "polished_text": polished
    }

@app.post("/api/voice/upload")
async def audio_upload_endpoint(
    file: UploadFile = File(...),
    source_language: Optional[str] = Form("auto"),
    translation_language: Optional[str] = Form("en")
):
    if not file or not file.filename:
        raise HTTPException(status_code=400, detail="No audio file uploaded")
        
    logger.info(f"Audio upload endpoint received file: {file.filename}")
    temp_filename = f"upload_{uuid.uuid4().hex[:10]}_{file.filename}"
    temp_path = os.path.join(config.AUDIO_INPUT_DIR, temp_filename)
    
    try:
        with open(temp_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
            
        file_size = os.path.getsize(temp_path)
        if file_size == 0:
            raise HTTPException(status_code=400, detail="Uploaded file is empty")
            
        res = whisper_service.transcribe_audio(
            audio_file_path=temp_path,
            language=source_language
        )
        
        spoken_text = res.get("text", "").strip() if res.get("success") else ""
        det_lang = res.get("language", source_language or "en")
        
        if not spoken_text:
            raise HTTPException(status_code=400, detail="Could not transcribe audio from uploaded file.")
            
        translated_text = spoken_text
        if translation_language and translation_language != det_lang:
            translated_text = translate_text(spoken_text, target_language=translation_language, source_language=det_lang)
            
        session_id = f"session_upload_{uuid.uuid4().hex[:8]}"
        memory_manager.save_message(
            session_id=session_id,
            role="user",
            content=spoken_text,
            language=det_lang,
            original_text=spoken_text,
            source_language=det_lang,
            translation_language=translation_language or "en",
            translated_text=translated_text,
            input_type="file_upload"
        )
        
        words = len(spoken_text.split())
        duration_est = f"{max(3, words * 1.2):.0f}s"
        
        return {
            "success": True,
            "filename": file.filename,
            "filesize_bytes": file_size,
            "duration": duration_est,
            "text": spoken_text,
            "original_text": spoken_text,
            "translated_text": translated_text,
            "language": det_lang
        }
    except Exception as e:
        logger.error(f"Audio upload endpoint error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if os.path.exists(temp_path):
            try: os.remove(temp_path)
            except Exception: pass

@app.get("/api/voice/history")
@app.get("/conversations")
@app.get("/api/conversations")
def get_conversations(q: Optional[str] = Query(None)):
    sessions = memory_manager.get_all_sessions(search_query=q)
    return {"success": True, "sessions": sessions}

@app.get("/conversation/{session_id}")
@app.get("/api/conversation/{session_id}")
def get_conversation_session(session_id: str):
    messages = memory_manager.get_session_messages(session_id)
    return {"success": True, "session_id": session_id, "messages": messages}

@app.delete("/api/voice/history/{session_id}")
@app.delete("/conversation/{session_id}")
@app.delete("/api/conversation/{session_id}")
def delete_conversation_session(session_id: str):
    deleted = memory_manager.delete_session(session_id)
    return {"success": deleted, "message": "Session deleted" if deleted else "Session not found"}

@app.get("/api/snippets")
def get_snippets():
    snippets = memory_manager.get_all_snippets()
    return {"success": True, "snippets": snippets}

@app.post("/api/snippets")
def create_snippet_endpoint(request: SnippetRequest):
    if not request.name or not request.text:
        raise HTTPException(status_code=400, detail="Name and text are required for snippets")
    snippet = memory_manager.create_snippet(
        name=request.name,
        text=request.text,
        voice_trigger=request.voice_trigger
    )
    return {"success": True, "snippet": snippet}

@app.delete("/api/snippets/{snippet_id}")
def delete_snippet_endpoint(snippet_id: int):
    deleted = memory_manager.delete_snippet(snippet_id)
    return {"success": deleted, "message": "Snippet deleted" if deleted else "Snippet not found"}

@app.get("/api/search")
@app.get("/search")
def search_endpoint(q: str = Query("", alias="q")):
    query_clean = q.strip() if q else ""
    if not query_clean:
        return {"success": True, "query": "", "results": []}

    results = []

    # 1. Try DuckDuckGo Instant Answer API
    try:
        url = f"https://api.duckduckgo.com/?q={urllib.parse.quote(query_clean)}&format=json&no_html=1"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=4) as response:
            data = json.loads(response.read().decode('utf-8'))
            
            if data.get("AbstractText") and data.get("AbstractURL"):
                results.append({
                    "title": data.get("Heading") or query_clean,
                    "url": data.get("AbstractURL"),
                    "snippet": data.get("AbstractText")
                })
            
            for topic in data.get("RelatedTopics", []):
                if isinstance(topic, dict) and topic.get("FirstURL") and topic.get("Text"):
                    results.append({
                        "title": topic.get("Text").split(" - ")[0] if " - " in topic.get("Text") else topic.get("Text")[:60],
                        "url": topic.get("FirstURL"),
                        "snippet": topic.get("Text")
                    })
                if len(results) >= 5:
                    break
    except Exception as e:
        logger.warning(f"DuckDuckGo API search error: {e}")

    # 2. Try DuckDuckGo HTML search if no instant results
    if not results:
        try:
            url = f"https://html.duckduckgo.com/html/?q={urllib.parse.quote(query_clean)}"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
            with urllib.request.urlopen(req, timeout=4) as response:
                html = response.read().decode('utf-8', errors='ignore')
                matches = re.findall(r'<a class="result__url" href="([^"]+)".*?>(.*?)</a>.*?<a class="result__snippet".*?>(.*?)</a>', html, re.DOTALL)
                for u, t, s in matches[:5]:
                    clean_t = re.sub(r'<[^>]+>', '', t).strip()
                    clean_s = re.sub(r'<[^>]+>', '', s).strip()
                    results.append({"title": clean_t or query_clean, "url": u.strip(), "snippet": clean_s})
        except Exception as e:
            logger.warning(f"DuckDuckGo HTML search error: {e}")

    # 3. Direct web search fallback links if still empty
    if not results:
        results.append({
            "title": f"Google Web Search: {query_clean}",
            "url": f"https://www.google.com/search?q={urllib.parse.quote(query_clean)}",
            "snippet": f"Click to view live Google web search results for '{query_clean}'."
        })
        results.append({
            "title": f"DuckDuckGo Search: {query_clean}",
            "url": f"https://duckduckgo.com/?q={urllib.parse.quote(query_clean)}",
            "snippet": f"Click to view live DuckDuckGo web search results for '{query_clean}'."
        })
        results.append({
            "title": f"Wikipedia Search: {query_clean}",
            "url": f"https://en.wikipedia.org/wiki/Special:Search?search={urllib.parse.quote(query_clean)}",
            "snippet": f"Click to search Wikipedia encyclopedia for '{query_clean}'."
        })

    return {"success": True, "query": query_clean, "results": results}

# Mount Frontend directory at root `/`
FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
if FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")

if __name__ == "__main__":
    import uvicorn
    from config import get_free_port
    
    target_port = get_free_port(config.HOST, config.PORT)
    print(f"\n[+] Starting Multilingual AI Agent server on http://{config.HOST}:{target_port}\n")
    uvicorn.run("app:app", host=config.HOST, port=target_port, reload=True, reload_excludes=["*.webm", "*.wav", "*.mp3", "*.ogg", "*.db", "audio/*", "temp_uploads/*", "audio/input/*", "audio/output/*"])
