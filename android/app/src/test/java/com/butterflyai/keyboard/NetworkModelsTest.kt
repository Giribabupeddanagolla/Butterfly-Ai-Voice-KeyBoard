package com.butterflyai.keyboard

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NetworkModelsTest {

    private val gson = Gson()

    @Test
    fun testTranscriptionResultDefaultValues() {
        val result = TranscriptionResult(success = true)
        assertTrue(result.success)
        assertEquals("", result.originalText)
        assertEquals("", result.translatedText)
        assertEquals("en", result.language)
        assertEquals("", result.languageName)
        assertNull(result.error)
    }

    @Test
    fun testTranscriptionResultJsonDeserialization() {
        val json = """
            {
                "success": true,
                "originalText": "నమస్కారం",
                "translatedText": "Hello",
                "language": "te",
                "languageName": "Telugu",
                "error": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, TranscriptionResult::class.java)
        assertTrue(parsed.success)
        assertEquals("నమస్కారం", parsed.originalText)
        assertEquals("Hello", parsed.translatedText)
        assertEquals("te", parsed.language)
        assertEquals("Telugu", parsed.languageName)
        assertNull(parsed.error)
    }

    @Test
    fun testAskResultJsonParsing() {
        val json = """
            {
                "success": true,
                "answer": "Butterfly AI is an AI-powered voice keyboard.",
                "error": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, AskResult::class.java)
        assertTrue(parsed.success)
        assertEquals("Butterfly AI is an AI-powered voice keyboard.", parsed.answer)
        assertNull(parsed.error)
    }

    @Test
    fun testPolishResultJsonParsing() {
        val json = """
            {
                "success": true,
                "polishedText": "Could you please review the attached document?",
                "error": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, PolishResult::class.java)
        assertTrue(parsed.success)
        assertEquals("Could you please review the attached document?", parsed.polishedText)
        assertNull(parsed.error)
    }


    @Test
    fun testSearchResultParsing() {
        val json = """
            {
                "success": true,
                "query": "fastapi",
                "results": [
                    {
                        "title": "FastAPI Framework",
                        "url": "https://fastapi.tiangolo.com",
                        "snippet": "FastAPI is a modern, fast web framework for Python."
                    }
                ],
                "error": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, SearchResult::class.java)
        assertTrue(parsed.success)
        assertEquals("fastapi", parsed.query)
        assertEquals(1, parsed.results.size)
        assertEquals("FastAPI Framework", parsed.results[0].title)
        assertEquals("https://fastapi.tiangolo.com", parsed.results[0].url)
        assertTrue(parsed.results[0].snippet.contains("modern, fast web framework"))
    }

    @Test
    fun testHistorySessionAndConversationDetailParsing() {
        val historyJson = """
            {
                "success": true,
                "sessions": [
                    {
                        "session_id": "sess_12345",
                        "title": "Meeting Notes",
                        "created_at": "2026-10-01T10:00:00",
                        "updated_at": "2026-10-01T10:15:00",
                        "last_message": "Action items reviewed.",
                        "original_text": "Meeting discussion",
                        "translated_text": "Meeting discussion",
                        "source_language": "en",
                        "translation_language": "en"
                    }
                ]
            }
        """.trimIndent()

        val historyResult = gson.fromJson(historyJson, HistoryResult::class.java)
        assertTrue(historyResult.success)
        assertEquals(1, historyResult.sessions.size)
        assertEquals("sess_12345", historyResult.sessions[0].session_id)
        assertEquals("Meeting Notes", historyResult.sessions[0].title)
        assertEquals("Action items reviewed.", historyResult.sessions[0].last_message)

        val detailJson = """
            {
                "success": true,
                "session_id": "sess_12345",
                "messages": [
                    {
                        "id": 1,
                        "session_id": "sess_12345",
                        "role": "user",
                        "content": "Can you summarize the meeting?",
                        "source_language": "en",
                        "translation_language": "en",
                        "created_at": "2026-10-01T10:00:00"
                    },
                    {
                        "id": 2,
                        "session_id": "sess_12345",
                        "role": "assistant",
                        "content": "Here is the summary of the meeting...",
                        "source_language": "en",
                        "translation_language": "en",
                        "created_at": "2026-10-01T10:00:05"
                    }
                ]
            }
        """.trimIndent()

        val detailResult = gson.fromJson(detailJson, ConversationDetailResult::class.java)
        assertTrue(detailResult.success)
        assertEquals("sess_12345", detailResult.session_id)
        assertEquals(2, detailResult.messages.size)
        assertEquals("user", detailResult.messages[0].role)
        assertEquals("assistant", detailResult.messages[1].role)
    }

}
