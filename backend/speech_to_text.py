import os
import re
import json
import logging
import urllib.request
import urllib.parse
from typing import Dict, Any
from config import config

logger = logging.getLogger(__name__)

from languages import LANGUAGES, get_language_name

LANGUAGE_NAMES = {k: v["name"] for k, v in LANGUAGES.items()}

def detect_language_from_text(text: str) -> str:
    """Detect language code based on script range or simple heuristics."""
    if not text:
        return "en"
    
    # Unicode script ranges
    if any('\u0c00' <= char <= '\u0c7f' for char in text): return "te" # Telugu
    if any('\u0900' <= char <= '\u097f' for char in text): return "hi" # Devanagari (Hindi, Marathi, Nepali)
    if any('\u0b80' <= char <= '\u0bff' for char in text): return "ta" # Tamil
    if any('\u0c80' <= char <= '\u0cff' for char in text): return "kn" # Kannada
    if any('\u0d00' <= char <= '\u0d7f' for char in text): return "ml" # Malayalam
    if any('\u0980' <= char <= '\u09ff' for char in text): return "bn" # Bengali / Assamese
    if any('\u0a80' <= char <= '\u0aff' for char in text): return "gu" # Gujarati
    if any('\u0a00' <= char <= '\u0a7f' for char in text): return "pa" # Gurmukhi (Punjabi)
    if any('\u0b00' <= char <= '\u0b7f' for char in text): return "or" # Odia
    if any('\u0600' <= char <= '\u06ff' for char in text): return "ur" # Arabic/Urdu
    if any('\u3040' <= char <= '\u30ff' for char in text): return "ja" # Japanese
    if any('\uac00' <= char <= '\ud7af' for char in text): return "ko" # Korean
    if any('\u4e00' <= char <= '\u9fff' for char in text): return "zh" # Chinese
    if any('\u0400' <= char <= '\u04ff' for char in text): return "ru" # Cyrillic (Russian)
    
    return "en"

def apply_smart_punctuation(text: str, language: str = "en") -> str:
    """Apply basic smart capitalization and punctuation formatting without altering words or meaning."""
    if not text or not text.strip():
        return ""
    
    clean = text.strip()
    clean = re.sub(r'\s+', ' ', clean)
    
    # Capitalize first letter if Latin character
    if clean and clean[0].isalpha() and clean[0].islower():
        clean = clean[0].upper() + clean[1:]
        
    # Add comma after initial greetings if followed by question/clause (e.g., "Hello, how are you")
    clean = re.sub(r'^(Hello|Hi|Hey)\s+(how|what|where|when|why|who|is|are|can|could|would|should|do|does|did)', r'\1, \2', clean, flags=re.IGNORECASE)

    # Re-verify first character upper
    if clean and clean[0].isalpha() and clean[0].islower():
        clean = clean[0].upper() + clean[1:]

    # Check terminal punctuation
    terminal_punct = ('.', '?', '!', '।', '||')
    if not clean.endswith(terminal_punct):
        lower_text = clean.lower()
        # Remove initial greeting clause if present for question detection
        check_text = re.sub(r'^(hello|hi|hey)[,\s]+', '', lower_text).strip()
        
        question_starters = (
            "who", "what", "where", "when", "why", "how", "which", "whose", "whom",
            "is ", "are ", "can ", "could ", "would ", "should ", "do ", "does ", "did ",
            "isnt", "arent", "cant", "wont", "am i", "have you", "has he", "are you", "do you",
            "ఏంటి", "ఎక్కడ", "ఎప్పుడు", "ఎందుకు", "ఎలా", "ఎవరు", "ఏమిటి"
        )
        if any(check_text.startswith(qs) for qs in question_starters):
            clean += "?"
        else:
            clean += "."
            
    return clean

_translation_cache = {}
_mymemory_blocked_until = 0.0

def perform_single_translation(text: str, source_lang: str, target_lang: str) -> str:
    """Perform faithful single-step translation between source and target language."""
    if not text or not text.strip() or not target_lang or target_lang == "auto":
        return text.strip() if text else ""
        
    text_clean = text.strip()
    detected = detect_language_from_text(text_clean)
    effective_source = source_lang if (source_lang and source_lang != "auto") else detected
    
    if effective_source == target_lang:
        return text_clean

    cache_key = (text_clean, effective_source, target_lang)
    if cache_key in _translation_cache:
        return _translation_cache[cache_key]

    translated_result = None

    # 1. Try OpenAI if client is available
    client = stt_service.get_client()
    if client:
        try:
            target_name = get_language_name(target_lang)
            prompt = (
                f"Translate the provided user text faithfully into {target_name}.\n\n"
                f"Rules:\n"
                f"- Return only the translation.\n"
                f"- Do not answer the user.\n"
                f"- Do not add information.\n"
                f"- Do not summarize.\n"
                f"- Do not explain.\n"
                f"- Do not invent sentences.\n"
                f"- Preserve names, numbers, dates, and original meaning.\n\n"
                f"Text:\n{text_clean}"
            )
            response = client.chat.completions.create(
                model=config.AI_MODEL,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1
            )
            translated = response.choices[0].message.content.strip()
            if translated and not translated.startswith("⚠️") and not translated.startswith("PLEASE SELECT"):
                translated_result = translated
        except Exception as e:
            logger.debug(f"OpenAI translation unavailable: {e}")
            if "429" in str(e) or "quota" in str(e).lower():
                stt_service.openai_stt_failed = True

    # 2. Try Google Translate API (Primary fast & free provider)
    if not translated_result:
        try:
            url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl={effective_source}&tl={target_lang}&dt=t&q={urllib.parse.quote(text_clean)}"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=4) as response:
                data = json.loads(response.read().decode('utf-8'))
                if data and isinstance(data, list) and len(data) > 0 and data[0]:
                    translated_parts = [part[0] for part in data[0] if part and len(part) > 0 and part[0]]
                    translated_text = "".join(translated_parts).strip()
                    if translated_text and not translated_text.startswith("PLEASE SELECT"):
                        translated_result = translated_text
        except Exception as e:
            logger.debug(f"Google Translate endpoint failed: {e}")

    # 3. Try deep_translator library
    if not translated_result:
        try:
            from deep_translator import GoogleTranslator
            translated = GoogleTranslator(source=effective_source, target=target_lang).translate(text_clean)
            if translated and "error" not in translated.lower() and "please select" not in translated.lower():
                translated_result = translated.strip()
        except Exception as e:
            logger.debug(f"deep_translator failed: {e}")

    # 4. Try MyMemory API (with circuit breaker on HTTP 429)
    global _mymemory_blocked_until
    import time
    if not translated_result and time.time() > _mymemory_blocked_until:
        try:
            pair_source = effective_source if effective_source != target_lang else "autodetect"
            langpair = f"{pair_source}|{target_lang}"
            url = f"https://api.mymemory.translated.net/get?q={urllib.parse.quote(text_clean)}&langpair={langpair}"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=4) as response:
                data = json.loads(response.read().decode('utf-8'))
                if data and "responseData" in data and "translatedText" in data["responseData"]:
                    status = str(data.get("responseStatus", "200"))
                    translated = data["responseData"]["translatedText"].strip()
                    if status == "200" and translated and not translated.startswith("PLEASE SELECT") and not translated.startswith("MYMEMORY WARNING"):
                        translated_result = translated
        except Exception as e:
            if "429" in str(e):
                _mymemory_blocked_until = time.time() + 600 # Backoff 10 mins on 429 Rate Limit
                logger.info("MyMemory API rate-limited (HTTP 429). Pausing MyMemory requests for 10 minutes.")
            else:
                logger.debug(f"MyMemory translation failed: {e}")

    if translated_result:
        _translation_cache[cache_key] = translated_result
        if len(_translation_cache) > 2000:
            _translation_cache.clear()
        return translated_result

    return text_clean

def translate_text(text: str, target_language: str = "en", source_language: str = "auto", text_language: str = None) -> str:
    """Multi-stage translation pipeline supporting source, translation, and text display languages."""
    if not text or not text.strip():
        return ""
        
    # Step A: Translate from source to translation_language
    primary_translated = perform_single_translation(text, source_lang=source_language, target_lang=target_language)
    
    # Step B: If text_language is provided and differs from translation_language, render into text_language
    if text_language and text_language != "auto" and text_language != target_language:
        final_rendered = perform_single_translation(primary_translated, source_lang=target_language, target_lang=text_language)
        return final_rendered
        
    return primary_translated

SR_LANG_MAP = {
    "te": "te-IN", "hi": "hi-IN", "ta": "ta-IN", "kn": "kn-IN",
    "ml": "ml-IN", "mr": "mr-IN", "bn": "bn-IN", "gu": "gu-IN",
    "pa": "pa-IN", "ur": "ur-PK", "en": "en-US", "es": "es-ES",
    "fr": "fr-FR", "de": "de-DE", "it": "it-IT", "pt": "pt-PT",
    "ar": "ar-SA", "ja": "ja-JP", "ko": "ko-KR", "zh": "zh-CN", "ru": "ru-RU"
}

def convert_audio_to_pcm_wav(input_path: str) -> str:
    """Convert any input audio file (webm, ogg, mp3, m4a, non-PCM wav) to 16kHz mono 16-bit PCM WAV."""
    if not input_path or not os.path.exists(input_path):
        return input_path
        
    output_wav = input_path + "_converted.wav"
    
    # 1. Try ffmpeg via imageio_ffmpeg or system ffmpeg
    try:
        import subprocess
        import shutil
        ffmpeg_bin = None
        try:
            import imageio_ffmpeg
            ffmpeg_bin = imageio_ffmpeg.get_ffmpeg_exe()
        except Exception:
            ffmpeg_bin = None
            
        if not ffmpeg_bin:
            ffmpeg_bin = shutil.which("ffmpeg")
                
        if ffmpeg_bin:
            cmd = [ffmpeg_bin, "-y", "-i", input_path, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", output_wav]
            sub_kwargs = {}
            if hasattr(subprocess, "CREATE_NO_WINDOW"):
                sub_kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, **sub_kwargs)
            if res.returncode == 0 and os.path.exists(output_wav):
                logger.info(f"Successfully converted audio {input_path} to WAV: {output_wav}")
                return output_wav
            else:
                logger.warning(f"ffmpeg conversion failed with code {res.returncode}: {res.stderr.decode('utf-8', errors='ignore')}")
    except Exception as e:
        logger.debug(f"ffmpeg conversion failed: {e}")

    # 2. Try pydub if available
    try:
        from pydub import AudioSegment
        sound = AudioSegment.from_file(input_path)
        sound = sound.set_frame_rate(16000).set_channels(1)
        sound.export(output_wav, format="wav")
        if os.path.exists(output_wav):
            return output_wav
    except Exception as e:
        logger.debug(f"pydub conversion failed: {e}")

    return input_path

class SpeechToTextService:
    def __init__(self):
        self.api_key = None
        self.client = None
        self.openai_stt_failed = False

    def get_client(self):
        if self.openai_stt_failed:
            return None
        current_key = config.OPENAI_API_KEY
        if current_key and (not self.client or self.api_key != current_key):
            try:
                from openai import OpenAI
                self.api_key = current_key
                self.client = OpenAI(api_key=current_key, max_retries=0)
            except Exception as e:
                logger.warning(f"Could not initialize OpenAI client for STT: {e}")
                self.client = None
        return self.client

    def transcribe_audio(self, audio_file_path: str, prompt: str = None, language: str = None, fallback_text: str = None) -> Dict[str, Any]:
        """Transcribe audio file to text using OpenAI Whisper API, SpeechRecognition fallback, or client fallback text."""
        clean_fallback = fallback_text.strip() if (fallback_text and fallback_text.strip()) else None

        # 1. Try OpenAI Whisper if API key is set and has not failed
        if os.path.exists(audio_file_path):
            client = self.get_client()
            if client:
                try:
                    with open(audio_file_path, "rb") as audio_file:
                        kwargs = {
                            "model": config.STT_MODEL,
                            "file": audio_file
                        }
                        if prompt:
                            kwargs["prompt"] = prompt
                        if language and language != "auto":
                            kwargs["language"] = language
                            
                        response = self.client.audio.transcriptions.create(**kwargs)
                        transcribed_text = response.text.strip()
                        if transcribed_text:
                            transcribed_text = re.sub(r'[♪♫🎵🎶♭♮♯]', '', transcribed_text)
                            transcribed_text = re.sub(r'\[(music|singing|background music)\]', '', transcribed_text, flags=re.IGNORECASE)
                            transcribed_text = re.sub(r'\((music|singing|background music)\)', '', transcribed_text, flags=re.IGNORECASE).strip()
                        if transcribed_text:
                            detected_lang = detect_language_from_text(transcribed_text)
                            final_lang = language if (language and language != "auto") else detected_lang
                            punct_text = apply_smart_punctuation(transcribed_text, final_lang)
                            return {
                                "success": True,
                                "text": punct_text,
                                "language": final_lang,
                                "provider": "openai"
                            }
                except Exception as e:
                    logger.warning(f"OpenAI Whisper STT error ({e}), skipping OpenAI and falling back to SpeechRecognition")
                    self.openai_stt_failed = True

        # 2. Fallback to SpeechRecognition (free Google Speech Recognition)
        if os.path.exists(audio_file_path):
            converted_path = convert_audio_to_pcm_wav(audio_file_path)
            if converted_path and os.path.exists(converted_path):
                try:
                    import speech_recognition as sr
                    r = sr.Recognizer()
                    with sr.AudioFile(converted_path) as source:
                        audio_data = r.record(source)
                    
                    base_lang = (language or "auto").lower().split('-')[0].split('_')[0]
                    candidate_langs = []
                    if base_lang and base_lang != "auto":
                        primary_sr = SR_LANG_MAP.get(base_lang, "en-US")
                        candidate_langs.append(primary_sr)
                    elif clean_fallback:
                        fallback_lang = detect_language_from_text(clean_fallback)
                        if fallback_lang in SR_LANG_MAP:
                            candidate_langs.append(SR_LANG_MAP[fallback_lang])
                    
                    candidate_langs.extend(["en-US", "hi-IN", "te-IN", "kn-IN", "mr-IN", "ta-IN", "ml-IN", "bn-IN"])
                    
                    seen = set()
                    target_langs = [x for x in candidate_langs if not (x in seen or seen.add(x))]
                    
                    for sr_lang in target_langs:
                        try:
                            transcribed_text = r.recognize_google(audio_data, language=sr_lang)
                            if transcribed_text and transcribed_text.strip():
                                detected_lang = detect_language_from_text(transcribed_text.strip())
                                final_lang = language if (language and language != "auto") else detected_lang
                                logger.info(f"Google SR fallback successful ({sr_lang}): {transcribed_text[:30]}...")
                                punct_text = apply_smart_punctuation(transcribed_text.strip(), final_lang)
                                return {
                                    "success": True,
                                    "text": punct_text,
                                    "language": final_lang,
                                    "provider": "google_sr"
                                }
                        except Exception:
                            continue
                except Exception as e:
                    logger.warning(f"SpeechRecognition fallback error: {e}")
                finally:
                    if converted_path != audio_file_path and os.path.exists(converted_path):
                        try:
                            os.remove(converted_path)
                        except Exception:
                            pass

        # 3. Fallback to client-side transcript text if provided
        if clean_fallback:
            detected_lang = detect_language_from_text(clean_fallback)
            final_lang = language if (language and language != "auto") else detected_lang
            punct_text = apply_smart_punctuation(clean_fallback, final_lang)
            return {
                "success": True,
                "text": punct_text,
                "language": final_lang,
                "provider": "client_fallback"
            }

        return {
            "success": False,
            "error": "Could not transcribe audio. Please try speaking clearly again.",
            "text": "",
            "language": "en"
        }

stt_service = SpeechToTextService()
