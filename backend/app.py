import os
import sys
import uuid
import shutil
import logging
import json
import re
import urllib.parse
import urllib.request
import secrets
from typing import Optional
from pathlib import Path
from contextlib import asynccontextmanager

# Ensure backend directory is in sys.path so internal imports (config, memory, etc.) work from anywhere
BACKEND_DIR = Path(__file__).resolve().parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from fastapi import FastAPI, File, UploadFile, Form, HTTPException, BackgroundTasks, Query, Request, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, PlainTextResponse
from pydantic import BaseModel

from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from config import config, is_openai_active, mark_openai_failed
from memory import memory_manager
from ai_agent import ai_agent, get_mock_response
from speech_to_text import stt_service, detect_language_from_text, translate_text
from languages import get_language_name, normalize_language_code
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
if is_openai_active():
    logger.info("OpenAI API configuration loaded successfully.")
else:
    logger.warning("WARNING: OPENAI_API_KEY is not configured or using placeholder. Fallback STT/translation enabled.")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Purge stale temporary audio files on startup
    try:
        tts_service.cleanup_output_cache(force=True)
    except Exception as e:
        logger.warning(f"Initial audio cache cleanup error: {e}")

    import threading
    def check_openai():
        if is_openai_active():
            try:
                from openai import OpenAI
                client = OpenAI(api_key=config.OPENAI_API_KEY, timeout=2.5, max_retries=0)
                client.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[{"role": "user", "content": "hi"}],
                    max_tokens=1
                )
                logger.info("OpenAI API key validated successfully.")
            except Exception as e:
                err_str = str(e)
                logger.warning(f"OpenAI API key validation failed on startup: {err_str}. Pre-emptively switching to fast free fallback STT/translation.")
                mark_openai_failed(err_str)
    threading.Thread(target=check_openai, daemon=True).start()
    yield

app = FastAPI(
    title="Multilingual Voice AI Agent API",
    description="Backend API for Multilingual Voice & Text AI Assistant",
    version="1.0.0",
    lifespan=lifespan
)


# Rate Limiting configuration via SlowAPI
limiter = Limiter(key_func=get_remote_address, default_limits=["120/minute"])
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

# CORS configuration - restricted in production, flexible in development
allowed_origins = config.get_allowed_origins()
if config.ENVIRONMENT == "production":
    logger.info(f"Production environment detected: Restricting CORS to {allowed_origins}")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins if allowed_origins else ["https://butterfly-ai-voice-keyboard.onrender.com"],
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
        allow_headers=["*"],
    )
else:
    logger.info("Development environment detected: Allowing localhost and mobile origins")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_origin_regex=r"^https?://(localhost|127\.0\.0\.1|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2[0-9]|3[0-1])\.\d+\.\d+)(:\d+)?$",
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

# Ensure audio storage directories exist before mounting
Path(config.AUDIO_OUTPUT_DIR).mkdir(parents=True, exist_ok=True)
Path(config.AUDIO_INPUT_DIR).mkdir(parents=True, exist_ok=True)

# Mount audio output directory statically
app.mount("/audio/output", StaticFiles(directory=config.AUDIO_OUTPUT_DIR), name="audio_output")

# Pydantic Schemas
class ChatRequest(BaseModel):
    session_id: Optional[str] = None
    message: str
    language: Optional[str] = None
    source_language: Optional[str] = "auto"
    target_language: Optional[str] = None

class ChatResponse(BaseModel):
    session_id: str
    message: str
    language: str

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
    session_id: Optional[str] = None
    prompt: Optional[str] = None
    message: Optional[str] = None
    text: Optional[str] = None
    language: Optional[str] = None
    source_language: Optional[str] = "auto"
    target_language: Optional[str] = None

class AskRequest(BaseModel):
    session_id: Optional[str] = None
    prompt: Optional[str] = None
    message: Optional[str] = None
    text: Optional[str] = None
    language: Optional[str] = None
    source_language: Optional[str] = "auto"
    target_language: Optional[str] = None

class PolishRequest(BaseModel):
    text: str
    language: Optional[str] = "auto"
class APIKeyRequest(BaseModel):
    api_key: str
    admin_token: Optional[str] = None

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
@limiter.limit("5/minute")
def update_api_key(
    request: Request,
    body: APIKeyRequest,
    x_admin_token: Optional[str] = Header(None),
    authorization: Optional[str] = Header(None)
):
    # Authorization verification
    auth_token = None
    if body.admin_token and body.admin_token.strip():
        auth_token = body.admin_token.strip()
    elif x_admin_token and x_admin_token.strip():
        auth_token = x_admin_token.strip()
    elif authorization and authorization.strip():
        parts = authorization.strip().split(" ", 1)
        if len(parts) == 2 and parts[0].lower() == "bearer":
            auth_token = parts[1].strip()
        else:
            auth_token = authorization.strip()

    expected_token = (config.ADMIN_SECRET_KEY or "").strip()

    # In production, ADMIN_SECRET_KEY MUST be configured and match
    if config.ENVIRONMENT == "production":
        if not expected_token:
            logger.error("ADMIN_SECRET_KEY is not configured in production. Blocking key update.")
            raise HTTPException(
                status_code=403,
                detail="Forbidden: ADMIN_SECRET_KEY must be configured on the server in production before updating settings."
            )
        if not auth_token or not secrets.compare_digest(auth_token, expected_token):
            raise HTTPException(
                status_code=401,
                detail="Unauthorized: Valid admin token required to update API key."
            )
    else:
        # In non-production, if ADMIN_SECRET_KEY is set, enforce it
        if expected_token:
            if not auth_token or not secrets.compare_digest(auth_token, expected_token):
                raise HTTPException(
                    status_code=401,
                    detail="Unauthorized: Valid admin token required to update API key."
                )
        else:
            logger.warning("ADMIN_SECRET_KEY is not set in non-production. Permitting key update.")

    new_key = body.api_key.strip()
    # Strip any carriage returns or newlines to prevent .env injection
    new_key = re.sub(r'[\r\n]', '', new_key)
    if not new_key:
        raise HTTPException(status_code=400, detail="API key cannot be empty")
    if not new_key.startswith("sk-"):
        raise HTTPException(status_code=400, detail="Invalid OpenAI API key format (must start with 'sk-')")
    
    # 1. Update config & services in memory
    os.environ["OPENAI_API_KEY"] = new_key
    config.OPENAI_API_KEY = new_key
    config.OPENAI_FAILED = False
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
@limiter.limit("45/minute")
def text_translate_endpoint(request: Request, body: TranslateRequest):
    session_id = body.session_id or f"session_{uuid.uuid4().hex[:8]}"
    if not body.text or not body.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    tgt_lang = body.target_language or body.translation_language or "en"
    result = ai_agent.process_translation(
        session_id=session_id,
        user_message=body.text,
        source_language=body.source_language or "auto",
        translation_language=tgt_lang,
        text_language=body.text_language or tgt_lang,
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
@limiter.limit("45/minute")
def translate_endpoint(request: Request, body: TranslateRequest):
    if not body.text or not body.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    
    src_lang = body.source_language or "auto"
    tgt_lang = body.target_language or body.translation_language or body.text_language or "en"
    if tgt_lang == "auto" or not tgt_lang.strip():
        tgt_lang = "en"
    
    if src_lang != "auto" and src_lang == tgt_lang:
        return {
            "success": True,
            "translation": body.text,
            "translated_text": body.text,
            "original_text": body.text,
            "source_language": src_lang,
            "target_language": tgt_lang
        }
    
    from config import is_openai_active
    if is_openai_active() and openai_service.is_configured() and not getattr(openai_service, "openai_failed", False):
        trans_res = openai_service.translate_text(
            text=body.text,
            source_language=src_lang,
            target_language=tgt_lang
        )
        if trans_res.get("success") and trans_res.get("translated_text") and trans_res.get("translated_text").strip() != body.text.strip():
            return {
                "success": True,
                "translation": trans_res["translated_text"],
                "translated_text": trans_res["translated_text"],
                "original_text": body.text,
                "source_language": src_lang,
                "target_language": tgt_lang
            }
            
    translated = translate_text(
        text=body.text,
        target_language=tgt_lang,
        source_language=src_lang
    )
    
    return {
        "success": True,
        "translation": translated,
        "translated_text": translated,
        "original_text": body.text,
        "source_language": src_lang,
        "target_language": tgt_lang
    }

@app.post("/api/transcribe")
@app.post("/api/voice/transcribe")
@app.post("/api/voice-translate")
@app.post("/speech-to-text")
@limiter.limit("30/minute")
async def transcribe_endpoint(
    request: Request,
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
        raw_name = audio.filename or 'recording.webm'
        raw_ext = Path(raw_name).suffix.lower()
        if raw_ext not in config.ALLOWED_AUDIO_EXTENSIONS:
            raise HTTPException(
                status_code=400,
                detail=f"Unsupported audio format '{raw_ext}'. Allowed formats: {', '.join(sorted(config.ALLOWED_AUDIO_EXTENSIONS))}"
            )
        logger.info(f"Received audio file upload: {audio.filename}, content_type={audio.content_type}")
        temp_filename = f"whisper_{uuid.uuid4().hex[:10]}_{raw_name}"
        temp_path = os.path.join(config.AUDIO_INPUT_DIR, temp_filename)
        try:
            total_size = 0
            chunk_size = 1024 * 1024
            with open(temp_path, "wb") as buffer:
                while True:
                    chunk = await audio.read(chunk_size)
                    if not chunk:
                        break
                    total_size += len(chunk)
                    if total_size > config.MAX_AUDIO_FILE_SIZE:
                        raise HTTPException(
                            status_code=413,
                            detail=f"Audio file exceeds maximum allowed size of {config.MAX_AUDIO_FILE_SIZE // (1024 * 1024)}MB"
                        )
                    buffer.write(chunk)
            
            file_size = total_size
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
        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Whisper transcription endpoint error: {e}")
            if clean_fallback:
                spoken_text = clean_fallback
        finally:
            if os.path.exists(temp_path):
                try: os.remove(temp_path)
                except Exception: pass
            try:
                tts_service.cleanup_output_cache()
            except Exception:
                pass

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
    tgt_lang = target_language or translation_language or ""
    if not tgt_lang or tgt_lang == "auto":
        tgt_lang = "en"

    translated_text = spoken_text
    if should_translate and tgt_lang != detected_lang and spoken_text.strip():
        try:
            from config import is_openai_active
            if is_openai_active() and openai_service.is_configured() and not getattr(openai_service, "openai_failed", False):
                trans_res = openai_service.translate_text(
                    text=spoken_text,
                    source_language=detected_lang,
                    target_language=tgt_lang
                )
                if trans_res.get("success") and trans_res.get("translated_text") and trans_res.get("translated_text").strip() != spoken_text.strip():
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
@limiter.limit("30/minute")
def text_to_speech_endpoint(request: Request, body: VoiceSpeakRequest):
    if not body.text or not body.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    result = tts_service.generate_speech(
        text=body.text,
        language=body.language or "en",
        voice=body.voice or "nova",
        speed=body.speed or 1.0
    )
    if not result.get("success"):
        raise HTTPException(status_code=500, detail=result.get("error", "TTS synthesis failed"))
    return result

TECH_KNOWLEDGE = {
    "java": {
        "en": "Java is a high-level, class-based, object-oriented programming language designed to have as few implementation dependencies as possible. It is intended to let application developers write once, run anywhere (WORA), meaning that compiled Java code can run on all platforms supporting Java (via the Java Virtual Machine) without needing to recompile.",
        "te": "జావా (Java) అనేది ఒక ప్రముఖమైన హై-లేవెల్, ఆబ్జెక్ట్-ఓరియెంటెడ్ ప్రోగ్రామింగ్ లాంగ్వేజ్. దీనిని 'Write Once, Run Anywhere' (WORA) అనే సూత్రంతో ఎక్కడైనా రన్ అయ్యేలా తయారుచేశారు.",
        "url": "https://en.wikipedia.org/wiki/Java_(programming_language)",
        "title": "Java Programming Language - Wikipedia"
    },
    "python": {
        "en": "Python is a high-level, interpreted, general-purpose programming language known for its clear syntax and high code readability. It is widely used in Artificial Intelligence, Machine Learning, Data Science, Web Development, Automation, and Scripting.",
        "te": "పైథాన్ (Python) అనేది సరళమైన శైలి కలిగిన ప్రముఖమైన ప్రోగ్రామింగ్ లాంగ్వేజ్. ఇది AI, డేటా సైన్స్, వెబ్ డెవలప్‌మెంట్ మరియు ఆటోమేషన్ లో విస్తృతంగా ఉపయోగించబడుతుంది.",
        "url": "https://en.wikipedia.org/wiki/Python_(programming_language)",
        "title": "Python Programming Language - Wikipedia"
    },
    "javascript": {
        "en": "JavaScript (JS) is a high-level, lightweight, interpreted programming language that powers dynamic and interactive user interfaces on web pages as well as server-side applications via Node.js.",
        "te": "జావాస్క్రిప్ట్ (JavaScript) అనేది వెబ్ పేజీలలో డైనమిక్ మరియు ఇంటరాక్టివ్ ఫీచర్లను అందించే ప్రముఖమైన ప్రోగ్రామింగ్ లాంగ్వేజ్.",
        "url": "https://en.wikipedia.org/wiki/JavaScript",
        "title": "JavaScript - Wikipedia"
    },
    "devops": {
        "en": "DevOps is a set of practices that combines software development (Dev) and IT operations (Ops) to shorten the development life cycle and provide continuous delivery with high software quality.",
        "te": "DevOps అనేది సాఫ్ట్‌వేర్ అభివృద్ధి (Dev) మరియు సమాచార సాంకేతిక కార్యకలాపాలు (Ops) కలిపే ఒక ఆధునిక సాంకేతిక విధానం. ఇది సాఫ్ట్‌వేర్ అభివృద్ధిని, టెస్టింగ్‌ను ఆటోమేట్ చేసి నాణ్యతతో కూడిన డెలివరీని వేగవంతం చేస్తుంది.",
        "url": "https://en.wikipedia.org/wiki/DevOps",
        "title": "DevOps - Wikipedia"
    },
    "cloud computing": {
        "en": "Cloud computing is the on-demand availability of computer system resources, especially data storage and computing power, without direct active management by the user.",
        "te": "క్లౌడ్ కంప్యూటింగ్ (Cloud Computing) అనేది ఇంటర్నెట్ ద్వారా కంప్యూటర్ వనరులు, డేటా స్టోరేజ్ మరియు సర్వర్‌లను రిమోట్‌గా అందించే ఆధునిక సాంకేతికత.",
        "url": "https://en.wikipedia.org/wiki/Cloud_computing",
        "title": "Cloud Computing - Wikipedia"
    },
    "docker": {
        "en": "Docker is an open platform for developing, shipping, and running applications inside lightweight, portable containers.",
        "te": "డాకర్ (Docker) అనేది అప్లికేషన్లను తేలికపాటి కంటైనర్ల రూపంలో ప్యాక్ చేసి, ఏ ఆపరేటింగ్ సిస్టమ్‌లోనైనా స్థిరంగా రన్ చేయడానికి ఉపయోగపడే ప్లాట్‌ఫామ్.",
        "url": "https://en.wikipedia.org/wiki/Docker_(software)",
        "title": "Docker - Wikipedia"
    },
    "kubernetes": {
        "en": "Kubernetes is an open-source container orchestration system for automating software deployment, scaling, and management.",
        "te": "కుబెర్నెటిస్ (Kubernetes) అనేది కంటైనరైజ్డ్ అప్లికేషన్ల విస్తరణ, స్కేలింగ్ మరియు ఆటోమేషన్ నిర్వహణకు ఉపయోగపడే ప్రముఖ ఓపెన్ సోర్స్ సిస్టమ్.",
        "url": "https://en.wikipedia.org/wiki/Kubernetes",
        "title": "Kubernetes - Wikipedia"
    },
    "html": {
        "en": "HTML (HyperText Markup Language) is the standard markup language used to structure content and elements on web pages across the World Wide Web.",
        "te": "HTML (HyperText Markup Language) అనేది వెబ్ పేజీల ఆకృతిని (structure) డిజైన్ చేయడానికి ఉపయోగించే మార్కప్ లాంగ్వేజ్.",
        "url": "https://en.wikipedia.org/wiki/HTML",
        "title": "HTML - Wikipedia"
    },
    "css": {
        "en": "CSS (Cascading Style Sheets) is a stylesheet language used to format the visual design, colors, layout, and presentation of HTML documents.",
        "te": "CSS (Cascading Style Sheets) అనేది వెబ్ పేజీల డిజైన్, రంగులు మరియు లేఅవుట్ శైలిని అలకరించే స్టైల్‌షీట్ లాంగ్వేజ్.",
        "url": "https://en.wikipedia.org/wiki/CSS",
        "title": "CSS - Wikipedia"
    },
    "c++": {
        "en": "C++ is a high-performance general-purpose programming language created by Bjarne Stroustrup as an extension of C. It supports procedural, object-oriented, and generic programming.",
        "te": "C++ అనేది సి (C) భాష ఆధారంగా రూపొందించబడిన ఆబ్జెక్ట్ ఓరియెంటెడ్ ప్రోగ్రామింగ్ లాంగ్వేజ్.",
        "url": "https://en.wikipedia.org/wiki/C%2B%2B",
        "title": "C++ - Wikipedia"
    },
    "sql": {
        "en": "SQL (Structured Query Language) is the standard domain-specific language used for storing, updating, manipulating, and querying data in relational database management systems.",
        "te": "SQL అనేది డేటాబేస్ లోని డేటాను స్టోర్ చేయడానికి మరియు క్వెరీ చేయడానికి ఉపయోగించే స్టాండర్డ్ లాంగ్వేజ్.",
        "url": "https://en.wikipedia.org/wiki/SQL",
        "title": "SQL - Wikipedia"
    },
    "react": {
        "en": "React is an open-source front-end JavaScript library maintained by Meta for building dynamic, component-based user interfaces.",
        "te": "React అనేది డైనమిక్ వెబ్ యూజర్ ఇంటర్‌ఫేస్‌లు తయారు చేయడానికి మేటా (Meta) అందించిన ప్రముఖ జావస్క్రిప్ట్ లైబ్రరీ.",
        "url": "https://en.wikipedia.org/wiki/React_(software)",
        "title": "React (JavaScript library) - Wikipedia"
    },
    "ai": {
        "en": "Artificial Intelligence (AI) refers to computer systems and software capable of performing complex tasks that typically require human intelligence, such as visual perception, speech recognition, reasoning, learning, and decision-making.",
        "te": "కృత్రిమ మేధస్సు (AI) అనేది మానవ ఆలోచనా శక్తి, సమస్య పరిష్కారం మరియు అభ్యాస సామర్థ్యాన్ని కంప్యూటర్ల ద్వారా అనుకరించే సాంకేతికత.",
        "url": "https://en.wikipedia.org/wiki/Artificial_intelligence",
        "title": "Artificial Intelligence - Wikipedia"
    },
    "machine learning": {
        "en": "Machine Learning (ML) is a branch of artificial intelligence focused on building algorithms that enable computers to learn patterns from data and improve their performance without explicit programming.",
        "te": "మెషిన్ లెర్నింగ్ (ML) అనేది AI లో భాగం. ఇది డేటా నుండి నమూనాలను నేర్చుకుని కంప్యూటర్లు తానంతట తానే అంచనా వేసేలా చేస్తుంది.",
        "url": "https://en.wikipedia.org/wiki/Machine_learning",
        "title": "Machine Learning - Wikipedia"
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

def get_intelligent_fallback_details(prompt: str, language: Optional[str] = "en", source_language: Optional[str] = "auto") -> dict:
    """Retrieve intelligent answer via tech knowledge, Wikipedia, or DuckDuckGo and translate to target language, returning answer, source_url, source_title."""
    p_lower = prompt.lower().strip()
    
    # 1. Resolve prompt language and target language
    detected_prompt_lang = detect_language_from_text(prompt) or "en"
    
    target_lang = normalize_language_code(language) if (language and language != "auto") else None
    if not target_lang:
        target_lang = detected_prompt_lang
    elif target_lang == "en" and detected_prompt_lang != "en":
        # Question was asked in non-English (e.g. Telugu), match question language!
        target_lang = detected_prompt_lang

    src_lang = normalize_language_code(source_language) if (source_language and source_language != "auto") else None
    if not src_lang:
        src_lang = detected_prompt_lang

    # 2. If prompt is not English, translate to English for high-quality information search
    english_prompt = prompt
    if src_lang != "en" and src_lang != "auto":
        try:
            ep = translate_text(prompt, target_language="en", source_language=src_lang)
            if ep and ep.strip():
                english_prompt = ep.strip()
        except Exception as tr_err:
            logger.debug(f"Prompt translation to English failed: {tr_err}")
    ep_lower = english_prompt.lower().strip()

    default_google_url = f"https://www.google.com/search?q={urllib.parse.quote(prompt)}"
    default_title = f"Web Search: {prompt}"

    # 3. Greetings & bot identity
    is_greeting = False
    if len(p_lower.split()) <= 4:
        greeting_pattern = r'\b(hi|hii|hello|hey|namaste|namaskaram)\b'
        if re.search(greeting_pattern, p_lower) or re.search(greeting_pattern, ep_lower):
            is_greeting = True

    if is_greeting:
        base_greeting = "Hello! I am Butterfly AI, your intelligent multilingual voice and text assistant. How can I help you today?"
        ans = base_greeting
        if target_lang != "en":
            try:
                tg = translate_text(base_greeting, target_language=target_lang, source_language="en")
                if tg and tg.strip(): ans = tg.strip()
            except Exception: pass
        return {"answer": ans, "source_url": default_google_url, "source_title": "Butterfly AI Assistant"}

    if "how are you" in p_lower or "how are you" in ep_lower:
        base_resp = "I'm doing great, thank you for asking! How can I assist you with Butterfly AI today?"
        ans = base_resp
        if target_lang != "en":
            try:
                tr = translate_text(base_resp, target_language=target_lang, source_language="en")
                if tr and tr.strip(): ans = tr.strip()
            except Exception: pass
        return {"answer": ans, "source_url": default_google_url, "source_title": "Butterfly AI Assistant"}

    if any(w in p_lower or w in ep_lower for w in ["who are you", "what are you"]):
        base_resp = "I am Butterfly AI, an intelligent multilingual voice & text assistant designed to transcribe, translate, search, and answer your questions."
        ans = base_resp
        if target_lang != "en":
            try:
                tr = translate_text(base_resp, target_language=target_lang, source_language="en")
                if tr and tr.strip(): ans = tr.strip()
            except Exception: pass
        return {"answer": ans, "source_url": default_google_url, "source_title": "Butterfly AI Assistant"}

    # 4. Check predefined tech knowledge base
    for tech_name, tech_dict in TECH_KNOWLEDGE.items():
        if re.search(r'\b' + re.escape(tech_name) + r'\b', p_lower) or re.search(r'\b' + re.escape(tech_name) + r'\b', ep_lower):
            raw_ans = tech_dict.get(target_lang) or tech_dict.get("en")
            if target_lang != "en" and target_lang not in tech_dict:
                try:
                    tr = translate_text(tech_dict["en"], target_language=target_lang, source_language="en")
                    if tr and tr.strip(): raw_ans = tr.strip()
                except Exception: pass
            s_url = tech_dict.get("url") or default_google_url
            s_title = tech_dict.get("title") or f"{tech_name.title()} - Overview"
            return {"answer": raw_ans, "source_url": s_url, "source_title": s_title}

    # 5. Extract clean search topic from English prompt
    clean_topic = re.sub(
        r'^(what is|what are|who is|who are|who was|tell me about|explain|describe|what do you mean by|define|meaning of)\s+',
        '',
        ep_lower,
        flags=re.IGNORECASE
    ).strip(' ?.!').strip()
    topic_query = clean_topic if clean_topic else english_prompt.strip(' ?.!').strip()

    # Disambiguated Wikipedia query titles
    query_title = topic_query
    if "java" in topic_query and "island" not in topic_query:
        query_title = "Java (programming language)"
    elif "python" in topic_query and "snake" not in topic_query and "reptile" not in topic_query:
        query_title = "Python (programming language)"
    elif "ruby" in topic_query and "gem" not in topic_query and "stone" not in topic_query:
        query_title = "Ruby (programming language)"
    elif "rust" in topic_query and "metal" not in topic_query and "iron" not in topic_query:
        query_title = "Rust (programming language)"
    elif "swift" in topic_query and "bird" not in topic_query:
        query_title = "Swift (programming language)"
    elif "react" in topic_query and "chemical" not in topic_query:
        query_title = "React (JavaScript library)"
    elif "node" in topic_query and "network" not in topic_query:
        query_title = "Node.js"
    elif "devops" in topic_query:
        query_title = "DevOps"

    raw_answer = None
    source_url = None
    source_title = None

    # Try Wikipedia page summary for disambiguated query_title
    try:
        wiki_url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(query_title)}"
        req = urllib.request.Request(wiki_url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=4) as response:
            wdata = json.loads(response.read().decode('utf-8'))
            extract = wdata.get("extract")
            if extract and not is_bad_abstract(topic_query, extract):
                raw_answer = extract
                source_url = wdata.get("content_urls", {}).get("desktop", {}).get("page") or f"https://en.wikipedia.org/wiki/{urllib.parse.quote(query_title)}"
                source_title = f"{wdata.get('title') or query_title} - Wikipedia"
    except Exception as w_err:
        logger.debug(f"Wikipedia summary error for '{query_title}': {w_err}")

    # Fallback to direct prompt / topic query if disambiguated title failed
    if not raw_answer and query_title != topic_query:
        try:
            wiki_url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(topic_query)}"
            req = urllib.request.Request(wiki_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as response:
                wdata = json.loads(response.read().decode('utf-8'))
                extract = wdata.get("extract")
                if extract and not is_bad_abstract(topic_query, extract):
                    raw_answer = extract
                    source_url = wdata.get("content_urls", {}).get("desktop", {}).get("page") or f"https://en.wikipedia.org/wiki/{urllib.parse.quote(topic_query)}"
                    source_title = f"{wdata.get('title') or topic_query} - Wikipedia"
        except Exception:
            pass

    # Try Wikipedia opensearch to find closest title if still no answer
    if not raw_answer:
        try:
            search_url = f"https://en.wikipedia.org/w/api.php?action=opensearch&search={urllib.parse.quote(topic_query)}&limit=1&namespace=0&format=json"
            s_req = urllib.request.Request(search_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(s_req, timeout=3) as s_res:
                s_data = json.loads(s_res.read().decode('utf-8'))
                if s_data and len(s_data) > 1 and s_data[1]:
                    found_title = s_data[1][0]
                    found_url = s_data[3][0] if len(s_data) > 3 and s_data[3] else f"https://en.wikipedia.org/wiki/{urllib.parse.quote(found_title)}"
                    w_sum_url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(found_title)}"
                    w_sum_req = urllib.request.Request(w_sum_url, headers={'User-Agent': 'Mozilla/5.0'})
                    with urllib.request.urlopen(w_sum_req, timeout=3) as w_sum_res:
                        w_sum_data = json.loads(w_sum_res.read().decode('utf-8'))
                        ext = w_sum_data.get("extract")
                        if ext and not is_bad_abstract(topic_query, ext):
                            raw_answer = ext
                            source_url = found_url
                            source_title = f"{found_title} - Wikipedia"
        except Exception:
            pass

    # Try DuckDuckGo instant answer
    if not raw_answer:
        try:
            ddg_url = f"https://api.duckduckgo.com/?q={urllib.parse.quote(english_prompt)}&format=json&no_html=1"
            req = urllib.request.Request(ddg_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as response:
                data = json.loads(response.read().decode('utf-8'))
                abstract = data.get("AbstractText", "")
                if abstract and not is_bad_abstract(english_prompt, abstract):
                    raw_answer = abstract
                    source_url = data.get("AbstractURL") or f"https://duckduckgo.com/?q={urllib.parse.quote(english_prompt)}"
                    source_title = data.get("Heading") or f"{topic_query.title()} - Web Search"
                elif data.get("RelatedTopics") and isinstance(data.get("RelatedTopics"), list):
                    for topic in data.get("RelatedTopics"):
                        if isinstance(topic, dict) and topic.get("Text"):
                            txt = topic.get("Text")
                            if not is_bad_abstract(english_prompt, txt):
                                raw_answer = txt
                                source_url = topic.get("FirstURL") or f"https://duckduckgo.com/?q={urllib.parse.quote(english_prompt)}"
                                source_title = f"{topic_query.title()} - Web Search"
                                break
        except Exception as ddg_err:
            logger.debug(f"DuckDuckGo error: {ddg_err}")

    # Try DuckDuckGo HTML Search for live snippets and direct links if still empty
    if not raw_answer:
        try:
            url = f"https://html.duckduckgo.com/html/?q={urllib.parse.quote(english_prompt)}"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
            with urllib.request.urlopen(req, timeout=4) as response:
                html = response.read().decode('utf-8', errors='ignore')
                matches = re.findall(r'<a class="result__url" href="([^"]+)".*?>(.*?)</a>.*?<a class="result__snippet".*?>(.*?)</a>', html, re.DOTALL)
                if matches:
                    u, t, s = matches[0]
                    clean_t = re.sub(r'<[^>]+>', '', t).strip()
                    clean_s = re.sub(r'<[^>]+>', '', s).strip()
                    if clean_s and not is_bad_abstract(english_prompt, clean_s):
                        raw_answer = clean_s
                        dest_url = u.strip()
                        if dest_url.startswith("//"):
                            dest_url = "https:" + dest_url
                        elif not dest_url.startswith("http"):
                            dest_url = "https://" + dest_url
                        source_url = dest_url
                        source_title = clean_t or f"{topic_query.title()} - Web Search"
        except Exception as html_err:
            logger.debug(f"DuckDuckGo HTML error: {html_err}")

    # Fallback to direct informative synthesis if still no answer
    if not raw_answer:
        base_synth = f"Regarding '{english_prompt}': You can explore detailed articles, guides, and tutorials on this topic via the related website link below."
        raw_answer = base_synth
        source_url = default_google_url
        source_title = default_title

    # Translate raw_answer to target_lang
    final_answer = raw_answer
    if target_lang != "en":
        try:
            translated_res = translate_text(raw_answer, target_language=target_lang, source_language="en")
            if translated_res and translated_res.strip():
                final_answer = translated_res.strip()
        except Exception as tr_err:
            logger.warning(f"Error translating answer to {target_lang}: {tr_err}")

    return {
        "answer": final_answer,
        "source_url": source_url or default_google_url,
        "source_title": source_title or default_title
    }

def get_intelligent_fallback_answer(prompt: str, language: Optional[str] = "en", source_language: Optional[str] = "auto") -> str:
    """Retrieve intelligent answer via tech knowledge, Wikipedia, or DuckDuckGo and translate to target language."""
    details = get_intelligent_fallback_details(prompt, language=language, source_language=source_language)
    return details.get("answer", "")

@app.post("/chat")
@app.post("/api/chat")
@limiter.limit("30/minute")
def chat_endpoint(request: Request, body: ChatRequest) -> ChatResponse:
    if not body.message or not body.message.strip():
        raise HTTPException(
            status_code=400,
            detail="Message cannot be empty."
        )
    if body.session_id is not None and not body.session_id.strip():
        raise HTTPException(
            status_code=400,
            detail="Invalid session_id: cannot be empty whitespace."
        )

    # Determine requested target language
    req_target = body.target_language or body.language
    if req_target and req_target.lower() != "auto":
        target_lang = normalize_language_code(req_target)
    else:
        target_lang = detect_language_from_text(body.message) or "en"

    req_source = body.source_language or "auto"

    try:
        result = ai_agent.handle_chat_request(
            message=body.message,
            session_id=body.session_id,
            language=target_lang,
            target_language=target_lang,
            source_language=req_source
        )
        return ChatResponse(
            session_id=result["session_id"],
            message=result["message"],
            language=result["language"]
        )
    except ValueError as ve:
        raise HTTPException(status_code=400, detail=str(ve))
    except RuntimeError as re:
        err_msg = str(re)
        if "Database error" in err_msg:
            raise HTTPException(status_code=500, detail="Database operation failed.")
        elif "OpenAI service error" in err_msg:
            raise HTTPException(status_code=502, detail=err_msg)
        else:
            raise HTTPException(status_code=500, detail="Chat service error.")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Unexpected error in /chat: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Internal chat service error.")

@app.post("/api/ai/chat")
@app.post("/api/ask")
@app.post("/api/voice/assistant")
@app.post("/api/assistant")
@limiter.limit("30/minute")
def ai_assistant_endpoint(request: Request, body: AskRequest):
    prompt = (body.message or body.text or body.prompt or "").strip()
    if not prompt:
        return JSONResponse(
            status_code=400,
            content={"success": False, "error": "Question cannot be empty."}
        )

    # Determine requested target language
    detected_prompt_lang = detect_language_from_text(prompt) or "en"
    req_target = body.target_language or body.language

    if not req_target or req_target.lower() == "auto":
        target_lang = detected_prompt_lang
    elif detected_prompt_lang != "en" and req_target.lower() == "en":
        # Question was asked in non-English (e.g. Telugu), match question language!
        target_lang = detected_prompt_lang
    else:
        target_lang = normalize_language_code(req_target)

    req_source = body.source_language or "auto"

    fb_details = get_intelligent_fallback_details(prompt, language=target_lang, source_language=req_source)

    def search_fallback_fn(user_text: str, lang_code: str) -> Optional[str]:
        eff_target = lang_code if (lang_code and lang_code != "auto") else target_lang
        details = get_intelligent_fallback_details(user_text, language=eff_target, source_language=req_source)
        return details.get("answer")

    try:
        result = ai_agent.handle_chat_request(
            message=prompt,
            session_id=body.session_id,
            language=target_lang,
            target_language=target_lang,
            source_language=req_source,
            fallback_handler=search_fallback_fn
        )
        s_url = result.get("source_url") or fb_details.get("source_url") or f"https://www.google.com/search?q={urllib.parse.quote(prompt)}"
        s_title = result.get("source_title") or fb_details.get("source_title") or f"Website: {prompt}"

        return {
            "success": True,
            "session_id": result["session_id"],
            "question": prompt,
            "answer": result["answer"],
            "model": result.get("model", config.AI_MODEL),
            "language": result.get("language_code", target_lang),
            "target_language": target_lang,
            "source_url": s_url,
            "source_title": s_title,
            "website_url": s_url
        }
    except ValueError as ve:
        return JSONResponse(
            status_code=400,
            content={"success": False, "error": str(ve)}
        )
    except Exception as e:
        logger.error(f"Error in ai_assistant_endpoint: {e}", exc_info=True)
        fallback_ans = fb_details.get("answer") or "Butterfly AI was unable to process your request."
        s_id = body.session_id or f"session_{uuid.uuid4().hex[:8]}"
        s_url = fb_details.get("source_url") or f"https://www.google.com/search?q={urllib.parse.quote(prompt)}"
        s_title = fb_details.get("source_title") or f"Website: {prompt}"
        return {
            "success": True,
            "session_id": s_id,
            "question": prompt,
            "answer": fallback_ans,
            "model": "butterfly-ai-assistant",
            "language": target_lang,
            "target_language": target_lang,
            "source_url": s_url,
            "source_title": s_title,
            "website_url": s_url
        }



@app.post("/api/voice/polish")
@app.post("/api/polish")
@limiter.limit("30/minute")
def ai_polish_endpoint(request: Request, body: PolishRequest):
    if not body.text or not body.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")
        
    original = body.text.strip()
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
@limiter.limit("20/minute")
async def audio_upload_endpoint(
    request: Request,
    file: UploadFile = File(...),
    source_language: Optional[str] = Form("auto"),
    translation_language: Optional[str] = Form("en")
):
    if not file or not file.filename:
        raise HTTPException(status_code=400, detail="No audio file uploaded")
        
    raw_ext = Path(file.filename).suffix.lower()
    if raw_ext not in config.ALLOWED_AUDIO_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported audio format '{raw_ext}'. Allowed formats: {', '.join(sorted(config.ALLOWED_AUDIO_EXTENSIONS))}"
        )

    logger.info(f"Audio upload endpoint received file: {file.filename}")
    temp_filename = f"upload_{uuid.uuid4().hex[:10]}_{file.filename}"
    temp_path = os.path.join(config.AUDIO_INPUT_DIR, temp_filename)
    
    try:
        total_size = 0
        chunk_size = 1024 * 1024
        with open(temp_path, "wb") as buffer:
            while True:
                chunk = await file.read(chunk_size)
                if not chunk:
                    break
                total_size += len(chunk)
                if total_size > config.MAX_AUDIO_FILE_SIZE:
                    raise HTTPException(
                        status_code=413,
                        detail=f"Audio file exceeds maximum allowed size of {config.MAX_AUDIO_FILE_SIZE // (1024 * 1024)}MB"
                    )
                buffer.write(chunk)
                
        file_size = total_size
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
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Audio upload endpoint error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception as e:
                logger.warning(f"Could not remove temporary upload audio file {temp_path}: {e}")
        try:
            tts_service.cleanup_output_cache()
        except Exception:
            pass

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

class RenameSessionRequest(BaseModel):
    title: str

@app.patch("/conversation/{session_id}")
@app.put("/conversation/{session_id}")
@app.patch("/api/conversation/{session_id}")
@app.put("/api/conversation/{session_id}")
def rename_conversation_session(session_id: str, body: RenameSessionRequest):
    new_title = body.title.strip() if body.title else ""
    if not new_title:
        raise HTTPException(status_code=400, detail="Title cannot be empty")
    updated = memory_manager.update_session_title(session_id, new_title)
    if not updated:
        raise HTTPException(status_code=404, detail="Session not found or title not updated")
    return {"success": True, "session_id": session_id, "title": new_title}

@app.get("/conversation/{session_id}/export")
@app.get("/api/conversation/{session_id}/export")
def export_conversation_session(session_id: str, format: Optional[str] = Query("txt")):
    messages = memory_manager.get_session_messages(session_id)
    if not messages:
        raise HTTPException(status_code=404, detail="Session not found or contains no messages")
    
    fmt = (format or "txt").lower().strip()
    if fmt == "json":
        return JSONResponse(
            content={"success": True, "session_id": session_id, "messages": messages},
            headers={"Content-Disposition": f'attachment; filename="conversation_{session_id}.json"'}
        )
    elif fmt in ("md", "markdown"):
        md_lines = [f"# Butterfly AI Conversation - {session_id}\n"]
        for m in messages:
            role_label = "**User**" if m.get("role") == "user" else "**Butterfly AI**"
            ts = m.get("timestamp", "")
            md_lines.append(f"### {role_label} ({ts})\n")
            md_lines.append(f"{m.get('content', '')}\n")
            if m.get("translated_text") and m.get("translated_text") != m.get("content"):
                md_lines.append(f"> *Translation ({m.get('translation_language', 'en')}):* {m.get('translated_text')}\n")
            md_lines.append("---\n")
        return PlainTextResponse(
            content="\n".join(md_lines),
            media_type="text/markdown",
            headers={"Content-Disposition": f'attachment; filename="conversation_{session_id}.md"'}
        )
    else:
        txt_lines = [f"=== Butterfly AI Conversation: {session_id} ===\n"]
        for m in messages:
            role_label = "USER" if m.get("role") == "user" else "BUTTERFLY AI"
            ts = m.get("timestamp", "")
            txt_lines.append(f"[{ts}] {role_label}:")
            txt_lines.append(f"{m.get('content', '')}")
            if m.get("translated_text") and m.get("translated_text") != m.get("content"):
                txt_lines.append(f"TRANSLATION: {m.get('translated_text')}")
            txt_lines.append("-" * 40)
        return PlainTextResponse(
            content="\n".join(txt_lines),
            media_type="text/plain",
            headers={"Content-Disposition": f'attachment; filename="conversation_{session_id}.txt"'}
        )

@app.get("/api/search")
@app.get("/search")
@limiter.limit("20/minute")
def search_endpoint(request: Request, q: str = Query("", alias="q")):
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

@app.get("/downloads/butterfly-ai-keyboard.apk")
def download_apk_endpoint(source: Optional[str] = Query(None)):
    github_cdn_url = "https://github.com/Giribabupeddanagolla/Butterfly-Ai-Voice-KeyBoard/releases/latest/download/butterfly-ai-keyboard.apk"
    apk_file = Path(__file__).resolve().parent.parent / "frontend" / "downloads" / "butterfly-ai-keyboard.apk"

    # Default to direct GitHub CDN redirect to prevent mobile browser download truncation and Render timeout issues
    if source != "local":
        return RedirectResponse(url=github_cdn_url, status_code=302)

    if apk_file.exists():
        return FileResponse(
            path=str(apk_file),
            filename="butterfly-ai-keyboard-v1.0.apk",
            media_type="application/vnd.android.package-archive",
            headers={
                "Cache-Control": "no-cache",
                "Content-Disposition": 'attachment; filename="butterfly-ai-keyboard-v1.0.apk"'
            }
        )
    return RedirectResponse(url=github_cdn_url, status_code=302)

@app.get("/downloads/butterfly-ai-keyboard-v1.0.apk")
def download_apk_v1_endpoint(source: Optional[str] = Query(None)):
    return download_apk_endpoint(source=source)

# Mount Frontend directory at root `/`
FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
if FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")

if __name__ == "__main__":
    import uvicorn
    import webbrowser
    import threading
    from config import get_free_port
    
    target_port = get_free_port(config.HOST, config.PORT)
    display_host = "localhost" if config.HOST in ("0.0.0.0", "::") else config.HOST
    print(f"\n[+] Starting Multilingual AI Agent server on http://{display_host}:{target_port}")
    print(f"[+] Local URL: http://localhost:{target_port}")
    print(f"[+] Network bind: {config.HOST}:{target_port}\n")

    # Automatically open the web browser
    threading.Timer(1.5, lambda: webbrowser.open(f"http://localhost:{target_port}")).start()

    uvicorn.run("app:app", host=config.HOST, port=target_port, reload=True, reload_excludes=["*.webm", "*.wav", "*.mp3", "*.ogg", "*.db", "audio/*", "temp_uploads/*", "audio/input/*", "audio/output/*"])
