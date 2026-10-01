import os
import sys
import tempfile
import sqlite3
import pytest

BACKEND_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from speech_to_text import detect_language_from_text, translate_text, stt_service
from text_to_speech import TextToSpeechService, tts_service
from memory import MemoryManager

def test_language_detection():
    # Telugu detection
    telugu_text = "నమస్కారం ఎలా ఉన్నారు"
    assert detect_language_from_text(telugu_text) == "te"

    # Hindi detection
    hindi_text = "नमस्ते आप कैसे हैं"
    assert detect_language_from_text(hindi_text) == "hi"

    # English detection
    english_text = "Hello how are you today"
    assert detect_language_from_text(english_text) == "en"

def test_offline_translation_fallback():
    # Test identical source and target
    res = translate_text("Hello", target_language="en", source_language="en")
    assert res == "Hello"

    # Test dictionary / deep-translator fallback
    res_es = translate_text("hello", target_language="es", source_language="en")
    assert isinstance(res_es, str)
    assert len(res_es) > 0

def test_tts_service_generation():
    service = TextToSpeechService()
    # Test short speech generation via fallback (edge-tts / gTTS)
    result = service.generate_speech("Hello test", language="en")
    assert result["success"] is True
    assert "audio_url" in result
    assert result["audio_url"].startswith("/audio/output/")

def test_memory_sqlite_pragmas_and_concurrency():
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "concurrency_test.db")
    
    manager = MemoryManager(db_path=db_path)
    
    # Verify foreign_keys is ON
    conn = manager.get_connection()
    cursor = conn.cursor()
    cursor.execute("PRAGMA foreign_keys;")
    fk_status = cursor.fetchone()[0]
    assert fk_status == 1, "PRAGMA foreign_keys should be 1 (enabled)"
    conn.close()

    # Verify session and message saving
    manager.save_message("session_test_01", "user", "Hello SQLite", source_language="en", translation_language="es")
    manager.save_message("session_test_01", "assistant", "Hola SQLite", source_language="en", translation_language="es")

    messages = manager.get_session_messages("session_test_01")
    assert len(messages) == 2
    assert messages[0]["content"] == "Hello SQLite"
    assert messages[1]["content"] == "Hola SQLite"

    # Clean up
    try:
        os.remove(db_path)
        os.rmdir(temp_dir)
    except Exception:
        pass
