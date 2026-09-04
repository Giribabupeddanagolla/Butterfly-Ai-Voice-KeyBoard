import os
import logging
from typing import Dict, Any, Optional
from config import config

logger = logging.getLogger(__name__)

class OpenAIService:
    def __init__(self):
        self.api_key = None
        self.client = None
        self.openai_failed = False

    def get_client(self):
        current_key = config.OPENAI_API_KEY or os.getenv("OPENAI_API_KEY", "")
        if current_key:
            if not self.client or self.api_key != current_key:
                try:
                    from openai import OpenAI
                    self.api_key = current_key
                    self.client = OpenAI(api_key=current_key, max_retries=0)
                except Exception as e:
                    logger.warning(f"Could not initialize OpenAI client: {e}")
                    self.client = None
        else:
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
            err_str = str(e)
            if "429" in err_str or "quota" in err_str.lower():
                if not self.openai_failed:
                    logger.info("OpenAI API quota exhausted. Switching to free fallback STT.")
                self.openai_failed = True
            else:
                logger.warning(f"OpenAI transcription error: {e}")
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
            err_str = str(e)
            if "429" in err_str or "quota" in err_str.lower() or "credit_balance_exhausted" in err_str.lower() or "insufficient_quota" in err_str.lower():
                if not self.openai_failed:
                    logger.info("OpenAI API quota exhausted. Switching to free fallback translation.")
                self.openai_failed = True
            else:
                logger.warning(f"OpenAI translation error: {e}")
            return {
                "success": False,
                "error": f"Translation failed: {e}",
                "original_text": text,
                "translated_text": text,
                "source_language": source_language,
                "target_language": target_language
            }

    def generate_chat_response(self, prompt: str, language: Optional[str] = None) -> Dict[str, Any]:
        """Generate AI response using OpenAI Chat API preserving prompt language."""
        client = self.get_client()
        if not client:
            return {
                "success": False,
                "error": "OpenAI API key is not configured. Please add your OPENAI_API_KEY in Settings or backend/.env."
            }

        try:
            sys_msg = (
                "You are Butterfly AI, an intelligent, helpful voice and text assistant. "
                "Answer the user's question accurately, clearly, and concisely. "
                "CRITICAL: Always answer in the exact same language that the user used in their question "
                "(e.g., if asked in Telugu, answer in Telugu; if asked in English, answer in English; "
                "if asked in Hindi, answer in Hindi, etc.). Do not translate or change the language unless explicitly requested."
            )
            model_name = getattr(config, "AI_MODEL", "gpt-4o-mini")
            response = client.chat.completions.create(
                model=model_name,
                messages=[
                    {"role": "system", "content": sys_msg},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.7,
                max_tokens=600
            )
            answer = response.choices[0].message.content.strip()
            return {
                "success": True,
                "answer": answer,
                "model": model_name
            }
        except Exception as e:
            err_str = str(e)
            logger.warning(f"OpenAI chat completion error: {e}")
            if "429" in err_str or "quota" in err_str.lower() or "credit_balance_exhausted" in err_str.lower() or "insufficient_quota" in err_str.lower():
                return {
                    "success": False,
                    "error": "OpenAI API quota exhausted. Please check your OpenAI billing plan or update your API key in Settings."
                }
            elif "401" in err_str or "invalid_api_key" in err_str.lower() or "incorrect api key" in err_str.lower():
                return {
                    "success": False,
                    "error": "Invalid OpenAI API key. Please check and update your API key in Settings."
                }
            else:
                return {
                    "success": False,
                    "error": f"OpenAI API request failed: {err_str}"
                }

openai_service = OpenAIService()

