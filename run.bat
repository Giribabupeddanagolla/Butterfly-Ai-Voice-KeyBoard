@echo off
cd /d "%~dp0backend"
if exist ".venv\Scripts\python.exe" (
    echo Starting Butterfly AI Voice Keyboard Backend with virtual environment...
    ".venv\Scripts\python.exe" app.py
) else (
    echo Starting Butterfly AI Voice Keyboard Backend with system python...
    python app.py
)
pause
