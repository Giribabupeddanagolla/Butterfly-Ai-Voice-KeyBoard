package com.butterflyai.keyboard

import org.junit.Assert.*
import org.junit.Test

class UrlNormalizationTest {

    private fun normalizeUrl(rawUrl: String?): String {
        val defaultUrl = "https://butterfly-ai-voice-keyboard.onrender.com"
        var url = rawUrl?.trim() ?: defaultUrl
        if (url.isEmpty()) {
            url = defaultUrl
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }

    private fun getDiagnosticMessage(baseUrl: String): String {
        return if (baseUrl.contains("onrender.com", ignoreCase = true)) {
            "Unable to connect to backend ($baseUrl). Free cloud servers take ~30-50s to wake up from sleep. Please wait a moment and try again."
        } else {
            "Unable to connect to backend ($baseUrl). Ensure your PC and phone are on the same Wi-Fi network and the backend is running."
        }
    }

    @Test
    fun testTrailingSlashRemoved() {
        val input = "http://192.168.1.100:8000/"
        val normalized = normalizeUrl(input)
        assertEquals("http://192.168.1.100:8000", normalized)
    }

    @Test
    fun testWhitespaceTrimmed() {
        val input = "   http://localhost:8000/   "
        val normalized = normalizeUrl(input)
        assertEquals("http://localhost:8000", normalized)
    }

    @Test
    fun testEmptyUrlFallsBackToDefault() {
        val normalized = normalizeUrl("")
        assertEquals("https://butterfly-ai-voice-keyboard.onrender.com", normalized)

        val nullNormalized = normalizeUrl(null)
        assertEquals("https://butterfly-ai-voice-keyboard.onrender.com", nullNormalized)
    }

    @Test
    fun testLocalLanUrlsPreservedWithoutOverwrite() {
        val lanUrl = "http://192.168.29.145:8000"
        val normalized = normalizeUrl(lanUrl)
        assertEquals("http://192.168.29.145:8000", normalized)

        val loopbackUrl = "http://127.0.0.1:8000"
        val normalizedLoopback = normalizeUrl(loopbackUrl)
        assertEquals("http://127.0.0.1:8000", normalizedLoopback)
    }

    @Test
    fun testEndpointPathConcatenation() {
        val baseUrl = normalizeUrl("http://192.168.1.50:8000/")
        val chatEndpoint = "$baseUrl/api/chat"
        val transcribeEndpoint = "$baseUrl/api/transcribe"
        val healthEndpoint = "$baseUrl/health"

        assertEquals("http://192.168.1.50:8000/api/chat", chatEndpoint)
        assertEquals("http://192.168.1.50:8000/api/transcribe", transcribeEndpoint)
        assertEquals("http://192.168.1.50:8000/health", healthEndpoint)
    }

    @Test
    fun testDiagnosticMessageDifferentiation() {
        val renderUrl = "https://butterfly-ai-voice-keyboard.onrender.com"
        val renderMsg = getDiagnosticMessage(renderUrl)
        assertTrue(renderMsg.contains("Free cloud servers take ~30-50s to wake up"))

        val localUrl = "http://192.168.1.100:8000"
        val localMsg = getDiagnosticMessage(localUrl)
        assertTrue(localMsg.contains("same Wi-Fi network"))
    }
}
