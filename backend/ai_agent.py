import logging
from typing import Dict, Any, List
from config import config
from conversation_context import PROJECT_CONTEXT
from memory import memory_manager
from speech_to_text import detect_language_from_text

logger = logging.getLogger(__name__)

# Fallback intelligent responses per language when API key is not configured
MOCK_RESPONSES = {
    "te": "నమస్కారం! నేను మీ Multilingual Voice AI Agent ని. మీ సందేశం నాకు చేరింది: \"{user_text}\". ప్రస్తుతానికి OpenAI API Key సెట్ కాకపోవడం వల్ల, ఇది ఒక డెమో సమాధానం. మీ ప్రశ్నలను ఏ భాషలోనైనా అడగవచ్చు!",
    "hi": "नमस्ते! मैं आपका Multilingual Voice AI Agent हूँ। आपका संदेश मुझे मिला: \"{user_text}\"। वर्तमान में OpenAI API Key कॉन्फ़िगर नहीं है, इसलिए यह एक डेमो उत्तर है। आप किसी भी भाषा में पूछ सकते हैं!",
    "ta": "வணக்கம்! நான் உங்கள் Multilingual Voice AI Agent. உங்கள் செய்தி கிடைத்தது: \"{user_text}\". தற்போது OpenAI API Key அமைக்கப்படவில்லை, எனவே இது ஒரு மாதிரி பதில். நீங்கள் எந்த மொழியிலும் கேட்கலாம்!",
    "kn": "ನಮಸ್ಕಾರ! ನಾನು ನಿಮ್ಮ Multilingual Voice AI Agent. ನಿಮ್ಮ ಸಂದೇಶ ದೊರೆತಿದೆ: \"{user_text}\". ಪ್ರಸ್ತುತ OpenAI API Key ಹೊಂದಿಸಲಾಗಿಲ್ಲ, ಆದ್ದರಿಂದ ಇದು ಡೆಮೊ ಉತ್ತರವಾಗಿದೆ.",
    "en": "Hello! I am your Multilingual Voice AI Agent. I received your message: \"{user_text}\". Currently, OpenAI API Key is not set in backend .env, so this is a demonstration response. Please add your key to enable full GPT responses!"
}

from speech_to_text import detect_language_from_text, translate_text

logger = logging.getLogger(__name__)

class AIAgent:
    def __init__(self):
        self.api_key = None
        self.client = None

    def get_client(self):
        current_key = config.OPENAI_API_KEY
        if current_key and (not self.client or self.api_key != current_key):
            try:
                from openai import OpenAI
                self.api_key = current_key
                self.client = OpenAI(api_key=current_key)
            except Exception as e:
                logger.warning(f"Could not initialize OpenAI client for AI Agent: {e}")
                self.client = None
        return self.client

    def process_translation(
        self,
        session_id: str,
        user_message: str,
        source_language: str = "auto",
        translation_language: str = "en",
        text_language: str = None,
        input_type: str = "text"
    ) -> Dict[str, Any]:
        """Process user message, perform pure translation, and store in DB without chatbot commentary."""
        if not user_message or not user_message.strip():
            return {
                "success": False,
                "error": "Empty message",
                "session_id": session_id,
                "original_text": "",
                "translated_text": "",
                "display_text": "",
                "input_type": input_type
            }

        # 1. Detect source language if set to auto
        detected_source = detect_language_from_text(user_message)
        effective_source = source_language if (source_language and source_language != "auto") else detected_source
        target_trans_lang = translation_language or "en"
        target_text_lang = text_language or target_trans_lang

        # 2. Save original user message to persistent DB
        memory_manager.save_message(
            session_id=session_id,
            role="user",
            content=user_message,
            language=effective_source,
            original_text=user_message,
            source_language=effective_source,
            translation_language=target_trans_lang,
            text_language=target_text_lang,
            translated_text=user_message,
            input_type=input_type
        )

        # 3. Perform pure translation without chatbot commentary
        translated_result = translate_text(
            text=user_message,
            target_language=target_trans_lang,
            source_language=effective_source,
            text_language=target_text_lang
        )

        # 4. Save translation result to DB
        memory_manager.save_message(
            session_id=session_id,
            role="assistant",
            content=translated_result,
            language=target_text_lang,
            original_text=user_message,
            source_language=effective_source,
            translation_language=target_trans_lang,
            text_language=target_text_lang,
            translated_text=translated_result,
            input_type=input_type
        )

        return {
            "success": True,
            "session_id": session_id,
            "source_language": effective_source,
            "translation_language": target_trans_lang,
            "text_language": target_text_lang,
            "original_text": user_message,
            "translated_text": translated_result,
            "display_text": translated_result,
            "input_type": input_type
        }

ai_agent = AIAgent()
