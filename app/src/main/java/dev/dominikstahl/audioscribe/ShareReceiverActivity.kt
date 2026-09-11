package dev.dominikstahl.audioscribe

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.dominikstahl.audioscribe.ui.MainViewModel
import dev.dominikstahl.audioscribe.ui.TranscriptionUiState
import dev.dominikstahl.audioscribe.ui.components.ApiKeyDialog
import dev.dominikstahl.audioscribe.ui.components.ShareProcessingSheet
import dev.dominikstahl.audioscribe.ui.theme.AudioScribeTheme

/**
 * Lightweight translucent activity handling the Android Share Target (ACTION_SEND).
 * It presents ONLY the slide-in bottom sheet over the sharing application without
 * opening or bringing the full AudioScribe main application forward.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawableResource(android.R.color.transparent)

        handleIntent(intent)

        setContent {
            AudioScribeTheme {
                ShareReceiverScreen(
                    viewModel = viewModel,
                    onFinish = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (streamUri != null) {
                viewModel.handleIncomingAudioUri(streamUri)
            } else {
                intent.data?.let { viewModel.handleIncomingAudioUri(it) } ?: finish()
            }
        } else if (Intent.ACTION_VIEW == action && intent.data != null) {
            viewModel.handleIncomingAudioUri(intent.data!!)
        } else {
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReceiverScreen(
    viewModel: MainViewModel,
    onFinish: () -> Unit
) {
    val transcriptionState by viewModel.transcriptionState.collectAsState()
    val isKeyDialogOpen by viewModel.isKeyDialogOpen.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        if (transcriptionState !is TranscriptionUiState.Idle) {
            ShareProcessingSheet(
                state = transcriptionState,
                audioPlayer = viewModel.audioPlayer,
                speechSynthesizer = viewModel.speechSynthesizer,
                onStartTranscription = { metadata ->
                    viewModel.startTranscriptionForMetadata(metadata)
                },
                onCancel = {
                    viewModel.cancelTranscription()
                    onFinish()
                },
                onDismiss = {
                    viewModel.dismissResult()
                    onFinish()
                },
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
    }
}
