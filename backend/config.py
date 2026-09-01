import os
from pathlib import Path
from dotenv import load_dotenv

# Base Directory: root of the project
BASE_DIR = Path(__file__).resolve().parent.parent

# Load .env file from backend directory or root directory
load_dotenv(Path(__file__).resolve().parent / ".env")
load_dotenv(BASE_DIR / ".env")

class Config:
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
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
    
    # App host & port
    HOST: str = os.getenv("HOST", "127.0.0.1")
    PORT: int = int(os.getenv("PORT", "8000"))

def is_port_available(host: str, port: int) -> bool:
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
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
