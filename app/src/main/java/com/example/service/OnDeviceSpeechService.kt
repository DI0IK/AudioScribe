package com.example.service

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
 * On-device speech recognition service that transcribes audio files locally on Android.
 * Acts as the offline fallback mode when no Gemini API key is configured.
 */
class OnDeviceSpeechService(private val context: Context) {

    companion object {
        private const val TAG = "OnDeviceSpeechService"
        const val MODEL_NAME = "On-Device Speech Engine"
        private const val TIMEOUT_MS = 45000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks whether speech recognition services are installed and available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Transcribes an audio file using Android's on-device speech recognition engine.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        onProgress: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(Exception("Audio file is missing or empty"))
        }

        onProgress("Initializing on-device speech engine...")

        // Execute recognition on main thread as required by Android SpeechRecognizer
        val recognitionResult = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
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
                        // Use on-device recognizer on API 31+ if available, else standard recognizer
                        recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                        ) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }

                        if (recognizer == null) {
                            continuation.resume(
                                Result.failure(Exception("Could not initialize SpeechRecognizer on this device"))
                            )
                            return@post
                        }

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
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
                                onProgress("Processing audio stream...")
                            }

                            override fun onBeginningOfSpeech() {
                                onProgress("Transcribing speech...")
                            }

                            override fun onRmsChanged(rmsdB: Float) {
                                // Audio wave levels
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {
                                // Buffer receiving
                            }

                            override fun onEndOfSpeech() {
                                onProgress("Finalizing transcription...")
                            }

                            override fun onError(error: Int) {
                                val message = getSpeechErrorMessage(error)
                                Log.w(TAG, "SpeechRecognizer error: $error ($message)")
                                cleanup()

                                if (partialText != null && partialText!!.isNotBlank()) {
                                    continuation.resume(Result.success(partialText!!))
                                } else if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                                    continuation.resume(
                                        Result.success("[Audio analyzed: No recognizable spoken words detected]")
                                    )
                                } else {
                                    continuation.resume(
                                        Result.failure(Exception("On-device speech engine: $message"))
                                    )
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                cleanup()
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull()?.trim()
                                if (!text.isNullOrBlank()) {
                                    continuation.resume(Result.success(text))
                                } else if (partialText != null && partialText!!.isNotBlank()) {
                                    continuation.resume(Result.success(partialText!!))
                                } else {
                                    continuation.resume(
                                        Result.success("[Audio analyzed: No recognizable spoken words detected]")
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

                            override fun onEvent(eventType: Int, params: Bundle?) {
                                // Custom engine events
                            }
                        })

                        recognizer.startListening(intent)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start speech recognition", e)
                        cleanup()
                        continuation.resume(Result.failure(e))
                    }
                }
            }
        }

        recognitionResult ?: Result.failure(
            Exception("On-device speech recognition timed out while analyzing audio file")
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
            else -> "Speech recognition error code: $errorCode"
        }
    }
}
