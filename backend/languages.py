"""
Butterfly AI - Centralized Language Configuration
"""

LANGUAGES = {
    "en": {
        "name": "English",
        "native": "English"
    },
    "te": {
        "name": "Telugu",
        "native": "తెలుగు"
    },
    "hi": {
        "name": "Hindi",
        "native": "हिन्दी"
    },
    "ta": {
        "name": "Tamil",
        "native": "தமிழ்"
    },
    "kn": {
        "name": "Kannada",
        "native": "ಕನ್ನಡ"
    },
    "ml": {
        "name": "Malayalam",
        "native": "മലയാളം"
    },
    "mr": {
        "name": "Marathi",
        "native": "मराठी"
    },
    "bn": {
        "name": "Bengali",
        "native": "বাংলা"
    },
    "gu": {
        "name": "Gujarati",
        "native": "ગુજરાતી"
    },
    "pa": {
        "name": "Punjabi",
        "native": "ਪੰਜਾਬੀ"
    },
    "ur": {
        "name": "Urdu",
        "native": "اردو"
    },
    "es": {
        "name": "Spanish",
        "native": "Español"
    },
    "fr": {
        "name": "French",
        "native": "Français"
    },
    "de": {
        "name": "German",
        "native": "Deutsch"
    },
    "it": {
        "name": "Italian",
        "native": "Italiano"
    },
    "pt": {
        "name": "Portuguese",
        "native": "Português"
    },
    "ar": {
        "name": "Arabic",
        "native": "العربية"
    },
    "ja": {
        "name": "Japanese",
        "native": "日本語"
    },
    "ko": {
        "name": "Korean",
        "native": "한국어"
    },
    "zh": {
        "name": "Chinese",
        "native": "中文"
    },
    "ru": {
        "name": "Russian",
        "native": "Русский"
    }
}

def get_language_name(code: str) -> str:
    if not code or code == "auto":
        return "Auto Detect"
    lang_info = LANGUAGES.get(code.lower())
    return lang_info["name"] if lang_info else code.upper()

def get_native_name(code: str) -> str:
    if not code or code == "auto":
        return "Auto Detect"
    lang_info = LANGUAGES.get(code.lower())
    return lang_info["native"] if lang_info else code.upper()

NAME_TO_CODE = {v["name"].lower(): k for k, v in LANGUAGES.items()}

def normalize_language_code(code_or_name: str) -> str:
    if not code_or_name or code_or_name == "auto":
        return "auto"
    cleaned = code_or_name.lower().strip()
    if cleaned in LANGUAGES:
        return cleaned
    if cleaned in NAME_TO_CODE:
        return NAME_TO_CODE[cleaned]
    return cleaned[:2]
