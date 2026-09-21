import os
import uuid
import shutil
import logging
import json
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

@app.middleware("http")
async def add_no_cache_headers(request, call_next):
    response = await call_next(request)
    if request.url.path.endswith(".css") or request.url.path.endswith(".js") or request.url.path == "/":
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response

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
    message: Optional[str] = None
    text: Optional[str] = None
    language: Optional[str] = "en"
    source_language: Optional[str] = "auto"

class AskRequest(BaseModel):
    message: Optional[str] = None
    text: Optional[str] = None
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
    language: Optional[str] = Form(None),
    is_translate_on: Optional[str] = Form("true")
):
    """
    OpenAI Whisper API Speech-to-Text Endpoint.
    Transcribes audio using Whisper API preserving spoken language and translates if requested.
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
                "error": "No speech detected. Please speak and try again.",
                "text": "",
                "transcription": "",
                "language": "en",
                "language_name": "English"
            }
        )
        
    if detected_lang == "auto":
        detected_lang = detect_language_from_text(spoken_text)

    should_translate = is_translate_on and is_translate_on.lower() in ["true", "1", "on", "yes"]
    tgt_lang = target_language or translation_language or "te"
    
    # If translation is requested and target equals detected source (e.g. en -> en), fallback target to Telugu ('te')
    if should_translate and (tgt_lang == detected_lang or tgt_lang == "auto"):
        tgt_lang = "te" if detected_lang != "te" else "en"

    translated_text = spoken_text
    if should_translate and spoken_text.strip():
        try:
            if openai_service.is_configured():
                trans_res = openai_service.translate_text(
                    text=spoken_text,
                    source_language=detected_lang,
                    target_language=tgt_lang
                )
                if trans_res.get("success") and trans_res.get("translated_text"):
                    translated_text = trans_res["translated_text"]
            if translated_text == spoken_text:
                translated_text = translate_text(spoken_text, target_language=tgt_lang, source_language=detected_lang)
        except Exception as t_err:
            logger.warning(f"Translation error in transcribe_endpoint: {t_err}")

    from languages import get_language_name
    lang_display_name = get_language_name(detected_lang)

    # Save to session memory without LLM response generation
    memory_manager.save_message(
        session_id=session_id,
        role="user",
        content=spoken_text,
        language=detected_lang,
        original_text=spoken_text,
        source_language=detected_lang,
        translation_language=tgt_lang,
        text_language=detected_lang,
        translated_text=translated_text,
        input_type="voice"
    )

    logger.info(f"Transcription result: '{spoken_text}' (language: {detected_lang} / {lang_display_name}), translation ({tgt_lang}): '{translated_text}'")
    return {
        "success": True,
        "text": spoken_text,
        "transcription": spoken_text,
        "original_text": spoken_text,
        "translation": translated_text,
        "translated_text": translated_text,
        "language": detected_lang,
        "language_name": lang_display_name,
        "source_language": detected_lang,
        "target_language": tgt_lang
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

TECH_KNOWLEDGE = {
    "java": {
        "en": "Java is a high-level, class-based, object-oriented programming language designed to have as few implementation dependencies as possible. It is intended to let application developers write once, run anywhere (WORA), meaning that compiled Java code can run on all platforms supporting Java (via the Java Virtual Machine) without needing to recompile.",
        "te": "జావా (Java) అనేది ఒక ప్రముఖమైన హై-లేవెల్, ఆబ్జెక్ట్-ఓరియెంటెడ్ ప్రోగ్రామింగ్ లాంగ్వేజ్. దీనిని 'Write Once, Run Anywhere' (WORA) అనే సూత్రంతో ఎక్కడైనా రన్ అయ్యేలా తయారుచేశారు."
    },
    "python": {
        "en": "Python is a high-level, interpreted, general-purpose programming language known for its clear syntax and high code readability. It is widely used in Artificial Intelligence, Machine Learning, Data Science, Web Development, Automation, and Scripting.",
        "te": "పైథాన్ (Python) అనేది సరళమైన శైలి కలిగిన ప్రముఖమైన ప్రోగ్రామింగ్ లాంగ్వేజ్. ఇది AI, డేటా సైన్స్, వెబ్ డెవలప్‌మెంట్ మరియు ఆటోమేషన్ లో విస్తృతంగా ఉపయోగించబడుతుంది."
    },
    "javascript": {
        "en": "JavaScript (JS) is a high-level, lightweight, interpreted programming language that powers dynamic and interactive user interfaces on web pages as well as server-side applications via Node.js.",
        "te": "జావాస్క్రిప్ట్ (JavaScript) అనేది వెబ్ పేజీలలో డైనమిక్ మరియు ఇంటరాక్టివ్ ఫీచర్లను అందించే ప్రముఖమైన ప్రోగ్రామింగ్ లాంగ్వేజ్."
    },
    "html": {
        "en": "HTML (HyperText Markup Language) is the standard markup language used to structure content and elements on web pages across the World Wide Web.",
        "te": "HTML (HyperText Markup Language) అనేది వెబ్ పేజీల ఆకృతిని (structure) డిజైన్ చేయడానికి ఉపయోగించే మార్కప్ లాంగ్వేజ్."
    },
    "css": {
        "en": "CSS (Cascading Style Sheets) is a stylesheet language used to format the visual design, colors, layout, and presentation of HTML documents.",
        "te": "CSS (Cascading Style Sheets) అనేది వెబ్ పేజీల డిజైన్, రంగులు మరియు లేఅవుట్ శైలిని అలకరించే స్టైల్‌షీట్ లాంగ్వేజ్."
    },
    "c++": {
        "en": "C++ is a high-performance general-purpose programming language created by Bjarne Stroustrup as an extension of C. It supports procedural, object-oriented, and generic programming.",
        "te": "C++ అనేది సి (C) భాష ఆధారంగా రూపొందించబడిన ఆబ్జెక్ట్ ఓరియెంటెడ్ ప్రోగ్రామింగ్ లాంగ్వేజ్."
    },
    "sql": {
        "en": "SQL (Structured Query Language) is the standard domain-specific language used for storing, updating, manipulating, and querying data in relational database management systems.",
        "te": "SQL అనేది డేటాబేస్ లోని డేటాను స్టోర్ చేయడానికి మరియు క్వెరీ చేయడానికి ఉపయోగించే స్టాండర్డ్ లాంగ్వేజ్."
    },
    "react": {
        "en": "React is an open-source front-end JavaScript library maintained by Meta for building dynamic, component-based user interfaces.",
        "te": "React అనేది డైనమిక్ వెబ్ యూజర్ ఇంటర్‌ఫేస్‌లు తయారు చేయడానికి మేటా (Meta) అందించిన ప్రముఖ జావస్క్రిప్ట్ లైబ్రరీ."
    },
    "ai": {
        "en": "Artificial Intelligence (AI) refers to computer systems and software capable of performing complex tasks that typically require human intelligence, such as visual perception, speech recognition, reasoning, learning, and decision-making.",
        "te": "కృత్రిమ మేధస్సు (AI) అనేది మానవ ఆలోచనా శక్తి, సమస్య పరిష్కారం మరియు అభ్యాస సామర్థ్యాన్ని కంప్యూటర్ల ద్వారా అనుకరించే సాంకేతికత."
    },
    "machine learning": {
        "en": "Machine Learning (ML) is a branch of artificial intelligence focused on building algorithms that enable computers to learn patterns from data and improve their performance without explicit programming.",
        "te": "మెషిన్ లెర్నింగ్ (ML) అనేది AI లో భాగం. ఇది డేటా నుండి నమూనాలను నేర్చుకుని కంప్యూటర్లు తానంతట తానే అంచనా వేసేలా చేస్తుంది."
    }
}

def is_bad_abstract(prompt: str, abstract: str) -> bool:
    if not abstract or len(abstract.strip()) < 35 or len(abstract.strip().split()) < 5:
        return True
    ext_lower = abstract.lower()
    p_lower = prompt.lower()
    
    # If user asks about 'java' without mentioning 'island' or 'indonesia', reject island extracts
    if 'java' in p_lower and not any(w in p_lower for w in ['island', 'indonesia', 'sunda', 'jakarta']):
        if any(w in ext_lower for w in ['sunda islands', 'island in indonesia', 'indonesian population', 'capital city, jakarta', 'dutch east indies', 'history of indonesia']):
            return True

    # Reject Category names or disambiguation pages
    if any(w in ext_lower for w in ['may refer to:', 'can refer to:', 'refers to several', 'disambiguation']) or ext_lower.endswith('category'):
        return True
        
    return False

def get_intelligent_fallback_answer(prompt: str, language: Optional[str] = "en") -> str:
    p_lower = prompt.lower().strip()
    is_telugu = any(ord(c) >= 0x0C00 and ord(c) <= 0x0C7F for c in prompt) or (language and language.startswith("te"))
    lang_key = "te" if is_telugu else "en"

    # 1. Greetings & bot identity
    if any(w in p_lower for w in ["hi", "hii", "hello", "hey", "hii guys", "namaste", "namaskaram"]):
        if is_telugu or any(c in prompt for c in ["హాయ్", "నమస్కారం", "ఏంటి"]):
            return "నమస్కారం! నేను బటర్‌ఫ్లై AI సహాయకుడిని. మీకు నేను ఎలా సహాయపడగలను?"
        return "Hello! I am Butterfly AI, your intelligent voice and text assistant. How can I help you today?"

    if "how are you" in p_lower:
        return "I'm doing great, thank you for asking! How can I assist you with Butterfly AI today?"

    if "who are you" in p_lower or "what are you" in p_lower:
        return "I am Butterfly AI, an intelligent multilingual voice & text assistant designed to transcribe, translate, search, and answer your questions."

    # 2. Check predefined tech knowledge base first
    for tech_name, tech_dict in TECH_KNOWLEDGE.items():
        if re.search(r'\b' + re.escape(tech_name) + r'\b', p_lower):
            return tech_dict.get(lang_key, tech_dict["en"])

    # 3. Disambiguated Wikipedia lookup
    query_title = prompt.strip()
    if "java" in p_lower and "island" not in p_lower:
        query_title = "Java (programming language)"
    elif "python" in p_lower and "snake" not in p_lower and "reptile" not in p_lower:
        query_title = "Python (programming language)"
    elif "ruby" in p_lower and "gem" not in p_lower and "stone" not in p_lower:
        query_title = "Ruby (programming language)"
    elif "rust" in p_lower and "metal" not in p_lower and "iron" not in p_lower:
        query_title = "Rust (programming language)"
    elif "swift" in p_lower and "bird" not in p_lower:
        query_title = "Swift (programming language)"
    elif "react" in p_lower and "chemical" not in p_lower:
        query_title = "React (JavaScript library)"
    elif "node" in p_lower and "network" not in p_lower:
        query_title = "Node.js"

    try:
        wiki_url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(query_title)}"
        req = urllib.request.Request(wiki_url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=4) as response:
            wdata = json.loads(response.read().decode('utf-8'))
            extract = wdata.get("extract")
            if extract and not is_bad_abstract(prompt, extract):
                return extract
    except Exception as w_err:
        logger.warning(f"Wikipedia summary error for '{query_title}': {w_err}")

    # Fallback to direct prompt Wikipedia query if disambiguated title failed
    if query_title != prompt.strip():
        try:
            wiki_url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(prompt.strip())}"
            req = urllib.request.Request(wiki_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as response:
                wdata = json.loads(response.read().decode('utf-8'))
                extract = wdata.get("extract")
                if extract and not is_bad_abstract(prompt, extract):
                    return extract
        except Exception:
            pass

    return f"Butterfly AI processed your question '{prompt}'. To enable deep GPT-4 reasoning, please update your OpenAI API key in Settings."

@app.post("/api/ai/chat")
@app.post("/api/ask")
@app.post("/api/voice/assistant")
@app.post("/api/assistant")
def ai_assistant_endpoint(request: AskRequest):
    prompt = (request.message or request.text or "").strip()
    if not prompt:
        return JSONResponse(
            status_code=400,
            content={"success": False, "error": "Question cannot be empty."}
        )
    
    session_id = f"session_{uuid.uuid4().hex[:8]}"
    
    ai_res = openai_service.generate_chat_response(prompt=prompt, language=request.language)
    
    if ai_res.get("success"):
        answer = ai_res["answer"]
        model = ai_res.get("model", config.AI_MODEL)
    else:
        err_msg = ai_res.get("error") or "Configure your OpenAI API key in Settings."
        return JSONResponse(
            status_code=200,
            content={
                "success": False,
                "error": err_msg
            }
        )
        ddg_answer = ""
        try:
            url = f"https://api.duckduckgo.com/?q={urllib.parse.quote(prompt)}&format=json&no_html=1"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as response:
                data = json.loads(response.read().decode('utf-8'))
                abstract = data.get("AbstractText", "")
                if abstract and not is_bad_abstract(prompt, abstract):
                    ddg_answer = abstract
                elif data.get("RelatedTopics") and isinstance(data.get("RelatedTopics"), list):
                    for topic in data.get("RelatedTopics"):
                        if isinstance(topic, dict) and topic.get("Text"):
                            txt = topic.get("Text")
                            if not is_bad_abstract(prompt, txt):
                                ddg_answer = txt
                                break
        except Exception as ddg_err:
            logger.warning(f"DuckDuckGo instant answer error: {ddg_err}")

        if not ddg_answer:
            ddg_answer = get_intelligent_fallback_answer(prompt, language=request.language)

        answer = ddg_answer
        model = "butterfly-ai-assistant"

    # Save message to memory manager
    memory_manager.save_message(
        session_id=session_id,
        role="user",
        content=prompt,
        language=request.language or "auto",
        original_text=prompt,
        translated_text=answer,
        input_type="assistant"
    )
    
    return {
        "success": True,
        "session_id": session_id,
        "question": prompt,
        "answer": answer,
        "model": model,
        "language": request.language or "auto"
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
