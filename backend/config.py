import os
from pathlib import Path
from dotenv import load_dotenv

# Base Directory: root of the project
BASE_DIR = Path(__file__).resolve().parent.parent

# Load .env file from backend directory or root directory
load_dotenv(Path(__file__).resolve().parent / ".env")
load_dotenv(BASE_DIR / ".env")

class Config:
    @property
    def OPENAI_API_KEY(self) -> str:
        return getattr(self, "_openai_api_key", None) or os.getenv("OPENAI_API_KEY", "") or os.getenv("OPENAI_API_KEY_NEW_KEY", "")

    @OPENAI_API_KEY.setter
    def OPENAI_API_KEY(self, value: str):
        self._openai_api_key = value

    AI_MODEL: str = os.getenv("AI_MODEL", "gpt-4o-mini")
    STT_MODEL: str = os.getenv("OPENAI_TRANSCRIPTION_MODEL", os.getenv("STT_MODEL", "whisper-1"))
    OPENAI_TRANSCRIPTION_MODEL: str = os.getenv("OPENAI_TRANSCRIPTION_MODEL", STT_MODEL)
    OPENAI_TRANSLATION_MODEL: str = os.getenv("OPENAI_TRANSLATION_MODEL", os.getenv("AI_MODEL", "gpt-4o-mini"))
    TTS_MODEL: str = os.getenv("TTS_MODEL", "tts-1")
    
    # Path to SQLite Database
    DATABASE_PATH: str = os.getenv(
        "DATABASE_PATH", str(BASE_DIR / "data" / "conversations.db")
    )
    
    # Audio Storage Paths
    AUDIO_INPUT_DIR: str = str(Path(__file__).resolve().parent / "audio" / "input")
    AUDIO_OUTPUT_DIR: str = str(Path(__file__).resolve().parent / "audio" / "output")
    
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "8000"))
    OPENAI_FAILED: bool = False

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", os.getenv("ENV", "development")).lower()
    ADMIN_SECRET_KEY: str = os.getenv("ADMIN_SECRET_KEY", os.getenv("ADMIN_API_TOKEN", ""))
    
    # Audio Upload Limits & Safety
    MAX_AUDIO_FILE_SIZE: int = int(os.getenv("MAX_AUDIO_FILE_SIZE", 25 * 1024 * 1024)) # 25MB
    ALLOWED_AUDIO_EXTENSIONS: set = {".webm", ".wav", ".mp3", ".m4a", ".ogg", ".aac", ".flac", ".mp4"}

    def get_allowed_origins(self) -> list:
        raw = os.getenv("ALLOWED_ORIGINS", "")
        if raw.strip():
            return [o.strip() for o in raw.split(",") if o.strip()]
        if self.ENVIRONMENT == "production":
            render_url = os.getenv("RENDER_EXTERNAL_URL", "")
            origins = []
            if render_url:
                origins.append(render_url.rstrip("/"))
            origins.append("https://butterfly-ai-voice-keyboard.onrender.com")
            return origins
        else:
            return [
                "http://localhost",
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:8000",
                "http://127.0.0.1",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:8000",
                "capacitor://localhost",
                "ionic://localhost"
            ]

    def __init__(self):
        if self.HOST in ["127.0.0.1", "localhost"]:
            self.HOST = "0.0.0.0"

def is_openai_active() -> bool:
    if getattr(config, "OPENAI_FAILED", False):
        return False
    key = config.OPENAI_API_KEY
    return bool(key and key.strip().startswith("sk-"))

def mark_openai_failed(reason: str = ""):
    config.OPENAI_FAILED = True

def is_port_available(host: str, port: int) -> bool:
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((host, port))
            return True
        except OSError:
            return False

def get_free_port(host: str, preferred_port: int = 8000) -> int:
    if is_port_available(host, preferred_port):
        return preferred_port
    for p in range(preferred_port + 1, preferred_port + 50):
        if is_port_available(host, p):
            return p
    return preferred_port

config = Config()

# Ensure directories exist
os.makedirs(Path(config.DATABASE_PATH).parent, exist_ok=True)
os.makedirs(config.AUDIO_INPUT_DIR, exist_ok=True)
os.makedirs(config.AUDIO_OUTPUT_DIR, exist_ok=True)
