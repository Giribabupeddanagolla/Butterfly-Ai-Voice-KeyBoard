Set-Location $PSScriptRoot
if (Test-Path ".\.venv\Scripts\python.exe") {
    Write-Host "Starting Butterfly AI Voice Keyboard Backend with virtual environment..." -ForegroundColor Cyan
    & ".\.venv\Scripts\python.exe" app.py
} else {
    Write-Host "Starting Butterfly AI Voice Keyboard Backend with system python..." -ForegroundColor Cyan
    python app.py
}
