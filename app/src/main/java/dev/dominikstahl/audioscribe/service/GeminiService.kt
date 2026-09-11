package dev.dominikstahl.audioscribe.service

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class GeminiTranscriptionResponse(
    val transcript: String,
    val structuredDataJson: String? = null
)

class GeminiService {

    companion object {
        private const val TAG = "GeminiService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        const val SYSTEM_INSTRUCTION_AUDIO =
            "You are a professional, exact audio transcription engine. Transcribe the spoken words in the provided audio file verbatim. Do not summarize, edit, translate, or correct grammatical errors. Do not include conversational remarks, metadata, speaker analysis, or markdown preambles. Output ONLY the raw transcription. If a portion is unintelligible, mark it as [unintelligible]."
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Validates a Gemini API key using a lightweight GET models query.
     */
    suspend fun validateApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(Exception("API key cannot be empty"))
        }

        try {
            val url = "$BASE_URL/models?key=$trimmedKey"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Result.success("API key successfully validated!")
            } else {
                val errorMsg = parseErrorMessage(response.code, responseBody)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Validation request failed", e)
            Result.failure(Exception("Connection failed: ${e.localizedMessage ?: "Unknown network error"}"))
        }
    }

    /**
     * Transcribes audio using Gemini API with temperature 0.0 and verbatim system instruction.
     * Supports verbatim mode, speaker diarization, and word timestamps when using transcribe models.
     */
    suspend fun transcribeAudio(
        apiKey: String,
        model: String,
        audioFile: File,
        mimeType: String,
        onProgress: (String) -> Unit = {}
    ): Result<GeminiTranscriptionResponse> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(Exception("Gemini API key is not configured. Please enter your API key in Settings."))
        }

        try {
            onProgress("Encoding audio bytes...")
            val audioBytes = audioFile.readBytes()
            val base64Data = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            var activeModel = model
            val isTranscribeModel = activeModel.contains("transcribe", ignoreCase = true)

            onProgress("Building transcription payload...")
            val jsonPayload = buildTranscriptionJson(base64Data, mimeType, isTranscribeModel)

            onProgress("Connecting to Gemini ($activeModel)...")
            val result = executeGenerateContent(trimmedKey, activeModel, jsonPayload)
            if (result.isFailure && result.exceptionOrNull()?.message?.contains("404") == true && activeModel != "gemini-3.8-flash") {
                // If requested model was 404, fallback to gemini-3.8-flash
                onProgress("Retrying with gemini-3.8-flash...")
                activeModel = "gemini-3.8-flash"
                val fallbackPayload = buildTranscriptionJson(base64Data, mimeType, isTranscribeModel = false)
                return@withContext executeGenerateContent(trimmedKey, activeModel, fallbackPayload)
            }

            result
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Gemini transcription timed out", e)
            Result.failure(Exception("Transcription timed out. Gemini 3.8 Flash is recommended for fastest response."))
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            val friendlyMsg = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                "Transcription request timed out. Please try Gemini 3.8 Flash."
            } else {
                e.localizedMessage ?: "Unknown transcription error"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    private fun executeGenerateContent(
        apiKey: String,
        model: String,
        jsonPayload: String
    ): Result<GeminiTranscriptionResponse> {
        val url = "$BASE_URL/models/$model:generateContent?key=$apiKey"
        val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return if (response.isSuccessful) {
            try {
                val json = JSONObject(responseBody)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val sb = StringBuilder()
                    var structuredDataJson: String? = null

                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val text = part.optString("text")
                            if (text.isNotEmpty()) {
                                sb.append(text)
                            }
                            val audioTrans = part.optJSONObject("audio_transcription")
                                ?: part.optJSONObject("audioTranscription")
                            if (audioTrans != null && structuredDataJson == null) {
                                structuredDataJson = audioTrans.toString()
                            }
                        }
                    }

                    if (structuredDataJson == null) {
                        val candidateTrans = candidate.optJSONObject("audio_transcription")
                            ?: candidate.optJSONObject("audioTranscription")
                        if (candidateTrans != null) {
                            structuredDataJson = candidateTrans.toString()
                        }
                    }

                    if (structuredDataJson == null) {
                        val rootTrans = json.optJSONObject("audio_transcription")
                            ?: json.optJSONObject("audioTranscription")
                        if (rootTrans != null) {
                            structuredDataJson = rootTrans.toString()
                        }
                    }

                    val transcript = sb.toString().trim()
                    if (transcript.isNotEmpty()) {
                        Result.success(
                            GeminiTranscriptionResponse(
                                transcript = transcript,
                                structuredDataJson = structuredDataJson
                            )
                        )
                    } else {
                        Result.failure(Exception("Transcription returned empty response from model"))
                    }
                } else {
                    Result.failure(Exception("No transcription candidates returned by Gemini"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse transcription response: $responseBody", e)
                Result.failure(Exception("Failed to parse Gemini response: ${e.message}"))
            }
        } else {
            val errorMsg = parseErrorMessage(response.code, responseBody)
            Result.failure(Exception(errorMsg))
        }
    }

    private fun buildTranscriptionJson(
        base64Audio: String,
        mimeType: String,
        isTranscribeModel: Boolean = false
    ): String {
        val root = JSONObject()

        // System Instruction (verbatim as mandated)
        val systemInstruction = JSONObject()
        val sysParts = JSONArray()
        val sysPart = JSONObject()
        sysPart.put("text", SYSTEM_INSTRUCTION_AUDIO)
        sysParts.put(sysPart)
        systemInstruction.put("parts", sysParts)
        root.put("systemInstruction", systemInstruction)

        // Contents
        val contents = JSONArray()
        val content = JSONObject()
        val parts = JSONArray()

        // Part 1: Inline audio
        val audioPart = JSONObject()
        val inlineData = JSONObject()
        inlineData.put("mimeType", mimeType)
        inlineData.put("data", base64Audio)
        audioPart.put("inlineData", inlineData)
        parts.put(audioPart)

        // Part 2: Explicit prompt
        val textPart = JSONObject()
        textPart.put("text", "Transcribe the spoken words in the provided audio file verbatim.")
        parts.put(textPart)

        content.put("parts", parts)
        contents.put(content)
        root.put("contents", contents)

        // Generation Config: temperature 0.0 (strictly deterministic)
        val config = JSONObject()
        config.put("temperature", 0.0)

        if (isTranscribeModel) {
            val transcriptionConfig = JSONObject().apply {
                val mode = JSONObject().apply {
                    put("type", "verbatim")
                    put("diarization_mode", "speaker")
                    val granularities = JSONArray().apply { put("word") }
                    put("timestamp_granularities", granularities)
                }
                put("mode", mode)
            }
            config.put("transcription_config", transcriptionConfig)

            // Also support camelCase for robustness across client parser versions
            val transcriptionConfigCamel = JSONObject().apply {
                val mode = JSONObject().apply {
                    put("type", "verbatim")
                    put("diarizationMode", "speaker")
                    val granularities = JSONArray().apply { put("word") }
                    put("timestampGranularities", granularities)
                }
                put("mode", mode)
            }
            config.put("transcriptionConfig", transcriptionConfigCamel)
            root.put("generation_config", config)
        }

        root.put("generationConfig", config)

        return root.toString()
    }

    private fun parseErrorMessage(statusCode: Int, body: String): String {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val message = error?.optString("message")
            if (!message.isNullOrBlank()) {
                "Gemini API Error ($statusCode): $message"
            } else {
                "HTTP $statusCode: $body"
            }
        } catch (e: Exception) {
            "HTTP Error $statusCode"
        }
    }
}
