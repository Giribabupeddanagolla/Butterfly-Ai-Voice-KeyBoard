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

# Edge TTS Voice Mappings (Male & Female Neural Voices per language)
EDGE_TTS_VOICES = {
    "en": {
        "nova": "en-US-AvaNeural",
        "shimmer": "en-US-JennyNeural",
        "alloy": "en-US-AriaNeural",
        "echo": "en-US-ChristopherNeural",
        "onyx": "en-US-GuyNeural",
        "fable": "en-GB-RyanNeural",
    },
    "te": {
        "male": "te-IN-MohanNeural",
        "female": "te-IN-ShrutiNeural"
    },
    "hi": {
        "male": "hi-IN-MadhurNeural",
        "female": "hi-IN-SwaraNeural"
    },
    "ta": {
        "male": "ta-IN-ValluvarNeural",
        "female": "ta-IN-PallaviNeural"
    },
    "kn": {
        "male": "kn-IN-GaganNeural",
        "female": "kn-IN-SapnaNeural"
    },
    "ml": {
        "male": "ml-IN-MidhunNeural",
        "female": "ml-IN-SobhanaNeural"
    },
    "mr": {
        "male": "mr-IN-ManoharNeural",
        "female": "mr-IN-AarohiNeural"
    },
    "bn": {
        "male": "bn-IN-BashkarNeural",
        "female": "bn-IN-TanishaaNeural"
    },
    "gu": {
        "male": "gu-IN-NiranjanNeural",
        "female": "gu-IN-DhwaniNeural"
    },
    "pa": {
        "male": "pa-IN-GurpreetNeural",
        "female": "pa-IN-HarmandeepNeural"
    },
    "ur": {
        "male": "ur-PK-AsadNeural",
        "female": "ur-PK-UzmaNeural"
    },
    "es": {
        "male": "es-ES-AlvaroNeural",
        "female": "es-ES-ElviraNeural"
    },
    "fr": {
        "male": "fr-FR-HenriNeural",
        "female": "fr-FR-DeniseNeural"
    },
    "de": {
        "male": "de-DE-KillianNeural",
        "female": "de-DE-KatjaNeural"
    },
    "ja": {
        "male": "ja-JP-KeitaNeural",
        "female": "ja-JP-NanamiNeural"
    },
    "ko": {
        "male": "ko-KR-InJoonNeural",
        "female": "ko-KR-SunHiNeural"
    },
    "zh": {
        "male": "zh-CN-YunxiNeural",
        "female": "zh-CN-XiaoxiaoNeural"
    },
    "ru": {
        "male": "ru-RU-DmitryNeural",
        "female": "ru-RU-SvetlanaNeural"
    }
}

def get_edge_voice(base_lang: str, voice_name: str) -> str:
    lang_voices = EDGE_TTS_VOICES.get(base_lang, EDGE_TTS_VOICES["en"])
    if base_lang == "en":
        return lang_voices.get(voice_name, "en-US-AvaNeural")
    
    is_male = voice_name in ["echo", "onyx", "fable"]
    gender_key = "male" if is_male else "female"
    return lang_voices.get(gender_key, lang_voices.get("female", "en-US-AvaNeural"))

class TextToSpeechService:
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
                logger.warning(f"Could not initialize OpenAI client for TTS: {e}")
                self.client = None
        elif not current_key:
            self.client = None
            self.api_key = None
        return self.client

    def generate_speech(self, text: str, language: str = "en", voice: str = "nova", speed: float = 1.0) -> Dict[str, Any]:
        """Convert text to speech audio file (.mp3) and return relative URL."""
        if not text or not text.strip():
            return {"success": False, "error": "Empty text provided", "audio_url": ""}

        filename = f"tts_{uuid.uuid4().hex[:10]}.mp3"
        output_path = os.path.join(config.AUDIO_OUTPUT_DIR, filename)

        # Validate voice & speed
        valid_voices = {"alloy", "echo", "fable", "onyx", "nova", "shimmer"}
        tts_voice = voice.lower().strip() if (voice and voice.lower().strip() in valid_voices) else "nova"
        tts_speed = max(0.25, min(4.0, float(speed))) if speed else 1.0

        from languages import normalize_language_code
        from speech_to_text import detect_language_from_text

        clean_lang = normalize_language_code(language)
        detected_text_lang = detect_language_from_text(text)
        if not clean_lang or clean_lang == "auto" or (clean_lang == "en" and detected_text_lang != "en"):
            clean_lang = detected_text_lang

        base_lang = (clean_lang or "en").lower().split('-')[0].split('_')[0]
        gtts_lang = GTTS_LANG_MAP.get(base_lang, "en")

        # 1. Try OpenAI TTS first (supports all 6 voices: Nova, Alloy, Echo, Fable, Onyx, Shimmer across languages)
        client = self.get_client()
        if client:
            try:
                response = client.audio.speech.create(
                    model=config.TTS_MODEL,
                    voice=tts_voice,
                    speed=tts_speed,
                    input=text[:4090] # Truncate to API max length
                )
                response.stream_to_file(output_path)
                logger.info(f"OpenAI TTS generated successfully using voice '{tts_voice}'")
                return {
                    "success": True,
                    "audio_url": f"/audio/output/{filename}",
                    "provider": "openai",
                    "voice": tts_voice
                }
            except Exception as e:
                logger.warning(f"OpenAI TTS failed ({e}), falling back to Edge TTS / gTTS")

        # 2. Try Edge TTS Neural voices (Free high-quality Male & Female voices for all languages)
        try:
            import asyncio
            import edge_tts
            edge_voice = get_edge_voice(base_lang, tts_voice)
            communicate = edge_tts.Communicate(text[:4090], edge_voice)
            asyncio.run(communicate.save(output_path))
            if os.path.exists(output_path) and os.path.getsize(output_path) > 0:
                logger.info(f"Edge TTS generated successfully using voice '{edge_voice}' ({tts_voice})")
                return {
                    "success": True,
                    "audio_url": f"/audio/output/{filename}",
                    "provider": "edge_tts",
                    "voice": tts_voice
                }
        except Exception as e:
            logger.warning(f"Edge TTS error ({e}), falling back to gTTS")

        # 3. Fallback to gTTS (Google Text-to-Speech)
        try:
            from gtts import gTTS
            slow_flag = True if tts_speed < 0.85 else False
            tts = gTTS(text=text, lang=gtts_lang, slow=slow_flag)
            tts.save(output_path)
            
            return {
                "success": True,
                "audio_url": f"/audio/output/{filename}",
                "provider": "gtts",
                "voice": tts_voice
            }
        except Exception as e:
            logger.error(f"gTTS generation failed: {e}")
            return {
                "success": False,
                "error": f"TTS synthesis failed: {str(e)}",
                "audio_url": ""
            }

tts_service = TextToSpeechService()
