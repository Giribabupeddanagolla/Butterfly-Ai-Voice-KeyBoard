import os
import logging
from typing import Dict, Any, Optional
from config import config

logger = logging.getLogger(__name__)

class WhisperService:
    def __init__(self):
        self.api_key = None
        self.client = None

    def get_client(self):
        current_key = config.OPENAI_API_KEY or os.getenv("OPENAI_API_KEY", "")
        if current_key and (not self.client or self.api_key != current_key):
            try:
                from openai import OpenAI
                self.api_key = current_key
                self.client = OpenAI(api_key=current_key, max_retries=0)
            except Exception as e:
                logger.warning(f"Could not initialize OpenAI client for WhisperService: {e}")
                self.client = None
        elif not current_key:
            self.client = None
            self.api_key = None
        return self.client

    def is_configured(self) -> bool:
        client = self.get_client()
        return client is not None and bool(self.api_key and self.api_key.startswith("sk-"))

    def transcribe_audio(
        self,
        audio_file_path: str,
        language: Optional[str] = None,
        fallback_text: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Transcribe audio using OpenAI Whisper API (`whisper-1`).
        Preserves spoken language. Never generates an AI response.
        Falls back seamlessly to local STT / SpeechRecognition if API key is not configured or quota fails.
        """
        if not audio_file_path or not os.path.exists(audio_file_path):
            if fallback_text and fallback_text.strip():
                from speech_to_text import detect_language_from_text
                text = fallback_text.strip()
                det_lang = detect_language_from_text(text)
                return {
                    "success": True,
                    "text": text,
                    "language": language if (language and language != "auto") else det_lang
                }
            return {
                "success": False,
                "error": "No speech detected. Please speak again.",
                "text": "",
                "language": language or "en"
            }

        # 1. Attempt OpenAI Whisper API
        client = self.get_client()
        if client:
            try:
                with open(audio_file_path, "rb") as audio_file:
                    kwargs = {
                        "model": getattr(config, "OPENAI_TRANSCRIPTION_MODEL", "whisper-1"),
                        "file": audio_file,
                        "response_format": "verbose_json"
                    }
                    if language and language != "auto":
                        kwargs["language"] = language

                    response = client.audio.transcriptions.create(**kwargs)

                    transcribed_text = ""
                    detected_lang = language or "en"

                    if hasattr(response, "text"):
                        transcribed_text = response.text.strip()
                    elif isinstance(response, dict):
                        transcribed_text = response.get("text", "").strip()

                    if hasattr(response, "language"):
                        detected_lang = getattr(response, "language", detected_lang)
                    elif isinstance(response, dict) and response.get("language"):
                        detected_lang = response.get("language")

                    if transcribed_text:
                        from languages import normalize_language_code
                        from speech_to_text import detect_language_from_text
                        norm_lang = normalize_language_code(detected_lang)
                        if not norm_lang or norm_lang == "auto":
                            norm_lang = detect_language_from_text(transcribed_text)

                        logger.info(f"Whisper API transcription successful: {transcribed_text[:30]}... ({norm_lang})")
                        return {
                            "success": True,
                            "text": transcribed_text,
                            "language": norm_lang
                        }
            except Exception as e:
                logger.warning(f"Whisper API error ({e}). Falling back to STT service.")

        # 2. Fallback to local STT service (SpeechRecognition / Google STT / client transcript)
        try:
            from speech_to_text import stt_service
            stt_res = stt_service.transcribe_audio(
                audio_file_path=audio_file_path,
                language=language,
                fallback_text=fallback_text
            )
            if stt_res.get("success") and stt_res.get("text"):
                return {
                    "success": True,
                    "text": stt_res["text"],
                    "language": stt_res.get("language", language or "en")
                }
        except Exception as e:
            logger.error(f"Fallback STT error: {e}")

        return {
            "success": False,
            "error": "Transcription failed. Please try again.",
            "text": "",
            "language": language or "en"
        }

whisper_service = WhisperService()
