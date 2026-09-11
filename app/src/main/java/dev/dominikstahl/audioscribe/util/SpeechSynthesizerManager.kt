package dev.dominikstahl.audioscribe.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * On-device Speech Synthesizer manager using Android's native TextToSpeech API.
 * Provides on-device text-to-speech synthesis to read out transcripts.
 */
class SpeechSynthesizerManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "SpeechSynthesizer"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    Log.w(TAG, "TTS utterance error: $errorCode")
                }
            })
        } else {
            Log.w(TAG, "Failed to initialize TextToSpeech engine")
        }
    }

    /**
     * Synthesizes and speaks the given text aloud using the on-device speech synthesizer.
     */
    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "Speech synthesizer not ready")
            return
        }

        if (_isSpeaking.value) {
            stop()
            return
        }

        val utteranceId = "utterance_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Stops any currently ongoing speech synthesis.
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        _isSpeaking.value = false
    }

    /**
     * Releases system speech synthesizer resources.
     */
    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        }
        tts = null
        isInitialized = false
    }
}
