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

class NetworkService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val gson = Gson()

    fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        var url = prefs.getString("server_url", "http://192.168.1.105:8000") ?: "http://192.168.1.105:8000"
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
            ConnectionTestResult(false, "Connection failed: ${e.localizedMessage ?: e.message}. Ensure PC & Phone are on same Wi-Fi network.")
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

                val originalText = jsonObj.get("text")?.asString
                    ?: jsonObj.get("transcription")?.asString
                    ?: jsonObj.get("original_text")?.asString
                    ?: ""

                val detectedLang = jsonObj.get("language")?.asString ?: sourceLanguage

                var finalTranslatedText = jsonObj.get("translation")?.asString
                    ?: jsonObj.get("translated_text")?.asString
                    ?: originalText

                if (isTranslateOn && originalText.isNotBlank() && (finalTranslatedText == originalText || finalTranslatedText.isBlank())) {
                    val translated = translateTextDirect(originalText, detectedLang, targetLanguage)
                    if (!translated.isNullOrBlank()) {
                        finalTranslatedText = translated
                    }
                }

                return@withContext TranscriptionResult(
                    success = true,
                    originalText = originalText,
                    translatedText = finalTranslatedText,
                    language = detectedLang
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
}
