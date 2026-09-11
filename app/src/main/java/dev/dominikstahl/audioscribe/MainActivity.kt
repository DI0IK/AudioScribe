package dev.dominikstahl.audioscribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dominikstahl.audioscribe.ui.MainViewModel
import dev.dominikstahl.audioscribe.ui.TranscriptionUiState
import dev.dominikstahl.audioscribe.ui.components.ApiKeyDialog
import dev.dominikstahl.audioscribe.ui.components.EmptyHistoryPlaceholder
import dev.dominikstahl.audioscribe.ui.components.GuideStepRow
import dev.dominikstahl.audioscribe.ui.components.HistoryDetailDialog
import dev.dominikstahl.audioscribe.ui.components.ShareProcessingSheet
import dev.dominikstahl.audioscribe.ui.components.TranscriptionHistoryItem
import dev.dominikstahl.audioscribe.ui.theme.AudioScribeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming audio when app is opened via Android Share sheet
        handleIntent(intent)

        setContent {
            AudioScribeTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val shareIntent = Intent(intent).apply {
                setClass(this@MainActivity, ShareReceiverActivity::class.java)
            }
            startActivity(shareIntent)
            finish()
            return
        } else if (Intent.ACTION_VIEW == action) {
            intent.data?.let { viewModel.handleIncomingAudioUri(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val hasKey by viewModel.hasKey.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val transcriptionState by viewModel.transcriptionState.collectAsState()
    val isKeyDialogOpen by viewModel.isKeyDialogOpen.collectAsState()
    val historyRecords by viewModel.historyRecords.collectAsState()
    val selectedHistoryRecord by viewModel.selectedHistoryRecord.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "AudioScribe",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (hasKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Text(
                                text = if (hasKey) "Gemini" else "On-Device",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (hasKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    // Cleaned up: removed help question mark and status checkmark icons as requested
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("main_scroll_content"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Engine Status Banner: Gemini BYOK vs On-Device Fallback
            item {
                if (!hasKey) {
                    Card(
                        onClick = { viewModel.openKeyDialog() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fallback_mode_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "On-Device Speech Fallback Active",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Text(
                                    text = "Audio shared to AudioScribe will be transcribed locally with the device speech engine. Tap to add a Gemini key.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openKeyDialog() }
                            .testTag("active_key_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Gemini BYOK Engine Ready",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = currentModel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Share Target Guide Card (Always visible or toggleable)
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_target_guide_card")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "How to Transcribe Audio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "AudioScribe is configured as a dedicated Android Share Target. Transcribe voice messages or audio files in 3 easy steps:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Step 1
                        GuideStepRow(
                            stepNumber = "1",
                            title = "Select audio in any app",
                            description = "In WhatsApp, Telegram, Voice Memos, Files, or Recorder, tap and hold an audio file or voice message."
                        )

                        // Step 2
                        GuideStepRow(
                            stepNumber = "2",
                            title = "Tap 'Share'",
                            description = "Choose the Android Share action (or Forward → Share in messaging apps)."
                        )

                        // Step 3
                        GuideStepRow(
                            stepNumber = "3",
                            title = "Choose 'AudioScribe'",
                            description = "Select AudioScribe to transcribe verbatim with Gemini or the on-device speech engine fallback!"
                        )
                    }
                }
            }

            // History Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Transcriptions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // History Records List or Empty State
            if (historyRecords.isEmpty()) {
                item {
                    EmptyHistoryPlaceholder()
                }
            } else {
                items(historyRecords, key = { it.id }) { record ->
                    TranscriptionHistoryItem(
                        record = record,
                        onClick = { viewModel.selectHistoryRecord(record) },
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AudioScribe Transcript", record.transcript)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Transcript copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Transcript: ${record.title}")
                                putExtra(Intent.EXTRA_TEXT, record.transcript)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share Transcript via...")
                            )
                        },
                        onSpeak = {
                            viewModel.speakTranscript(record.transcript)
                        }
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet for incoming audio / processing / results
    if (transcriptionState !is TranscriptionUiState.Idle) {
        ShareProcessingSheet(
            state = transcriptionState,
            audioPlayer = viewModel.audioPlayer,
            speechSynthesizer = viewModel.speechSynthesizer,
            onStartTranscription = { metadata ->
                viewModel.startTranscriptionForMetadata(metadata)
            },
            onCancel = { viewModel.cancelTranscription() },
            onDismiss = { viewModel.dismissResult() },
            onOpenKeyDialog = { viewModel.openKeyDialog() },
            onRetry = {
                val current = transcriptionState
                if (current is TranscriptionUiState.Error && current.metadata != null) {
                    viewModel.startTranscriptionForMetadata(current.metadata, forceFallback = false)
                }
            },
            onRetryFallback = {
                val current = transcriptionState
                if (current is TranscriptionUiState.Error && current.metadata != null) {
                    viewModel.startTranscriptionForMetadata(current.metadata, forceFallback = true)
                } else if (current is TranscriptionUiState.KeyRequired) {
                    viewModel.startTranscriptionForMetadata(current.metadata, forceFallback = true)
                }
            }
        )
    }

    // Settings / API Key Dialog
    if (isKeyDialogOpen) {
        ApiKeyDialog(
            currentKey = viewModel.secureKeyManager.getApiKey(),
            currentModel = currentModel,
            workspaceKey = viewModel.secureKeyManager.getWorkspaceKey(),
            onDismiss = { viewModel.closeKeyDialog() },
            onSaveAndValidate = { key, callback ->
                viewModel.validateAndSaveKey(key, callback)
            },
            onDeleteKey = { viewModel.deleteKey() },
            onModelChange = { model -> viewModel.setModel(model) }
        )
    }

    // History Detail Dialog
    selectedHistoryRecord?.let { record ->
        HistoryDetailDialog(
            record = record,
            audioPlayer = viewModel.audioPlayer,
            speechSynthesizer = viewModel.speechSynthesizer,
            onDismiss = { viewModel.selectHistoryRecord(null) },
            onDelete = { viewModel.deleteTranscriptionRecord(record) }
        )
    }
}

