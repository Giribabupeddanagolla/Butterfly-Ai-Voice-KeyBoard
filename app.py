"""
Butterfly AI Voice Keyboard - Root Launcher
Allows running `python app.py` directly from the repository root directory.
"""
import os
import sys
from pathlib import Path

# Add backend directory to sys.path and switch working directory
ROOT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = ROOT_DIR / "backend"

os.chdir(str(BACKEND_DIR))
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

if __name__ == "__main__":
    import uvicorn
    from config import config, get_free_port

    target_port = get_free_port(config.HOST, config.PORT)
    print(f"\n[+] Starting Butterfly AI Voice Keyboard from {BACKEND_DIR}...")
    print(f"[+] Serving on http://{config.HOST}:{target_port}/\n")
    uvicorn.run(
        "app:app",
        host=config.HOST,
        port=target_port,
        reload=True,
        reload_dirs=[str(BACKEND_DIR)],
        reload_excludes=[
            "*.webm", "*.wav", "*.mp3", "*.ogg", "*.db",
            "audio/*", "temp_uploads/*", "audio/input/*", "audio/output/*"
        ]
    )
