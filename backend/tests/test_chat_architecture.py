import os
import sys
import tempfile
import pytest
from unittest.mock import MagicMock, patch

# Ensure backend is on sys.path
BACKEND_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from fastapi.testclient import TestClient
from config import config
from memory import MemoryManager
import memory
from conversation_context import PROJECT_CONTEXT, get_project_context
from app import app
from ai_agent import ai_agent, MOCK_RESPONSES, get_mock_response
from services.openai_service import openai_service

@pytest.fixture(autouse=True)
def isolated_db(monkeypatch):
    """Provide a fresh isolated SQLite database for each test."""
    temp_dir = tempfile.mkdtemp()
    test_db_path = os.path.join(temp_dir, "test_conversations.db")
    
    test_memory_manager = MemoryManager(db_path=test_db_path)
    monkeypatch.setattr(memory, "memory_manager", test_memory_manager)
    monkeypatch.setattr("ai_agent.memory_manager", test_memory_manager)
    monkeypatch.setattr("app.memory_manager", test_memory_manager)
    
    yield test_memory_manager
    
    try:
        if os.path.exists(test_db_path):
            os.remove(test_db_path)
        os.rmdir(temp_dir)
    except Exception:
        pass

@pytest.fixture
def client():
    return TestClient(app)

def test_1_chat_endpoint_exists(client):
    """Test 1 — /chat exists: POST /chat should no longer return 404."""
    response = client.post("/chat", json={"message": "ping"})
    assert response.status_code != 404

def test_2_request_contract(client):
    """Test 2 — Request contract: verify session_id, message, language returned."""
    payload = {
        "session_id": "test_contract_001",
        "message": "Hello, how can you help me?"
    }
    with patch.object(openai_service, "is_configured", return_value=True), \
         patch.object(openai_service, "generate_chat_response", return_value={"success": True, "answer": "I can help with coding and translations.", "model": "gpt-4o-mini"}):
        response = client.post("/chat", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert "session_id" in data
        assert data["session_id"] == "test_contract_001"
        assert "message" in data
        assert data["message"] == "I can help with coding and translations."
        assert "language" in data
        assert data["language"] == "English"

def test_3_session_preservation_and_history(client, isolated_db):
    """Test 3 — Session preservation: verify multi-turn history reaches AI layer."""
    session_id = "test_preserved_001"
    
    captured_messages = []
    
    def fake_generate_chat_response(**kwargs):
        messages = kwargs.get("messages", [])
        captured_messages.append(list(messages))
        if len(captured_messages) == 1:
            return {"success": True, "answer": "Here is the login page code: <form>...</form>", "model": "gpt-4o-mini"}
        else:
            return {"success": True, "answer": "Here is the updated login page with Google login added.", "model": "gpt-4o-mini"}

    with patch.object(openai_service, "is_configured", return_value=True), \
         patch.object(openai_service, "generate_chat_response", side_effect=fake_generate_chat_response):
        
        # Turn 1
        res1 = client.post("/chat", json={"session_id": session_id, "message": "Create a login page."})
        assert res1.status_code == 200
        assert res1.json()["session_id"] == session_id
        assert "login page" in res1.json()["message"]
        
        # Turn 2
        res2 = client.post("/chat", json={"session_id": session_id, "message": "Add Google login to that page."})
        assert res2.status_code == 200
        assert res2.json()["session_id"] == session_id
        assert "Google login" in res2.json()["message"]
        
        # Check turn 2 messages received by AI layer
        assert len(captured_messages) == 2
        turn2_msgs = captured_messages[1]
        
        # turn 2 must have system prompt, previous user msg, previous assistant msg, and current user msg
        contents = [m["content"] for m in turn2_msgs]
        roles = [m["role"] for m in turn2_msgs]
        
        assert roles[0] == "system"
        assert "Create a login page." in contents
        assert "Here is the login page code: <form>...</form>" in contents
        assert "Add Google login to that page." in contents

def test_4_project_context_injected(client):
    """Test 4 — PROJECT_CONTEXT: mock AI service and verify PROJECT_CONTEXT in system message."""
    captured_kwargs = {}

    def fake_generate_chat_response(**kwargs):
        captured_kwargs.update(kwargs)
        return {"success": True, "answer": "Verified context.", "model": "gpt-4o-mini"}

    with patch.object(openai_service, "is_configured", return_value=True), \
         patch.object(openai_service, "generate_chat_response", side_effect=fake_generate_chat_response):
        
        res = client.post("/chat", json={"session_id": "test_ctx_001", "message": "Test context injection"})
        assert res.status_code == 200
        
        messages = captured_kwargs.get("messages", [])
        assert len(messages) >= 2
        system_msg = messages[0]
        assert system_msg["role"] == "system"
        # Check key instruction fragments from PROJECT_CONTEXT without dumping full context
        assert "Multilingual Voice AI Agent" in system_msg["content"]
        assert "STRICT LANGUAGE PRESERVATION" in system_msg["content"]

def test_5_different_session_isolation(client, isolated_db):
    """Test 5 — Different session: verify test_002 does not receive test_001 history."""
    captured_calls = []

    def fake_generate_chat_response(**kwargs):
        captured_calls.append(kwargs.get("messages", []))
        return {"success": True, "answer": "Answer", "model": "gpt-4o-mini"}

    with patch.object(openai_service, "is_configured", return_value=True), \
         patch.object(openai_service, "generate_chat_response", side_effect=fake_generate_chat_response):
        
        # Session 1
        client.post("/chat", json={"session_id": "test_001", "message": "Secret message in session 1"})
        
        # Session 2
        client.post("/chat", json={"session_id": "test_002", "message": "Hello from session 2"})
        
        assert len(captured_calls) == 2
        session2_contents = [m["content"] for m in captured_calls[1]]
        assert "Secret message in session 1" not in session2_contents
        assert "Hello from session 2" in session2_contents

def test_6_existing_endpoint_compatibility(client):
    """Test 6 — Existing endpoint compatibility: verify /api/ask still functions with expected format."""
    # Test with prompt & language
    payload = {
        "message": "What is Python?",
        "text": "What is Python?",
        "language": "en"
    }
    response = client.post("/api/ask", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["success"] is True
    assert "answer" in data
    assert "session_id" in data
    assert "question" in data
    
    # Test backward-compatible session_id support
    custom_session = "android_session_99"
    payload_with_session = {
        "prompt": "What is AI?",
        "session_id": custom_session
    }
    response2 = client.post("/api/ask", json=payload_with_session)
    assert response2.status_code == 200
    assert response2.json()["session_id"] == custom_session

def test_7_empty_message_and_invalid_session(client):
    """Test 7 — Empty message and invalid session_id return controlled 400 validation error."""
    # Empty message
    res_empty = client.post("/chat", json={"message": "   "})
    assert res_empty.status_code == 400
    assert "empty" in res_empty.json()["detail"].lower()
    
    # Missing message
    res_missing = client.post("/chat", json={})
    assert res_missing.status_code == 422 or res_missing.status_code == 400
    
    # Whitespace session_id
    res_invalid_sess = client.post("/chat", json={"session_id": "   ", "message": "Valid text"})
    assert res_invalid_sess.status_code == 400
    assert "session_id" in res_invalid_sess.json()["detail"].lower()

def test_8_mock_responses_fallback_reachable(client):
    """Test 8 — MOCK_RESPONSES: verify multilingual fallback is reachable when OpenAI API key is missing."""
    with patch.object(openai_service, "is_configured", return_value=False):
        # English fallback
        res_en = client.post("/chat", json={"message": "How do I build an app?"})
        assert res_en.status_code == 200
        msg_en = res_en.json()["message"]
        assert "OpenAI API Key is not set" in msg_en
        assert "How do I build an app?" in msg_en
        
        # Telugu fallback
        res_te = client.post("/chat", json={"message": "నాకు login page కావాలి"})
        assert res_te.status_code == 200
        msg_te = res_te.json()["message"]
        assert "OpenAI API Key" in msg_te
        assert "నాకు login page కావాలి" in msg_te
        assert res_te.json()["language"] == "Telugu"

def test_failed_ai_request_does_not_save_assistant_message(client, isolated_db):
    """Verify that failed AI requests do not save an assistant message to the database."""
    session_id = "test_failed_call_001"
    
    with patch.object(openai_service, "is_configured", return_value=True), \
         patch.object(openai_service, "generate_chat_response", return_value={"success": False, "error": "OpenAI rate limit exceeded"}):
        
        res = client.post("/chat", json={"session_id": session_id, "message": "Trigger failure"})
        assert res.status_code == 502
        
        # Check messages saved in DB for this session
        saved_messages = isolated_db.get_session_messages(session_id)
        # Assistant response must NOT be saved
        assistant_msgs = [m for m in saved_messages if m["role"] == "assistant"]
        assert len(assistant_msgs) == 0

def test_session_id_auto_generation(client):
    """Verify missing session_id generates a reusable session_id."""
    with patch.object(openai_service, "is_configured", return_value=True), \
         patch.object(openai_service, "generate_chat_response", return_value={"success": True, "answer": "Generated reply", "model": "gpt-4o-mini"}):
        res = client.post("/chat", json={"message": "First message without session ID"})
        assert res.status_code == 200
        sess_id = res.json()["session_id"]
        assert sess_id.startswith("session_")
        
        # Reuse generated session ID
        res2 = client.post("/chat", json={"session_id": sess_id, "message": "Second message"})
        assert res2.status_code == 200
        assert res2.json()["session_id"] == sess_id

def test_openai_service_direct_injects_project_context():
    """Verify OpenAIService directly injects PROJECT_CONTEXT as system prompt."""
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_choice = MagicMock()
    mock_choice.message.content = "OpenAI response test."
    mock_response.choices = [mock_choice]
    mock_client.chat.completions.create.return_value = mock_response

    with patch.object(openai_service, "get_client", return_value=mock_client):
        # Call with prompt only
        res = openai_service.generate_chat_response(prompt="Test direct call")
        assert res["success"] is True
        
        args, kwargs = mock_client.chat.completions.create.call_args
        messages = kwargs.get("messages", [])
        assert len(messages) == 2
        assert messages[0]["role"] == "system"
        assert "Multilingual Voice AI Agent" in messages[0]["content"]
        assert messages[1]["role"] == "user"
        assert messages[1]["content"] == "Test direct call"

def test_multilingual_language_preservation_metadata(client):
    """Verify language preservation metadata for various Indian languages."""
    languages_test = [
        ("నాకు సహాయం కావాలి", "Telugu"),
        ("मुझे मदद चाहिए", "Hindi"),
        ("எனக்கு உதவி வேண்டும்", "Tamil"),
        ("ನನಗೆ ಸಹಾಯ ಬೇಕು", "Kannada"),
    ]
    with patch.object(openai_service, "is_configured", return_value=False):
        for msg, expected_lang in languages_test:
            res = client.post("/chat", json={"message": msg})
            assert res.status_code == 200
            assert res.json()["language"] == expected_lang

def test_get_project_context_helper():
    """Verify get_project_context returns non-empty stripped PROJECT_CONTEXT."""
    ctx = get_project_context()
    assert isinstance(ctx, str)
    assert len(ctx) > 50
    assert "STRICT LANGUAGE PRESERVATION" in ctx
    assert ctx == PROJECT_CONTEXT.strip()

def test_get_all_sessions_search_returns_most_recent_message(isolated_db, client):
    """Verify searching for an older message returns the most recent message as last_message."""
    sess_id = "test_search_latest_msg_sess"
    isolated_db.get_or_create_session(sess_id, title="Search Test Session")
    
    # Message 1 contains matching keyword
    isolated_db.save_message(
        session_id=sess_id,
        role="user",
        content="First query with unique_keyword_xyz",
        language="en"
    )
    # Message 2 is newer
    isolated_db.save_message(
        session_id=sess_id,
        role="assistant",
        content="Intermediate response",
        language="en"
    )
    # Message 3 is the latest message
    isolated_db.save_message(
        session_id=sess_id,
        role="user",
        content="Final and most recent message in the chat",
        language="en"
    )

    # 1. Search directly via memory manager
    search_results = isolated_db.get_all_sessions(search_query="unique_keyword_xyz")
    assert len(search_results) == 1
    session_result = search_results[0]
    assert session_result["session_id"] == sess_id
    assert session_result["last_message"] == "Final and most recent message in the chat"

    # 2. Search via API endpoint /api/voice/history?q=...
    api_resp = client.get("/api/voice/history?q=unique_keyword_xyz")
    assert api_resp.status_code == 200
    data = api_resp.json()
    assert data["success"] is True
    assert len(data["sessions"]) == 1
    assert data["sessions"][0]["last_message"] == "Final and most recent message in the chat"

def test_get_all_sessions_search_title_empty_messages(isolated_db):
    """Verify sessions matching by title without any messages are properly included."""
    sess_id = "test_empty_session_id"
    isolated_db.get_or_create_session(sess_id, title="Butterfly Special Topic")
    
    results = isolated_db.get_all_sessions(search_query="Special Topic")
    assert len(results) == 1
    assert results[0]["session_id"] == sess_id
    assert results[0]["title"] == "Butterfly Special Topic"
    assert results[0]["last_message"] is None


