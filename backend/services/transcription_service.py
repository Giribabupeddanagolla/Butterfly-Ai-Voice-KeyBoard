import os
import logging
from typing import Dict, Any, Optional
from services.whisper_service import whisper_service

logger = logging.getLogger(__name__)

class TranscriptionService:
    """
    Service for transcribing audio via OpenAI Audio Transcription API (`whisper-1`).
    Preserves original spoken language and returns text without any AI LLM response.
    """
    def __init__(self):
        self.service = whisper_service

    def transcribe_audio(
        self,
        audio_file_path: str,
        language: Optional[str] = "auto",
        fallback_text: Optional[str] = None
    ) -> Dict[str, Any]:
        return self.service.transcribe_audio(
            audio_file_path=audio_file_path,
            language=language,
            fallback_text=fallback_text
        )

transcription_service = TranscriptionService()
