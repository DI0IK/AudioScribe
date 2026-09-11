package dev.dominikstahl.audioscribe.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dominikstahl.audioscribe.data.model.TranscriptionRecord
import dev.dominikstahl.audioscribe.util.AudioFileManager
import dev.dominikstahl.audioscribe.util.AudioPlayerManager
import dev.dominikstahl.audioscribe.util.SpeechSynthesizerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryDetailDialog(
    record: TranscriptionRecord,
    audioPlayer: AudioPlayerManager,
    speechSynthesizer: SpeechSynthesizerManager,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val playbackState by audioPlayer.playbackState.collectAsState()
    val isSpeaking by speechSynthesizer.isSpeaking.collectAsState()
    val audioFile = record.localFilePath?.let { File(it) }?.takeIf { it.exists() }

    val formattedDate = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        .format(Date(record.timestamp))

    AlertDialog(
        onDismissRequest = {
            audioPlayer.stop()
            speechSynthesizer.stop()
            onDismiss()
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "$formattedDate • ${AudioFileManager.formatFileSize(record.fileSize)} • ${record.modelUsed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Read-aloud Speak button if needed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            audioPlayer.stop()
                            if (isSpeaking) {
                                speechSynthesizer.stop()
                            } else {
                                speechSynthesizer.speak(record.transcript)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isSpeaking) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isSpeaking) "Stop Speech" else "Read Aloud", fontSize = 12.sp)
                    }
                }

                // Audio Player with Seek / Scrubbing if local audio file is preserved
                if (audioFile != null) {
                    AudioPlayerBar(
                        audioFile = audioFile,
                        audioPlayer = audioPlayer,
                        totalDurationFallbackMs = record.durationMs
                    )
                }

                // Rich Transcript View (Diarization, timestamps seek, Prose toggle)
                RichTranscriptView(
                    transcript = record.transcript,
                    structuredDataJson = record.structuredDataJson,
                    audioFile = audioFile,
                    audioPlayer = audioPlayer,
                    onCopyText = { textToCopy ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("AudioScribe Transcript", textToCopy)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Transcript copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("history_transcript_text")
                )

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AudioScribe Transcript", record.transcript)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Transcript copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_history_transcript_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }

                    FilledTonalButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Transcript: ${record.title}")
                                putExtra(Intent.EXTRA_TEXT, record.transcript)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share Transcript via...")
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("share_history_transcript_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = {
                    audioPlayer.stop()
                    speechSynthesizer.stop()
                    onDismiss()
                }
            ) {
                Text("Close")
            }
        },
        dismissButton = {
            IconButton(
                onClick = {
                    audioPlayer.stop()
                    speechSynthesizer.stop()
                    onDelete()
                },
                modifier = Modifier.testTag("delete_history_record_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
