package dev.dominikstahl.audioscribe.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speech recognition service that transcribes audio files on Android.
 * Acts as the fallback mode when no Gemini API key is configured.
 */
class OnDeviceSpeechService(private val context: Context) {

    companion object {
        private const val TAG = "OnDeviceSpeechService"
        const val MODEL_NAME = "On-Device Speech Engine"
        private const val ATTEMPT_TIMEOUT_MS = 25000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks whether speech recognition services are installed and available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    private sealed class AttemptResult {
        data class Success(val transcript: String) : AttemptResult()
        data class Error(val errorCode: Int, val message: String, val partialText: String?) : AttemptResult()
    }

    /**
     * Transcribes an audio file using speech recognition.
     * Automatically handles Error 12 (offline language pack missing) by falling back to the system speech recognizer.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(Exception("Audio file is missing or empty"))
        }

        val hasOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        // Attempt 1: Try on-device recognition if available
        if (hasOnDevice) {
            onProgress("Initializing on-device speech engine...")
            val result1 = executeAttempt(
                audioFile = audioFile,
                useOnDevice = true,
                languageTag = Locale.getDefault().toLanguageTag(),
                onProgress = onProgress
            )

            when (result1) {
                is AttemptResult.Success -> return@withContext Result.success(result1.transcript)
                is AttemptResult.Error -> {
                    // Check if error is 12 (ERROR_LANGUAGE_NOT_SUPPORTED) or 13 (ERROR_LANGUAGE_UNAVAILABLE)
                    if (result1.errorCode == 12 || result1.errorCode == 13 || result1.errorCode == SpeechRecognizer.ERROR_CLIENT) {
                        Log.w(TAG, "On-device recognition failed with code ${result1.errorCode}. Falling back to system speech recognizer...")
                        onProgress("On-device model unavailable (error ${result1.errorCode}), switching to system speech recognizer...")
                    } else if (result1.partialText != null && result1.partialText.isNotBlank()) {
                        return@withContext Result.success(result1.partialText)
                    } else {
                        Log.w(TAG, "On-device failed (${result1.message}), falling back to system speech recognizer...")
                    }
                }
            }
        }

        // Attempt 2: System SpeechRecognizer with default locale
        onProgress("Connecting to system speech recognizer...")
        val result2 = executeAttempt(
            audioFile = audioFile,
            useOnDevice = false,
            languageTag = Locale.getDefault().toLanguageTag(),
            onProgress = onProgress
        )

        when (result2) {
            is AttemptResult.Success -> return@withContext Result.success(result2.transcript)
            is AttemptResult.Error -> {
                if (result2.partialText != null && result2.partialText.isNotBlank()) {
                    return@withContext Result.success(result2.partialText)
                }

                // If error 12 occurred with specific locale, attempt without specifying language
                if (result2.errorCode == 12 || result2.errorCode == 13) {
                    Log.w(TAG, "Locale not supported. Retrying with default system language...")
                    onProgress("Retrying with default speech language...")
                    val result3 = executeAttempt(
                        audioFile = audioFile,
                        useOnDevice = false,
                        languageTag = null,
                        onProgress = onProgress
                    )
                    when (result3) {
                        is AttemptResult.Success -> return@withContext Result.success(result3.transcript)
                        is AttemptResult.Error -> {
                            if (result3.partialText != null && result3.partialText.isNotBlank()) {
                                return@withContext Result.success(result3.partialText)
                            }
                        }
                    }
                }

                val finalErrorMsg = if (result2.errorCode == 12 || result2.errorCode == 13) {
                    "Speech recognition error ${result2.errorCode}: Offline voice pack is not installed on this device. Please enter a Gemini API key in Settings (Gemini 3.8 Flash) for fast, reliable transcription."
                } else {
                    "Speech recognition error: ${result2.message}. Configuring a Gemini API key in Settings is recommended."
                }
                return@withContext Result.failure(Exception(finalErrorMsg))
            }
        }
    }

    private suspend fun executeAttempt(
        audioFile: File,
        useOnDevice: Boolean,
        languageTag: String?,
        onProgress: (String) -> Unit
    ): AttemptResult {
        val result = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            suspendCancellableCoroutine<AttemptResult> { continuation ->
                mainHandler.post {
                    var recognizer: SpeechRecognizer? = null
                    var pfd: ParcelFileDescriptor? = null

                    fun cleanup() {
                        try {
                            recognizer?.destroy()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error destroying recognizer", e)
                        }
                        try {
                            pfd?.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing pfd", e)
                        }
                    }

                    continuation.invokeOnCancellation {
                        mainHandler.post { cleanup() }
                    }

                    try {
                        recognizer = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }

                        if (recognizer == null) {
                            cleanup()
                            continuation.resume(
                                AttemptResult.Error(
                                    errorCode = -1,
                                    message = "Could not instantiate SpeechRecognizer",
                                    partialText = null
                                )
                            )
                            return@post
                        }

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            if (!languageTag.isNullOrBlank()) {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                            }
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                            // On Android 13+ (API 33), provide the audio source directly
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                try {
                                    pfd = ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)
                                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not attach EXTRA_AUDIO_SOURCE ParcelFileDescriptor", e)
                                }
                            }
                        }

                        recognizer.setRecognitionListener(object : RecognitionListener {
                            private var partialText: String? = null

                            override fun onReadyForSpeech(params: Bundle?) {
                                onProgress("Analyzing audio...")
                            }

                            override fun onBeginningOfSpeech() {
                                onProgress("Transcribing audio...")
                            }

                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                onProgress("Finalizing transcription...")
                            }

                            override fun onError(error: Int) {
                                val message = getSpeechErrorMessage(error)
                                Log.w(TAG, "SpeechRecognizer error: $error ($message)")
                                cleanup()

                                if (partialText != null && partialText!!.isNotBlank()) {
                                    continuation.resume(AttemptResult.Success(partialText!!))
                                } else if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                                    continuation.resume(
                                        AttemptResult.Success("[Audio analyzed: No recognizable spoken words detected]")
                                    )
                                } else {
                                    continuation.resume(
                                        AttemptResult.Error(
                                            errorCode = error,
                                            message = message,
                                            partialText = partialText
                                        )
                                    )
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                cleanup()
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull()?.trim()
                                if (!text.isNullOrBlank()) {
                                    continuation.resume(AttemptResult.Success(text))
                                } else if (partialText != null && partialText!!.isNotBlank()) {
                                    continuation.resume(AttemptResult.Success(partialText!!))
                                } else {
                                    continuation.resume(
                                        AttemptResult.Success("[Audio analyzed: No recognizable spoken words detected]")
                                    )
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val candidate = matches?.firstOrNull()?.trim()
                                if (!candidate.isNullOrBlank()) {
                                    partialText = candidate
                                    onProgress("Transcribing: \"$candidate\"")
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })

                        recognizer.startListening(intent)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start speech recognition", e)
                        cleanup()
                        continuation.resume(
                            AttemptResult.Error(
                                errorCode = -1,
                                message = e.localizedMessage ?: "Failed to start speech recognizer",
                                partialText = null
                            )
                        )
                    }
                }
            }
        }

        return result ?: AttemptResult.Error(
            errorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            message = "Speech recognition operation timed out",
            partialText = null
        )
    }

    private fun getSpeechErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording/reading error"
            SpeechRecognizer.ERROR_CLIENT -> "Client application error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network connection error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network operation timed out"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognition match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service is currently busy"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition server disconnected"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected within timeout"
            12 -> "Language not supported or offline voice pack not downloaded (Error 12)"
            13 -> "Language unavailable offline (Error 13)"
            14 -> "Speech recognition server error (Error 14)"
            else -> "Speech recognition error code: $errorCode"
        }
    }
}
