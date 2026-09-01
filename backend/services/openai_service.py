import os
import logging
from typing import Dict, Any, Optional
from config import config

logger = logging.getLogger(__name__)

class OpenAIService:
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
                logger.warning(f"Could not initialize OpenAI client: {e}")
                self.client = None
        elif not current_key:
            self.client = None
            self.api_key = None
        return self.client

    def is_configured(self) -> bool:
        client = self.get_client()
        return client is not None and bool(self.api_key and self.api_key.startswith("sk-"))

    def transcribe_audio(self, audio_file_path: str, prompt: Optional[str] = None, language: Optional[str] = None) -> Dict[str, Any]:
        """Transcribe audio using OpenAI Whisper API."""
        client = self.get_client()
        if not client:
            return {
                "success": False,
                "error": "OpenAI API key is not configured.",
                "text": "",
                "language": language or "en"
            }

        if not os.path.exists(audio_file_path):
            return {
                "success": False,
                "error": "Audio file not found.",
                "text": "",
                "language": language or "en"
            }

        try:
            with open(audio_file_path, "rb") as audio_file:
                model_name = getattr(config, "OPENAI_TRANSCRIPTION_MODEL", config.STT_MODEL)
                kwargs = {
                    "model": model_name,
                    "file": audio_file
                }
                if prompt:
                    kwargs["prompt"] = prompt
                if language and language != "auto":
                    kwargs["language"] = language

                response = client.audio.transcriptions.create(**kwargs)
                transcribed_text = response.text.strip()
                return {
                    "success": True,
                    "text": transcribed_text,
                    "language": language or "auto"
                }
        except Exception as e:
            logger.error(f"OpenAI transcription error: {e}")
            return {
                "success": False,
                "error": f"Speech transcription failed: {e}",
                "text": "",
                "language": language or "en"
            }

    def translate_text(self, text: str, source_language: str = "auto", target_language: str = "en") -> Dict[str, Any]:
        """Translate text using OpenAI Chat API."""
        client = self.get_client()
        if not client:
            return {
                "success": False,
                "error": "OpenAI API key is not configured.",
                "original_text": text,
                "translated_text": text,
                "source_language": source_language,
                "target_language": target_language
            }

        try:
            from languages import get_language_name
            target_name = get_language_name(target_language)
            model_name = getattr(config, "OPENAI_TRANSLATION_MODEL", config.AI_MODEL)
            prompt = (
                f"Translate the provided text faithfully into {target_name}.\n\n"
                f"Rules:\n"
                f"- Return only the translation.\n"
                f"- Do not answer the user.\n"
                f"- Do not add explanations or conversational text.\n"
                f"- Preserve names, numbers, dates, and original meaning.\n\n"
                f"Text:\n{text}"
            )
            response = client.chat.completions.create(
                model=model_name,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1
            )
            translated = response.choices[0].message.content.strip()
            return {
                "success": True,
                "original_text": text,
                "translated_text": translated,
                "source_language": source_language,
                "target_language": target_language
            }
        except Exception as e:
            logger.error(f"OpenAI translation error: {e}")
            return {
                "success": False,
                "error": f"Translation failed: {e}",
                "original_text": text,
                "translated_text": text,
                "source_language": source_language,
                "target_language": target_language
            }

openai_service = OpenAIService()
