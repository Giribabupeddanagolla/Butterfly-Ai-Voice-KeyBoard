package com.butterflyai.keyboard

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.util.concurrent.TimeUnit

data class TranscriptionResult(
    val success: Boolean,
    val originalText: String = "",
    val translatedText: String = "",
    val language: String = "en",
    val languageName: String = "",
    val error: String? = null
)

data class PolishResult(
    val success: Boolean,
    val polishedText: String = "",
    val error: String? = null
)

data class AskResult(
    val success: Boolean,
    val answer: String = "",
    val error: String? = null
)

data class ConnectionTestResult(
    val success: Boolean,
    val message: String
)

data class SnippetItem(
    val id: Int = 0,
    val name: String = "",
    val text: String = "",
    val voice_trigger: String? = null,
    val created_at: String? = null
)

data class SnippetsResult(
    val success: Boolean,
    val snippets: List<SnippetItem> = emptyList(),
    val error: String? = null
)

object StarterSnippets {
    val list: List<SnippetItem> = listOf(
        SnippetItem(id = 1, name = "Quick Greeting", text = "Hello! Hope you are having a wonderful day.", voice_trigger = "greeting"),
        SnippetItem(id = 2, name = "Work Email", text = "contact@butterfly.ai", voice_trigger = "my email"),
        SnippetItem(id = 3, name = "Phone Number", text = "+1 (555) 019-2834", voice_trigger = "my phone"),
        SnippetItem(id = 4, name = "Meeting Follow-up", text = "Thanks for your time today! Looking forward to our next steps.", voice_trigger = "meeting follow up"),
        SnippetItem(id = 5, name = "Be Right Back", text = "I am currently away from my desk, but I will get back to you shortly.", voice_trigger = "be right back"),
        SnippetItem(id = 6, name = "Thank You", text = "Thank you so much for your assistance! Greatly appreciate your help.", voice_trigger = "thank you")
    )
}

data class SearchItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = ""
)

data class SearchResult(
    val success: Boolean,
    val query: String = "",
    val results: List<SearchItem> = emptyList(),
    val error: String? = null
)

data class HistorySession(
    val session_id: String = "",
    val title: String? = null,
    val created_at: String? = null,
    val created_at_ist: String? = null,
    val time_ago: String? = null,
    val timestamp_ms: Long? = null,
    val updated_at: String? = null,
    val updated_at_ist: String? = null,
    val last_message: String? = null,
    val original_text: String? = null,
    val translated_text: String? = null,
    val source_language: String? = null,
    val translation_language: String? = null
)

data class HistoryResult(
    val success: Boolean,
    val sessions: List<HistorySession> = emptyList(),
    val error: String? = null
)

data class MessageItem(
    val id: Int? = null,
    val session_id: String? = null,
    val role: String? = null,
    val content: String? = null,
    val original_text: String? = null,
    val translated_text: String? = null,
    val source_language: String? = null,
    val translation_language: String? = null,
    val created_at: String? = null
)

data class ConversationDetailResult(
    val success: Boolean,
    val session_id: String? = null,
    val messages: List<MessageItem> = emptyList(),
    val error: String? = null
)

/**
 * Interceptor to handle Render free-tier spin-down / cold starts.
 * Render free containers take 30-50s to wake up from sleep, during which
 * connections may time out or Render's edge gateway returns HTTP 502/503/504.
 */
class RenderColdStartRetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastException: Exception? = null

        while (attempt < maxRetries) {
            try {
                val response = chain.proceed(request)
                if (response.code in listOf(502, 503, 504) && attempt < maxRetries - 1) {
                    Log.w("NetworkService", "Render server spinning up (HTTP ${response.code}). Retrying in 2.5s (attempt ${attempt + 1}/$maxRetries)...")
                    response.close()
                    attempt++
                    try {
                        Thread.sleep(2500L * attempt)
                    } catch (_: InterruptedException) {}
                    continue
                }
                return response
            } catch (e: Exception) {
                lastException = e
                if ((e is java.net.SocketTimeoutException || e is java.net.ConnectException) && attempt < maxRetries - 1) {
                    attempt++
                    Log.w("NetworkService", "Connection timeout/refused (likely Render cold start). Retrying in 2.5s (attempt $attempt/$maxRetries)...", e)
                    try {
                        Thread.sleep(2500L * attempt)
                    } catch (_: InterruptedException) {}
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: java.io.IOException("Request failed after $maxRetries cold-start retry attempts")
    }
}

class NetworkService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(RenderColdStartRetryInterceptor(maxRetries = 3))
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val gson = Gson()

    fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        // Priority: 1. User-configured/saved URL. 2. Safe default Render URL only if none configured.
        // NOTE: Local/LAN URLs (e.g. 192.168.x.x, localhost, 127.0.0.1) MUST NOT be overwritten.
        var url = prefs.getString("server_url", "https://butterfly-ai-voice-keyboard.onrender.com")
            ?: "https://butterfly-ai-voice-keyboard.onrender.com"
        url = url.trim()
        if (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }

    suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        return@withContext try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ConnectionTestResult(true, "Connected successfully to Butterfly AI backend ($baseUrl)")
                } else {
                    ConnectionTestResult(false, "Server returned HTTP error code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Test connection error: ${e.message}", e)
            val errorMsg = if (baseUrl.contains("onrender.com", ignoreCase = true)) {
                "Unable to connect to backend ($baseUrl). Free cloud servers take ~30-50s to wake up from sleep. Please wait a moment and try again."
            } else {
                "Unable to connect to backend ($baseUrl). Ensure your PC and phone are on the same Wi-Fi network and the backend is running."
            }
            ConnectionTestResult(false, errorMsg)
        }
    }

    suspend fun transcribeAudio(
        audioFile: File,
        sourceLanguage: String = "auto",
        targetLanguage: String = "en",
        isTranslateOn: Boolean = false
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val mediaType = when {
                audioFile.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                audioFile.name.endsWith(".m4a", ignoreCase = true) -> "audio/m4a"
                audioFile.name.endsWith(".aac", ignoreCase = true) -> "audio/aac"
                audioFile.name.endsWith(".3gp", ignoreCase = true) -> "audio/3gpp"
                else -> "audio/webm"
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    RequestBody.create(mediaType.toMediaTypeOrNull(), audioFile)
                )
                .addFormDataPart("source_language", sourceLanguage)
                .addFormDataPart("translation_language", targetLanguage)
                .addFormDataPart("target_language", targetLanguage)
                .addFormDataPart("is_translate_on", isTranslateOn.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/transcribe")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                var jsonErr: String? = null
                var jsonObj: JsonObject? = null
                if (bodyString.isNotBlank()) {
                    try {
                        jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                        if (jsonObj != null) {
                            if (jsonObj.has("error")) {
                                jsonErr = jsonObj.get("error")?.asString
                            } else if (jsonObj.has("detail")) {
                                jsonErr = jsonObj.get("detail")?.asString
                            }
                        }
                    } catch (e: Exception) {}
                }

                if (!response.isSuccessful) {
                    return@withContext TranscriptionResult(false, error = jsonErr ?: "Backend HTTP error ${response.code}")
                }

                if (jsonObj == null) {
                    return@withContext TranscriptionResult(false, error = "Empty response from server")
                }

                if (jsonObj.has("success") && !jsonObj.get("success").asBoolean) {
                    val err = jsonErr ?: "Transcription failed"
                    return@withContext TranscriptionResult(false, error = err)
                }

                val originalText = jsonObj.get("original_text")?.asString
                    ?: jsonObj.get("transcription")?.asString
                    ?: jsonObj.get("spoken_text")?.asString
                    ?: jsonObj.get("source_text")?.asString
                    ?: jsonObj.get("text")?.asString
                    ?: ""

                val detectedLang = jsonObj.get("language")?.asString ?: sourceLanguage
                val detectedLangName = jsonObj.get("language_name")?.asString ?: ""

                var finalTranslatedText = jsonObj.get("translation")?.asString
                    ?: jsonObj.get("translated_text")?.asString
                    ?: ""

                if (finalTranslatedText.isBlank()) {
                    finalTranslatedText = originalText
                }

                if (isTranslateOn && originalText.isNotBlank() && detectedLang != targetLanguage && (finalTranslatedText == originalText || finalTranslatedText.isBlank())) {
                    val translated = translateTextDirect(originalText, detectedLang, targetLanguage)
                    if (!translated.isNullOrBlank()) {
                        finalTranslatedText = translated
                    }
                }

                return@withContext TranscriptionResult(
                    success = true,
                    originalText = originalText,
                    translatedText = finalTranslatedText,
                    language = detectedLang,
                    languageName = detectedLangName
                )
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Transcribe audio error: ${e.message}", e)
            return@withContext TranscriptionResult(false, error = e.localizedMessage ?: "Network connection failed")
        }
    }

    suspend fun translateTextDirect(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val src = sourceLang.trim().lowercase()
        val tgt = targetLang.trim().lowercase()
        if (src != "auto" && src == tgt) {
            return@withContext text
        }
        val baseUrl = getBaseUrl()
        try {
            val jsonBody = JsonObject().apply {
                addProperty("text", text)
                addProperty("source_language", sourceLang)
                addProperty("target_language", targetLang)
                addProperty("translation_language", targetLang)
            }

            val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
            val request = Request.Builder()
                .url("$baseUrl/api/translate")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return@withContext null
                    val json = gson.fromJson(bodyString, JsonObject::class.java)
                    if (json.has("success") && json.get("success").asBoolean) {
                        return@withContext json.get("translation")?.asString
                            ?: json.get("translated_text")?.asString
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Translation internal error: ${e.message}")
        }
        return@withContext null
    }

    suspend fun polishText(text: String): PolishResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val jsonBody = JsonObject().apply {
                addProperty("text", text)
            }
            val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
            val request = Request.Builder()
                .url("$baseUrl/api/polish")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                var jsonObj: JsonObject? = null
                var errStr: String? = null
                if (bodyString.isNotBlank()) {
                    try {
                        jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                        if (jsonObj != null) {
                            if (jsonObj.has("error")) {
                                errStr = jsonObj.get("error")?.asString
                            } else if (jsonObj.has("detail")) {
                                errStr = jsonObj.get("detail")?.asString
                            }
                        }
                    } catch (e: Exception) {}
                }

                if (response.isSuccessful && jsonObj != null) {
                    if (jsonObj.has("success") && jsonObj.get("success").asBoolean) {
                        val polished = jsonObj.get("polished_text")?.asString ?: text
                        return@withContext PolishResult(true, polishedText = polished)
                    }
                }
                return@withContext PolishResult(false, error = errStr ?: "Polish request failed (HTTP ${response.code})")
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Polish text error: ${e.message}", e)
            return@withContext PolishResult(false, error = e.localizedMessage ?: "Polish request failed")
        }
    }

    suspend fun askAI(prompt: String, language: String = "auto"): AskResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val jsonBody = JsonObject().apply {
                addProperty("message", prompt)
                addProperty("text", prompt)
                addProperty("language", language)
            }
            val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
            val request = Request.Builder()
                .url("$baseUrl/api/ask")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                var jsonObj: JsonObject? = null
                var errStr: String? = null
                if (bodyString.isNotBlank()) {
                    try {
                        jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                        if (jsonObj != null) {
                            if (jsonObj.has("error")) {
                                errStr = jsonObj.get("error")?.asString
                            } else if (jsonObj.has("detail")) {
                                errStr = jsonObj.get("detail")?.asString
                            }
                        }
                    } catch (e: Exception) {}
                }

                if (response.isSuccessful && jsonObj != null) {
                    if (jsonObj.has("success") && jsonObj.get("success").asBoolean) {
                        val answer = jsonObj.get("answer")?.asString ?: ""
                        return@withContext AskResult(true, answer = answer)
                    }
                }
                return@withContext AskResult(false, error = errStr ?: "Ask AI failed (HTTP ${response.code})")
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Ask AI error: ${e.message}", e)
            return@withContext AskResult(false, error = e.localizedMessage ?: "Ask AI connection failed")
        }
    }

    suspend fun getSnippets(): SnippetsResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/snippets")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext SnippetsResult(false, error = "HTTP ${response.code}")
                }
                val jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                if (jsonObj != null && jsonObj.has("snippets") && jsonObj.get("snippets").isJsonArray) {
                    val listType = object : com.google.gson.reflect.TypeToken<List<SnippetItem>>() {}.type
                    val snippets: List<SnippetItem> = gson.fromJson(jsonObj.get("snippets"), listType) ?: emptyList()
                    return@withContext SnippetsResult(true, snippets = snippets)
                }
                return@withContext SnippetsResult(true, snippets = emptyList())
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Get snippets error: ${e.message}", e)
            return@withContext SnippetsResult(false, error = e.localizedMessage ?: "Unable to load snippets")
        }
    }

    suspend fun createSnippet(name: String, text: String, voiceTrigger: String? = null): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val jsonBody = JsonObject().apply {
                addProperty("name", name)
                addProperty("text", text)
                if (voiceTrigger != null) {
                    addProperty("voice_trigger", voiceTrigger)
                }
            }
            val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
            val request = Request.Builder()
                .url("$baseUrl/api/snippets")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Create snippet error: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun deleteSnippet(snippetId: Int): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/snippets/$snippetId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Delete snippet error: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun resetSnippets(): SnippetsResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val emptyBody = RequestBody.create("application/json".toMediaTypeOrNull(), "{}")
            val request = Request.Builder()
                .url("$baseUrl/api/snippets/reset")
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext SnippetsResult(false, error = "HTTP ${response.code}")
                }
                val jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                if (jsonObj != null && jsonObj.has("snippets") && jsonObj.get("snippets").isJsonArray) {
                    val listType = object : com.google.gson.reflect.TypeToken<List<SnippetItem>>() {}.type
                    val snippets: List<SnippetItem> = gson.fromJson(jsonObj.get("snippets"), listType) ?: emptyList()
                    return@withContext SnippetsResult(true, snippets = snippets)
                }
                return@withContext SnippetsResult(true, snippets = emptyList())
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Reset snippets error: ${e.message}", e)
            return@withContext SnippetsResult(false, error = e.localizedMessage ?: "Unable to reset snippets")
        }
    }

    suspend fun searchWeb(query: String): SearchResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val queryClean = query.trim()
        if (queryClean.isEmpty()) {
            return@withContext SearchResult(true, query = "", results = emptyList())
        }
        try {
            val encodedQuery = java.net.URLEncoder.encode(queryClean, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/search?q=$encodedQuery")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext SearchResult(false, query = queryClean, error = "Search HTTP ${response.code}")
                }
                val jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                if (jsonObj != null && jsonObj.has("results") && jsonObj.get("results").isJsonArray) {
                    val listType = object : com.google.gson.reflect.TypeToken<List<SearchItem>>() {}.type
                    val results: List<SearchItem> = gson.fromJson(jsonObj.get("results"), listType) ?: emptyList()
                    return@withContext SearchResult(true, query = queryClean, results = results)
                }
                return@withContext SearchResult(true, query = queryClean, results = emptyList())
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Search web error: ${e.message}", e)
            return@withContext SearchResult(false, query = queryClean, error = e.localizedMessage ?: "Search connection failed")
        }
    }

    suspend fun getHistory(searchQuery: String? = null): HistoryResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val url = if (!searchQuery.isNullOrBlank()) {
                val encodedQ = java.net.URLEncoder.encode(searchQuery.trim(), "UTF-8")
                "$baseUrl/api/voice/history?q=$encodedQ"
            } else {
                "$baseUrl/api/voice/history"
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext HistoryResult(false, error = "History HTTP ${response.code}")
                }
                val jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                if (jsonObj != null && jsonObj.has("sessions") && jsonObj.get("sessions").isJsonArray) {
                    val listType = object : com.google.gson.reflect.TypeToken<List<HistorySession>>() {}.type
                    val sessions: List<HistorySession> = gson.fromJson(jsonObj.get("sessions"), listType) ?: emptyList()
                    return@withContext HistoryResult(true, sessions = sessions)
                }
                return@withContext HistoryResult(true, sessions = emptyList())
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Get history error: ${e.message}", e)
            return@withContext HistoryResult(false, error = e.localizedMessage ?: "History connection failed")
        }
    }

    suspend fun getConversationDetails(sessionId: String): ConversationDetailResult = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/conversation/$sessionId")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext ConversationDetailResult(false, error = "Conversation HTTP ${response.code}")
                }
                val jsonObj = gson.fromJson(bodyString, JsonObject::class.java)
                if (jsonObj != null && jsonObj.has("messages") && jsonObj.get("messages").isJsonArray) {
                    val listType = object : com.google.gson.reflect.TypeToken<List<MessageItem>>() {}.type
                    val messages: List<MessageItem> = gson.fromJson(jsonObj.get("messages"), listType) ?: emptyList()
                    return@withContext ConversationDetailResult(true, session_id = sessionId, messages = messages)
                }
                return@withContext ConversationDetailResult(true, session_id = sessionId, messages = emptyList())
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Get conversation details error: ${e.message}", e)
            return@withContext ConversationDetailResult(false, error = e.localizedMessage ?: "Failed to load conversation")
        }
    }

    suspend fun deleteHistorySession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/voice/history/$sessionId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("NetworkService", "Delete history session error: ${e.message}", e)
            return@withContext false
        }
    }
}
