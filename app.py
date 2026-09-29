"""
Butterfly AI Voice Keyboard - Root Launcher
Allows running `python app.py` directly from the repository root directory.
"""
import os
import sys
from pathlib import Path

# Add directories to sys.path
ROOT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = ROOT_DIR / "backend"

if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

# Expose app for uvicorn app:app or uvicorn backend.app:app
try:
    from backend.app import app
except ImportError:
    from app import app

if __name__ == "__main__":
    import uvicorn
    import webbrowser
    import threading
    from config import config, get_free_port

    target_port = get_free_port(config.HOST, config.PORT)
    display_host = "localhost" if config.HOST in ("0.0.0.0", "::") else config.HOST
    print(f"\n[+] Starting Butterfly AI Voice Keyboard from {BACKEND_DIR}...")
    print(f"[+] Local URL: http://localhost:{target_port}/")
    print(f"[+] Serving on http://{display_host}:{target_port}/ (bound to {config.HOST}:{target_port})\n")

    # Automatically open the web browser
    threading.Timer(1.5, lambda: webbrowser.open(f"http://localhost:{target_port}/")).start()

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
