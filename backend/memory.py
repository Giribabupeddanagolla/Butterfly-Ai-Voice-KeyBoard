import sqlite3
import datetime
from pathlib import Path
from typing import List, Dict, Any, Optional
from config import config

class MemoryManager:
    def __init__(self, db_path: str = None):
        self.db_path = db_path or config.DATABASE_PATH
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self.init_db()

    def get_connection(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def init_db(self):
        with self.get_connection() as conn:
            cursor = conn.cursor()
            # Sessions table
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            
            # Messages table
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    language TEXT DEFAULT 'en',
                    original_text TEXT,
                    source_language TEXT,
                    translation_language TEXT,
                    text_language TEXT,
                    translated_text TEXT,
                    input_type TEXT DEFAULT 'text',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (session_id) REFERENCES sessions (session_id) ON DELETE CASCADE
                )
            """)
            
            # Migration check for existing databases
            cursor.execute("PRAGMA table_info(messages)")
            columns = [column[1] for column in cursor.fetchall()]
            extra_cols = [
                ("original_text", "TEXT"),
                ("source_language", "TEXT"),
                ("translation_language", "TEXT"),
                ("text_language", "TEXT"),
                ("translated_text", "TEXT"),
                ("input_type", "TEXT DEFAULT 'text'")
            ]
            for col_name, col_type in extra_cols:
                if col_name not in columns:
                    try:
                        cursor.execute(f"ALTER TABLE messages ADD COLUMN {col_name} {col_type}")
                    except Exception as e:
                        pass

            # Index for performance
            cursor.execute("""
                CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages (session_id)
            """)
            conn.commit()

    def get_or_create_session(self, session_id: str, title: Optional[str] = None) -> Dict[str, Any]:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM sessions WHERE session_id = ?", (session_id,))
            session = cursor.fetchone()
            
            if session:
                return dict(session)
            
            # Create new session
            default_title = title or f"Conversation {datetime.datetime.now().strftime('%b %d, %H:%M')}"
            now = datetime.datetime.now().isoformat()
            cursor.execute(
                "INSERT INTO sessions (session_id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                (session_id, default_title, now, now)
            )
            conn.commit()
            return {"session_id": session_id, "title": default_title, "created_at": now, "updated_at": now}

    def save_message(
        self,
        session_id: str,
        role: str,
        content: str,
        language: str = "en",
        original_text: Optional[str] = None,
        source_language: Optional[str] = None,
        translation_language: Optional[str] = None,
        text_language: Optional[str] = None,
        translated_text: Optional[str] = None,
        input_type: str = "text"
    ) -> Dict[str, Any]:
        self.get_or_create_session(session_id)
        now = datetime.datetime.now().isoformat()
        
        orig = original_text if original_text is not None else content
        src_lang = source_language or language or "auto"
        trans_lang = translation_language or language or "en"
        txt_lang = text_language or trans_lang
        trans_txt = translated_text if translated_text is not None else content

        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                INSERT INTO messages (
                    session_id, role, content, language,
                    original_text, source_language, translation_language, text_language, translated_text, input_type,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (session_id, role, content, txt_lang, orig, src_lang, trans_lang, txt_lang, trans_txt, input_type, now)
            )
            msg_id = cursor.lastrowid
            
            # Auto update session title if user message and first message
            cursor.execute("SELECT COUNT(*) FROM messages WHERE session_id = ?", (session_id,))
            msg_count = cursor.fetchone()[0]
            if msg_count <= 2 and role == "user":
                short_title = content[:30] + ("..." if len(content) > 30 else "")
                cursor.execute(
                    "UPDATE sessions SET title = ?, updated_at = ? WHERE session_id = ?",
                    (short_title, now, session_id)
                )
            else:
                cursor.execute(
                    "UPDATE sessions SET updated_at = ? WHERE session_id = ?",
                    (now, session_id)
                )
            conn.commit()
            
            return {
                "id": msg_id,
                "session_id": session_id,
                "role": role,
                "content": content,
                "language": txt_lang,
                "original_text": orig,
                "source_language": src_lang,
                "translation_language": trans_lang,
                "text_language": txt_lang,
                "translated_text": trans_txt,
                "input_type": input_type,
                "created_at": now
            }

    def get_session_messages(self, session_id: str, limit: int = 100) -> List[Dict[str, Any]]:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT id, session_id, role, content, language,
                       original_text, source_language, translation_language, text_language, translated_text, input_type,
                       created_at
                FROM messages WHERE session_id = ? ORDER BY id ASC LIMIT ?
                """,
                (session_id, limit)
            )
            rows = cursor.fetchall()
            return [dict(row) for row in rows]

    def get_all_sessions(self) -> List[Dict[str, Any]]:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT s.session_id, s.title, s.created_at, s.updated_at,
                       (SELECT content FROM messages WHERE session_id = s.session_id ORDER BY id DESC LIMIT 1) as last_message
                FROM sessions s
                ORDER BY s.updated_at DESC
            """)
            rows = cursor.fetchall()
            return [dict(row) for row in rows]

    def delete_session(self, session_id: str) -> bool:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM messages WHERE session_id = ?", (session_id,))
            cursor.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
            conn.commit()
            return cursor.rowcount > 0

    def update_session_title(self, session_id: str, title: str) -> bool:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            now = datetime.datetime.now().isoformat()
            cursor.execute(
                "UPDATE sessions SET title = ?, updated_at = ? WHERE session_id = ?",
                (title, now, session_id)
            )
            conn.commit()
            return cursor.rowcount > 0

    # --- VOICE RECORD CRUD OPERATIONS ---
    def create_record(self, text: str, source_language: str = "auto", target_language: str = "en", translated_text: Optional[str] = None) -> Dict[str, Any]:
        session_id = f"session_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
        return self.save_message(
            session_id=session_id,
            role="user",
            content=text,
            language=target_language,
            original_text=text,
            source_language=source_language,
            translation_language=target_language,
            translated_text=translated_text or text,
            input_type="voice"
        )

    def get_all_records(self, limit: int = 100) -> List[Dict[str, Any]]:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT id, session_id, role, content, language,
                       original_text, source_language, translation_language, text_language, translated_text, input_type,
                       created_at
                FROM messages ORDER BY id DESC LIMIT ?
                """,
                (limit,)
            )
            return [dict(row) for row in cursor.fetchall()]

    def get_record_by_id(self, record_id: int) -> Optional[Dict[str, Any]]:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT id, session_id, role, content, language,
                       original_text, source_language, translation_language, text_language, translated_text, input_type,
                       created_at
                FROM messages WHERE id = ?
                """,
                (record_id,)
            )
            row = cursor.fetchone()
            return dict(row) if row else None

    def update_record(self, record_id: int, text: str, translated_text: Optional[str] = None) -> bool:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            trans = translated_text if translated_text is not None else text
            cursor.execute(
                "UPDATE messages SET content = ?, original_text = ?, translated_text = ? WHERE id = ?",
                (text, text, trans, record_id)
            )
            conn.commit()
            return cursor.rowcount > 0

    def delete_record_by_id(self, record_id: int) -> bool:
        with self.get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM messages WHERE id = ?", (record_id,))
            conn.commit()
            return cursor.rowcount > 0

memory_manager = MemoryManager()
