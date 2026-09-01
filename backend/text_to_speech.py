import os
import uuid
import logging
from typing import Dict, Any
from pathlib import Path
from config import config

logger = logging.getLogger(__name__)

# Map internal language codes to gTTS language codes
GTTS_LANG_MAP = {
    "te": "te", # Telugu
    "hi": "hi", # Hindi
    "ta": "ta", # Tamil
    "kn": "kn", # Kannada
    "ml": "ml", # Malayalam
    "mr": "mr", # Marathi
    "bn": "bn", # Bengali
    "gu": "gu", # Gujarati
    "pa": "pa", # Punjabi
    "ur": "ur", # Urdu
    "en": "en", # English
    "es": "es", # Spanish
    "fr": "fr", # French
    "de": "de", # German
    "it": "it", # Italian
    "pt": "pt", # Portuguese
    "ar": "ar", # Arabic
    "ja": "ja", # Japanese
    "ko": "ko", # Korean
    "zh": "zh-CN", # Chinese
    "ru": "ru", # Russian
}

class TextToSpeechService:
    def __init__(self):
        self.api_key = None
        self.client = None
        self.openai_tts_failed = False

    def get_client(self):
        if self.openai_tts_failed:
            return None
        current_key = config.OPENAI_API_KEY
        if current_key and (not self.client or self.api_key != current_key):
            try:
                from openai import OpenAI
                self.api_key = current_key
                self.client = OpenAI(api_key=current_key, max_retries=0)
            except Exception as e:
                logger.warning(f"Could not initialize OpenAI client for TTS: {e}")
                self.client = None
        return self.client

    def generate_speech(self, text: str, language: str = "en") -> Dict[str, Any]:
        """Convert text to speech audio file (.mp3) and return relative URL."""
        if not text or not text.strip():
            return {"success": False, "error": "Empty text provided", "audio_url": ""}

        filename = f"tts_{uuid.uuid4().hex[:10]}.mp3"
        output_path = os.path.join(config.AUDIO_OUTPUT_DIR, filename)

        # 1. Try OpenAI TTS if client is available and has not failed
        client = self.get_client()
        if client:
            try:
                # OpenAI voices: alloy, echo, fable, onyx, nova, shimmer
                response = self.client.audio.speech.create(
                    model=config.TTS_MODEL,
                    voice="nova",
                    input=text[:4090] # Truncate to API max length
                )
                response.stream_to_file(output_path)
                return {
                    "success": True,
                    "audio_url": f"/audio/output/{filename}",
                    "provider": "openai"
                }
            except Exception as e:
                logger.warning(f"OpenAI TTS failed ({e}), skipping OpenAI and falling back to gTTS")
                self.openai_tts_failed = True

        # 2. Fallback to gTTS (Google Text-to-Speech)
        try:
            from gtts import gTTS
            base_lang = (language or "en").lower().split('-')[0].split('_')[0]
            gtts_lang = GTTS_LANG_MAP.get(base_lang, "en")
            # Create gTTS audio
            tts = gTTS(text=text, lang=gtts_lang, slow=False)
            tts.save(output_path)
            
            return {
                "success": True,
                "audio_url": f"/audio/output/{filename}",
                "provider": "gtts"
            }
        except Exception as e:
            logger.error(f"gTTS generation failed: {e}")
            return {
                "success": False,
                "error": f"TTS synthesis failed: {str(e)}",
                "audio_url": ""
            }

tts_service = TextToSpeechService()
