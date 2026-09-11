package dev.dominikstahl.audioscribe.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val playingFilePath: String? = null
)

class AudioPlayerManager(private val context: Context) {

    private val TAG = "AudioPlayerManager"
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun playOrPause(file: File) {
        val currentPath = _playbackState.value.playingFilePath
        if (currentPath == file.absolutePath && mediaPlayer != null) {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                progressJob?.cancel()
            } else {
                mediaPlayer?.start()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
                startProgressTracker()
            }
            return
        }

        stop()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                start()
                setOnCompletionListener {
                    _playbackState.value = PlaybackState(
                        isPlaying = false,
                        currentPositionMs = duration,
                        durationMs = duration,
                        playingFilePath = file.absolutePath
                    )
                    progressJob?.cancel()
                }
            }

            val duration = mediaPlayer?.duration ?: 0
            _playbackState.value = PlaybackState(
                isPlaying = true,
                currentPositionMs = 0,
                durationMs = duration,
                playingFilePath = file.absolutePath
            )
            startProgressTracker()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${file.absolutePath}", e)
            stop()
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = mp.currentPosition,
                        durationMs = mp.duration
                    )
                }
                delay(200)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaPlayer = null
        _playbackState.value = PlaybackState()
    }
}
