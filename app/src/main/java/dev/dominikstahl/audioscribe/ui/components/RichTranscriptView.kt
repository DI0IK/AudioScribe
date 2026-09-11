package dev.dominikstahl.audioscribe.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dominikstahl.audioscribe.data.model.AudioTranscriptionData
import dev.dominikstahl.audioscribe.data.model.TranscriptSegment
import dev.dominikstahl.audioscribe.util.AudioPlayerManager
import java.io.File

private enum class TranscriptDisplayMode {
    DIARIZED,
    PLAIN_TEXT
}

@Composable
fun RichTranscriptView(
    transcript: String,
    structuredDataJson: String?,
    audioFile: File?,
    audioPlayer: AudioPlayerManager,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 280.dp
) {
    val structuredData = remember(structuredDataJson) {
        AudioTranscriptionData.fromJson(structuredDataJson)
    }

    val hasSegments = structuredData != null && structuredData.segments.isNotEmpty()
    var displayMode by remember(hasSegments) {
        mutableStateOf(if (hasSegments) TranscriptDisplayMode.DIARIZED else TranscriptDisplayMode.PLAIN_TEXT)
    }

    val playbackState by audioPlayer.playbackState.collectAsState()
    val isCurrentFile = audioFile != null && playbackState.playingFilePath == audioFile.absolutePath

    val wordCount = remember(transcript) {
        transcript.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode Selector & Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasSegments) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = displayMode == TranscriptDisplayMode.DIARIZED,
                        onClick = { displayMode = TranscriptDisplayMode.DIARIZED },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("Speakers (${structuredData?.segments?.size ?: 0})", fontSize = 12.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = displayMode == TranscriptDisplayMode.PLAIN_TEXT,
                        onClick = { displayMode = TranscriptDisplayMode.PLAIN_TEXT },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("Prose", fontSize = 12.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            } else {
                Text(
                    text = "Verbatim Transcript",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "$wordCount words",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Main Content Container
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = maxHeight),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            AnimatedContent(
                targetState = displayMode,
                label = "TranscriptModeSwitch"
            ) { mode ->
                if (mode == TranscriptDisplayMode.DIARIZED && structuredData != null) {
                    DiarizedSegmentsList(
                        segments = structuredData.segments,
                        audioFile = audioFile,
                        currentPositionMs = if (isCurrentFile) playbackState.currentPositionMs else -1,
                        isPlaying = isCurrentFile && playbackState.isPlaying,
                        onSeek = { seekMs ->
                            audioPlayer.seekTo(seekMs.toInt(), audioFile)
                        }
                    )
                } else {
                    PlainTranscriptTextBox(transcript = transcript)
                }
            }
        }

        // Action Toolbar (e.g. Copy options)
        if (hasSegments) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = {
                        val formattedWithSpeakers = structuredData?.segments?.joinToString("\n\n") { seg ->
                            val spk = AudioTranscriptionData.formatSpeaker(seg.speakerLabel)
                            val time = "[${AudioTranscriptionData.formatDuration(seg.startOffsetMs)} - ${AudioTranscriptionData.formatDuration(seg.endOffsetMs)}]"
                            "$spk $time:\n${seg.text}"
                        } ?: transcript
                        onCopyText(formattedWithSpeakers)
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy with Speakers", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DiarizedSegmentsList(
    segments: List<TranscriptSegment>,
    audioFile: File?,
    currentPositionMs: Int,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        segments.forEach { segment ->
            val isActive = currentPositionMs >= segment.startOffsetMs && currentPositionMs <= segment.endOffsetMs
            val animatedBorderColor by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "SegmentBorderAnim"
            )
            val animatedBgColor by animateColorAsState(
                targetValue = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                label = "SegmentBgAnim"
            )

            val speakerColor = getSpeakerAccentColor(segment.speakerLabel)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = animatedBgColor,
                border = BorderStroke(1.dp, animatedBorderColor)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Speaker Header & Timecode Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(speakerColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = speakerColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = AudioTranscriptionData.formatSpeaker(segment.speakerLabel),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = speakerColor
                            )
                        }

                        // Clickable seek time badge
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onSeek(segment.startOffsetMs) },
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Jump audio to ${AudioTranscriptionData.formatDuration(segment.startOffsetMs)}",
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = AudioTranscriptionData.formatDuration(segment.startOffsetMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Segment Transcript Text
                    SelectionContainer {
                        Text(
                            text = segment.text,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainTranscriptTextBox(transcript: String) {
    val scrollState = rememberScrollState()

    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(14.dp)
        ) {
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun getSpeakerAccentColor(speakerLabel: String?): Color {
    val index = when {
        speakerLabel.isNullOrBlank() -> 0
        speakerLabel.contains("1") -> 0
        speakerLabel.contains("2") -> 1
        speakerLabel.contains("3") -> 2
        speakerLabel.contains("4") -> 3
        speakerLabel.contains("5") -> 4
        speakerLabel.contains("6") -> 5
        else -> speakerLabel.hashCode().let { kotlin.math.abs(it) % 6 }
    }

    return when (index) {
        0 -> Color(0xFF3F51B5) // Indigo
        1 -> Color(0xFF009688) // Teal
        2 -> Color(0xFFE65100) // Deep Orange
        3 -> Color(0xFF673AB7) // Deep Purple
        4 -> Color(0xFF0288D1) // Light Blue
        else -> Color(0xFF43A047) // Green
    }
}
