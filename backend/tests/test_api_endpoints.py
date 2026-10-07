import os
import sys
import tempfile
import io
import pytest
from unittest.mock import patch, MagicMock

BACKEND_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from fastapi.testclient import TestClient
from config import config
from memory import MemoryManager
import memory
from app import app
from services.openai_service import openai_service

@pytest.fixture(autouse=True)
def isolated_db(monkeypatch):
    """Provide a fresh isolated SQLite database for each test."""
    temp_dir = tempfile.mkdtemp()
    test_db_path = os.path.join(temp_dir, "test_api_db.db")
    test_memory = MemoryManager(db_path=test_db_path)
    monkeypatch.setattr(memory, "memory_manager", test_memory)
    monkeypatch.setattr("app.memory_manager", test_memory)
    monkeypatch.setattr("ai_agent.memory_manager", test_memory)
    
    yield test_memory
    
    try:
        if os.path.exists(test_db_path):
            os.remove(test_db_path)
        os.rmdir(temp_dir)
    except Exception:
        pass

@pytest.fixture
def client():
    return TestClient(app)

# 1. Health & Status Endpoints
def test_health_check(client):
    res = client.get("/health")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "ok"
    assert "openai_configured" in data

def test_api_health(client):
    res = client.get("/api/health")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"

def test_openai_status(client):
    res = client.get("/api/openai/status")
    assert res.status_code == 200
    data = res.json()
    assert "configured" in data
    assert data["configured"] is False

def test_is_openai_active_placeholder_keys(monkeypatch):
    from config import is_openai_active
    monkeypatch.setattr(config, "OPENAI_FAILED", False)

    # Empty key
    monkeypatch.setattr(config, "OPENAI_API_KEY", "")
    assert is_openai_active() is False

    # Dummy placeholder keys
    monkeypatch.setattr(config, "OPENAI_API_KEY", "sk-validkey1234567890123")
    assert is_openai_active() is False

    monkeypatch.setattr(config, "OPENAI_API_KEY", "sk-placeholder-test")
    assert is_openai_active() is False

    # Valid-format key
    monkeypatch.setattr(config, "OPENAI_API_KEY", "sk-proj-RealActualApiKeyFormat1234567890")
    assert is_openai_active() is True

    # Failed flag disables it
    monkeypatch.setattr(config, "OPENAI_FAILED", True)
    assert is_openai_active() is False

# 2. Translation Endpoints & Fallback
def test_text_translate_same_language(client):
    res = client.post("/api/text-translate", json={
        "text": "Hello world",
        "source_language": "en",
        "target_language": "en"
    })
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "Hello world" in (data.get("translation") or data.get("translated_text", ""))

def test_text_translate_empty_error(client):
    res = client.post("/api/text-translate", json={"text": "   "})
    assert res.status_code == 400

def test_voice_translate_endpoint_fallback(client):
    with patch.object(openai_service, "is_configured", return_value=False):
        res = client.post("/api/translate", json={
            "text": "Good morning",
            "source_language": "en",
            "target_language": "es"
        })
        assert res.status_code == 200
        data = res.json()
        assert data["success"] is True
        assert "translated_text" in data

# 3. Speech-to-Text Endpoint & Fallback
def test_transcribe_fallback_text(client):
    res = client.post("/api/transcribe", data={
        "fallback_text": "Live speech fallback test",
        "source_language": "en",
        "translation_language": "en",
        "is_translate_on": "false"
    })
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "Live speech fallback test" in data["text"]

def test_transcribe_with_audio_upload(client):
    # Dummy mock audio file
    fake_audio = io.BytesIO(b"RIFF\x24\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00D\xac\x00\x00\x88X\x01\x00\x02\x00\x10\x00data\x00\x00\x00\x00")
    fake_audio.name = "test.wav"
    
    with patch("services.whisper_service.whisper_service.transcribe_audio", return_value={"success": True, "text": "Mock audio transcription", "language": "en"}):
        res = client.post("/api/transcribe", files={"audio": ("test.wav", fake_audio, "audio/wav")}, data={"is_translate_on": "false"})
        assert res.status_code == 200
        data = res.json()
        assert data["success"] is True
        assert data["text"] == "Mock audio transcription"

def test_transcribe_audio_file_size_exceeded(client, monkeypatch):
    monkeypatch.setattr(config, "MAX_AUDIO_FILE_SIZE", 50)
    fake_audio = io.BytesIO(b"RIFF\x24\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00D\xac\x00\x00\x88X\x01\x00\x02\x00\x10\x00data\x00\x00\x00\x00" * 5)
    fake_audio.name = "test.wav"
    res = client.post("/api/transcribe", files={"audio": ("test.wav", fake_audio, "audio/wav")}, data={"is_translate_on": "false"})
    assert res.status_code == 413
    assert "exceeds maximum allowed size" in res.json()["detail"]

def test_voice_upload_audio_file_size_exceeded(client, monkeypatch):
    monkeypatch.setattr(config, "MAX_AUDIO_FILE_SIZE", 50)
    fake_audio = io.BytesIO(b"RIFF\x24\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00D\xac\x00\x00\x88X\x01\x00\x02\x00\x10\x00data\x00\x00\x00\x00" * 5)
    fake_audio.name = "test.wav"
    res = client.post("/api/voice/upload", files={"file": ("test.wav", fake_audio, "audio/wav")})
    assert res.status_code == 413
    assert "exceeds maximum allowed size" in res.json()["detail"]

def test_transcribe_with_3gp_audio_upload(client):
    fake_audio = io.BytesIO(b"fake 3gp audio payload")
    fake_audio.name = "recording.3gp"
    with patch("services.whisper_service.whisper_service.transcribe_audio", return_value={"success": True, "text": "3gp audio transcription", "language": "en"}):
        res = client.post("/api/transcribe", files={"audio": ("recording.3gp", fake_audio, "audio/3gpp")}, data={"is_translate_on": "false"})
        assert res.status_code == 200
        data = res.json()
        assert data["success"] is True
        assert data["text"] == "3gp audio transcription"

# 4. Text-to-Speech Endpoint
def test_tts_endpoint_empty_text(client):
    res = client.post("/api/voice/speak", json={"text": ""})
    assert res.status_code == 400

def test_tts_endpoint_success(client):
    with patch("text_to_speech.tts_service.generate_speech", return_value={"success": True, "audio_url": "/audio/output/test.mp3", "provider": "gtts"}):
        res = client.post("/api/voice/speak", json={"text": "Hello from unit test", "language": "en"})
        assert res.status_code == 200
        assert res.json()["success"] is True
        assert res.json()["audio_url"] == "/audio/output/test.mp3"

# 5. AI Assistant & Polish Endpoints
def test_ai_assistant_empty_error(client):
    res = client.post("/api/ai/chat", json={"message": ""})
    assert res.status_code == 400

def test_ai_assistant_fallback_response(client):
    res = client.post("/api/ai/chat", json={"message": "What is Python?", "language": "en"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert len(data["answer"]) > 0

def test_ai_polish_empty_error(client):
    res = client.post("/api/polish", json={"text": ""})
    assert res.status_code == 400

def test_ai_polish_fallback(client):
    with patch.object(openai_service, "is_configured", return_value=False):
        res = client.post("/api/polish", json={"text": "um hello like world actually"})
        assert res.status_code == 200
        data = res.json()
        assert data["success"] is True
        assert "um" not in data["polished_text"].lower()

# 6. Web Search Endpoint
def test_search_empty(client):
    res = client.get("/api/search?q=")
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["results"] == []

def test_search_query(client):
    res = client.get("/api/search?q=FastAPI")
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert len(data["results"]) > 0

# 7. History / Conversations Endpoints
def test_conversations_endpoint(client):
    res = client.get("/conversations")
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert isinstance(data["sessions"], list)

# 9. Settings API Key Admin Authorization & Rate Limiting
def test_settings_key_security(client):
    # Test unauthorized when ADMIN_SECRET_KEY configured
    config.ADMIN_SECRET_KEY = "test_admin_secret_999"
    config.ENVIRONMENT = "production"

    # Missing token
    res = client.post("/api/settings/key", json={"api_key": "sk-test1234567890123456"})
    assert res.status_code == 401

    # Wrong token
    res = client.post("/api/settings/key", json={"api_key": "sk-test1234567890123456", "admin_token": "wrong"})
    assert res.status_code == 401

    # Valid token but invalid key prefix
    res = client.post("/api/settings/key", json={"api_key": "invalid_prefix", "admin_token": "test_admin_secret_999"})
    assert res.status_code == 400

    # Valid token and valid format (mock file write so .env is not mutated on disk)
    with patch("pathlib.Path.write_text"):
        res = client.post("/api/settings/key", json={"api_key": "sk-validkey1234567890123", "admin_token": "test_admin_secret_999"})
        assert res.status_code == 200
        assert res.json()["success"] is True

    # Reset config
    config.ENVIRONMENT = "development"
    config.ADMIN_SECRET_KEY = ""
    config.OPENAI_API_KEY = ""
    os.environ["OPENAI_API_KEY"] = ""

# 10. Conversation Rename & Export Endpoints
def test_conversation_rename(client, isolated_db):
    session_id = "session_test_rename_123"
    isolated_db.save_message(
        session_id=session_id,
        role="user",
        content="Test content for rename",
        language="en"
    )

    # 1. Rename via PATCH
    res = client.patch(f"/conversation/{session_id}", json={"title": "Updated Custom Title"})
    assert res.status_code == 200
    assert res.json()["title"] == "Updated Custom Title"

    # 2. Check updated in conversation list
    res = client.get("/conversations")
    assert res.status_code == 200
    sessions = res.json()["sessions"]
    matching = [s for s in sessions if s["session_id"] == session_id]
    assert len(matching) == 1
    assert matching[0]["title"] == "Updated Custom Title"

    # 3. Rename via PUT
    res = client.put(f"/conversation/{session_id}", json={"title": "New PUT Title"})
    assert res.status_code == 200
    assert res.json()["title"] == "New PUT Title"

    # 4. Error on empty title
    res = client.patch(f"/conversation/{session_id}", json={"title": "   "})
    assert res.status_code == 400

    # 5. Error on non-existent session
    res = client.patch("/conversation/non_existent_session_999", json={"title": "Should Fail"})
    assert res.status_code == 404

def test_conversation_export(client, isolated_db):
    session_id = "session_test_export_456"
    isolated_db.save_message(
        session_id=session_id,
        role="user",
        content="Hello Butterfly",
        language="en",
        translated_text="నమస్కారం బటర్‌ఫ్లై",
        translation_language="te"
    )

    # 1. Plain text export
    res = client.get(f"/conversation/{session_id}/export?format=txt")
    assert res.status_code == 200
    assert "text/plain" in res.headers["content-type"]
    assert "Hello Butterfly" in res.text
    assert "నమస్కారం బటర్‌ఫ్లై" in res.text

    # 2. JSON export
    res = client.get(f"/conversation/{session_id}/export?format=json")
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["session_id"] == session_id
    assert len(data["messages"]) == 1

    # 3. Markdown export
    res = client.get(f"/conversation/{session_id}/export?format=md")
    assert res.status_code == 200
    assert "text/markdown" in res.headers["content-type"]
    assert "# Butterfly AI Conversation" in res.text

    # 4. Non-existent session
    res = client.get("/conversation/non_existent_session_999/export")
    assert res.status_code == 404

def test_audio_format_validation(client):
    # Uploading a non-audio file (e.g. .txt or .exe) must be rejected
    invalid_file = io.BytesIO(b"not audio content")
    res = client.post(
        "/api/transcribe",
        files={"audio": ("test.txt", invalid_file, "text/plain")}
    )
    assert res.status_code == 400
    assert "Unsupported audio format" in res.json()["detail"]

    invalid_file_upload = io.BytesIO(b"binary data")
    res = client.post(
        "/api/voice/upload",
        files={"file": ("malicious.exe", invalid_file_upload, "application/octet-stream")}
    )
    assert res.status_code == 400
    assert "Unsupported audio format" in res.json()["detail"]
