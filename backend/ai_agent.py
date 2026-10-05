import logging
import uuid
from typing import Dict, Any, List, Optional, Callable
from config import config
from conversation_context import PROJECT_CONTEXT, get_project_context
from memory import memory_manager
from speech_to_text import detect_language_from_text, translate_text
from languages import get_language_name, normalize_language_code
from services.openai_service import openai_service

logger = logging.getLogger(__name__)

# Fallback intelligent responses per language when API key is not configured
MOCK_RESPONSES = {
    "te": "నమస్కారం! నేను మీ Multilingual Voice AI Agent ని. మీ సందేశం నాకు చేరింది: \"{user_text}\". ప్రస్తుతానికి OpenAI API Key సెట్ కాకపోవడం వల్ల, ఇది ఒక డెమో సమాధానం. మీ ప్రశ్నలను ఏ భాషలోనైనా అడగవచ్చు!",
    "hi": "नमस्ते! मैं आपका Multilingual Voice AI Agent हूँ। आपका संदेश मुझे मिला: \"{user_text}\"। वर्तमान में OpenAI API Key कॉन्फ़िगर नहीं है, इसलिए यह एक डेमो उत्तर है। आप किसी भी भाषा में पूछ सकते हैं!",
    "ta": "வணக்கம்! நான் உங்கள் Multilingual Voice AI Agent. உங்கள் செய்தி கிடைத்தது: \"{user_text}\". தற்போது OpenAI API Key அமைக்கப்படவில்லை, எனவே இது ஒரு மாதிரி பதில். நீங்கள் எந்த மொழியிலும் கேட்கலாம்!",
    "kn": "ನಮಸ್ಕಾರ! ನಾನು ನಿಮ್ಮ Multilingual Voice AI Agent. ನಿಮ್ಮ ಸಂದೇಶ ದೊರೆತಿದೆ: \"{user_text}\". ಪ್ರಸ್ತುತ OpenAI API Key ಹೊಂದಿಸಲಾಗಿಲ್ಲ, ಆದ್ದರಿಂದ ಇದು ಡೆಮೊ ಉತ್ತರವಾಗಿದೆ.",
    "en": "Hello! I am your Multilingual Voice AI Agent. I received your message: \"{user_text}\". Currently, OpenAI API Key is not set in backend .env, so this is a demonstration response. Please add your key to enable full GPT responses!"
}

def get_mock_response(language_code: str, user_text: str) -> str:
    """Return multilingual demonstration/fallback response when OpenAI is not configured."""
    template = MOCK_RESPONSES.get(language_code, MOCK_RESPONSES.get("en"))
    return template.format(user_text=user_text)

class AIAgent:
    def __init__(self):
        self.api_key = None
        self.client = None

    def get_client(self):
        client = openai_service.get_client()
        self.client = client
        self.api_key = openai_service.get_api_key()
        return client

    def handle_chat_request(
        self,
        message: str,
        session_id: Optional[str] = None,
        language: Optional[str] = None,
        source_language: Optional[str] = None,
        target_language: Optional[str] = None,
        fallback_handler: Optional[Callable[[str, str], Optional[str]]] = None
    ) -> Dict[str, Any]:
        """
        Central Chat Service:
        1. Validate request
        2. Resolve session_id
        3. Load conversation history
        4. Load PROJECT_CONTEXT with multilingual target instruction
        5. Build AI messages
        6. Call OpenAI service or fallback handler
        7. Ensure answer is in target language
        8. Save conversation to memory
        9. Return response
        """
        # 1. Validate request
        if not message or not message.strip():
            raise ValueError("Message cannot be empty.")
        clean_message = message.strip()

        # 2. Resolve session_id
        if session_id is not None:
            s_id = session_id.strip()
            if not s_id:
                raise ValueError("Invalid session_id: cannot be empty whitespace.")
            resolved_session_id = s_id
        else:
            resolved_session_id = f"session_{uuid.uuid4().hex[:8]}"

        # Ensure session exists in memory manager
        try:
            memory_manager.get_or_create_session(resolved_session_id)
        except Exception as e:
            logger.error(f"Failed to access session in database: {e}")
            raise RuntimeError("Database error while initializing session.")

        # Resolve target language
        target_lang = target_language or language
        if target_lang and target_lang != "auto":
            lang_code = normalize_language_code(target_lang)
        else:
            detected = detect_language_from_text(clean_message)
            lang_code = detected if detected else "en"
        lang_name = get_language_name(lang_code)

        # 3. Load conversation history
        try:
            history_messages = memory_manager.get_session_messages(resolved_session_id, limit=20)
        except Exception as e:
            logger.error(f"Failed to load conversation history: {e}")
            raise RuntimeError("Database error while loading conversation history.")

        # 4 & 5. Build AI messages with PROJECT_CONTEXT and explicit multilingual instruction
        system_instruction = PROJECT_CONTEXT.strip()
        if lang_code and lang_code != "auto":
            system_instruction += (
                f"\n\nCRITICAL MULTILINGUAL REQUIREMENT: You MUST formulate your entire response in {lang_name} ({lang_code}). "
                f"All explanations, answers, and statements MUST be translated and written fluently in {lang_name}. "
                f"Do NOT answer in English unless {lang_name} is English."
            )

        ai_messages: List[Dict[str, str]] = [
            {"role": "system", "content": system_instruction}
        ]
        for prev in history_messages:
            r = prev.get("role")
            c = prev.get("content")
            if r in ("user", "assistant") and c:
                ai_messages.append({"role": r, "content": c})
        ai_messages.append({"role": "user", "content": clean_message})

        # 6. Call OpenAI service
        is_configured = openai_service.is_configured()
        answer = None
        model = getattr(config, "AI_MODEL", "gpt-4o-mini")

        if is_configured:
            ai_res = openai_service.generate_chat_response(
                messages=ai_messages,
                language=lang_code
            )
            if ai_res.get("success"):
                answer = ai_res["answer"]
                model = ai_res.get("model", model)
            else:
                err_msg = ai_res.get("error", "AI service request failed")
                if fallback_handler:
                    try:
                        answer = fallback_handler(clean_message, lang_code)
                    except Exception as fb_err:
                        logger.warning(f"Fallback handler error: {fb_err}")
                if not answer:
                    logger.error(f"OpenAI service failure: {err_msg}")
                    raise RuntimeError(f"OpenAI service error: {err_msg}")
        else:
            # API key not configured - use intended multilingual fallback
            if fallback_handler:
                try:
                    answer = fallback_handler(clean_message, lang_code)
                except Exception as fb_err:
                    logger.warning(f"Fallback handler error: {fb_err}")
            
            if not answer:
                answer = get_mock_response(lang_code, clean_message)
            model = "butterfly-ai-fallback"

        # 7. Multilingual verification: ensure the final answer is translated into the target language
        if answer and lang_code != "en" and lang_code != "auto":
            detected_ans_lang = detect_language_from_text(answer)
            if detected_ans_lang != lang_code:
                try:
                    translated_ans = translate_text(answer, target_language=lang_code, source_language="auto")
                    if translated_ans and translated_ans.strip():
                        answer = translated_ans.strip()
                except Exception as tr_err:
                    logger.warning(f"Error ensuring final answer translation to {lang_code}: {tr_err}")

        # 8. Save conversation to memory (only after response is successfully produced)
        try:
            memory_manager.save_message(
                session_id=resolved_session_id,
                role="user",
                content=clean_message,
                language=lang_code,
                original_text=clean_message,
                source_language=source_language or "auto",
                translation_language=lang_code,
                text_language=lang_code,
                translated_text=clean_message,
                input_type="chat"
            )
            memory_manager.save_message(
                session_id=resolved_session_id,
                role="assistant",
                content=answer,
                language=lang_code,
                original_text=clean_message,
                source_language=source_language or "auto",
                translation_language=lang_code,
                text_language=lang_code,
                translated_text=answer,
                input_type="chat"
            )
        except Exception as e:
            logger.error(f"Failed to save messages to database: {e}")
            raise RuntimeError("Database error while saving message.")

        return {
            "success": True,
            "session_id": resolved_session_id,
            "message": answer,
            "answer": answer,
            "language": lang_name,
            "language_code": lang_code,
            "target_language": lang_code,
            "model": model
        }

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
