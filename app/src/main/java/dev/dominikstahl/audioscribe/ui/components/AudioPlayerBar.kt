package dev.dominikstahl.audioscribe.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dominikstahl.audioscribe.data.model.AudioTranscriptionData
import dev.dominikstahl.audioscribe.util.AudioPlayerManager
import java.io.File

@Composable
fun AudioPlayerBar(
    audioFile: File?,
    audioPlayer: AudioPlayerManager,
    modifier: Modifier = Modifier,
    totalDurationFallbackMs: Long = 0L
) {
    val playbackState by audioPlayer.playbackState.collectAsState()
    val isCurrentFile = audioFile != null && playbackState.playingFilePath == audioFile.absolutePath
    val isPlaying = isCurrentFile && playbackState.isPlaying

    val totalDurationMs = remember(isCurrentFile, playbackState.durationMs, totalDurationFallbackMs) {
        if (isCurrentFile && playbackState.durationMs > 0) {
            playbackState.durationMs.toLong()
        } else if (totalDurationFallbackMs > 0) {
            totalDurationFallbackMs
        } else {
            0L
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val currentPositionMs = if (isDragging) {
        dragPositionMs.toLong()
    } else if (isCurrentFile) {
        playbackState.currentPositionMs.toLong()
    } else {
        0L
    }

    val sliderMax = if (totalDurationMs > 0) totalDurationMs.toFloat() else 1000f
    val currentSliderValue = currentPositionMs.toFloat().coerceIn(0f, sliderMax)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_player_bar"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play / Pause main button
                FilledIconButton(
                    onClick = {
                        if (audioFile != null) {
                            audioPlayer.playOrPause(audioFile)
                        }
                    },
                    enabled = audioFile != null && audioFile.exists(),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("audio_player_play_pause_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Quick Skip -10s
                IconButton(
                    onClick = {
                        if (audioFile != null) {
                            val newPos = (currentPositionMs - 10_000L).coerceAtLeast(0L).toInt()
                            audioPlayer.seekTo(newPos, audioFile)
                        }
                    },
                    enabled = audioFile != null && audioFile.exists(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Quick Skip +10s
                IconButton(
                    onClick = {
                        if (audioFile != null) {
                            val newPos = (currentPositionMs + 10_000L).coerceAtMost(sliderMax.toLong()).toInt()
                            audioPlayer.seekTo(newPos, audioFile)
                        }
                    },
                    enabled = audioFile != null && audioFile.exists(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Interactive Progress Slider
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    Slider(
                        value = currentSliderValue,
                        onValueChange = { newValue ->
                            isDragging = true
                            dragPositionMs = newValue
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            if (audioFile != null) {
                                audioPlayer.seekTo(dragPositionMs.toInt(), audioFile)
                            }
                        },
                        valueRange = 0f..sliderMax,
                        enabled = audioFile != null && audioFile.exists(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .testTag("audio_player_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    )

                    // Timestamps Row: Current / Total
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = AudioTranscriptionData.formatDuration(currentPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Text(
                            text = AudioTranscriptionData.formatDuration(totalDurationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
