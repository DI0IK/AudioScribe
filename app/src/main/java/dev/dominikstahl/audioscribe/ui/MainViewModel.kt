package dev.dominikstahl.audioscribe.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dominikstahl.audioscribe.data.db.AppDatabase
import dev.dominikstahl.audioscribe.data.model.TranscriptionRecord
import dev.dominikstahl.audioscribe.security.SecureKeyManager
import dev.dominikstahl.audioscribe.service.GeminiService
import dev.dominikstahl.audioscribe.service.OnDeviceSpeechService
import dev.dominikstahl.audioscribe.util.AudioFileManager
import dev.dominikstahl.audioscribe.util.AudioMetadata
import dev.dominikstahl.audioscribe.util.AudioPlayerManager
import dev.dominikstahl.audioscribe.util.SpeechSynthesizerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class TranscriptionUiState {
    data object Idle : TranscriptionUiState()

    data class Ready(
        val metadata: AudioMetadata,
        val isFallback: Boolean = false,
        val modelName: String = ""
    ) : TranscriptionUiState()

    data class KeyRequired(val metadata: AudioMetadata) : TranscriptionUiState()

    data class Preparing(val fileName: String) : TranscriptionUiState()

    data class Processing(
        val metadata: AudioMetadata,
        val statusMessage: String,
        val isFallback: Boolean = false
    ) : TranscriptionUiState()

    data class Completed(
        val metadata: AudioMetadata,
        val transcript: String,
        val recordId: Long,
        val modelUsed: String,
        val isCached: Boolean = false,
        val structuredDataJson: String? = null
    ) : TranscriptionUiState()

    data class Error(
        val metadata: AudioMetadata?,
        val errorMessage: String,
        val canRetryFallback: Boolean = false
    ) : TranscriptionUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val secureKeyManager = SecureKeyManager(context)
    private val geminiService = GeminiService()
    val onDeviceSpeechService = OnDeviceSpeechService(context)
    val speechSynthesizer = SpeechSynthesizerManager(context)
    private val database = AppDatabase.getInstance(context)
    private val dao = database.transcriptionDao()
    val audioPlayer = AudioPlayerManager(context)

    // UI States
    private val _transcriptionState = MutableStateFlow<TranscriptionUiState>(TranscriptionUiState.Idle)
    val transcriptionState: StateFlow<TranscriptionUiState> = _transcriptionState.asStateFlow()

    private val _isKeyDialogOpen = MutableStateFlow(false)
    val isKeyDialogOpen: StateFlow<Boolean> = _isKeyDialogOpen.asStateFlow()

    private val _currentModel = MutableStateFlow(secureKeyManager.getSelectedModel())
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _hasKey = MutableStateFlow(secureKeyManager.hasApiKey())
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _selectedHistoryRecord = MutableStateFlow<TranscriptionRecord?>(null)
    val selectedHistoryRecord: StateFlow<TranscriptionRecord?> = _selectedHistoryRecord.asStateFlow()

    val historyRecords: StateFlow<List<TranscriptionRecord>> = dao.getAllTranscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var transcriptionJob: Job? = null

    init {
        checkKeyStatus()
    }

    fun checkKeyStatus() {
        _hasKey.value = secureKeyManager.hasApiKey()
        _currentModel.value = secureKeyManager.getSelectedModel()
    }

    fun openKeyDialog() {
        _isKeyDialogOpen.value = true
    }

    fun closeKeyDialog() {
        _isKeyDialogOpen.value = false
    }

    fun selectHistoryRecord(record: TranscriptionRecord?) {
        _selectedHistoryRecord.value = record
    }

    /**
     * Primary entry point when audio is received via Android's Share sheet.
     * Automatically checks the local cache first: if previously transcribed,
     * instantly displays the completed transcript without requiring user interaction.
     */
    fun handleIncomingAudioUri(uri: Uri) {
        viewModelScope.launch {
            _transcriptionState.value = TranscriptionUiState.Preparing("Reading shared audio...")
            val result = AudioFileManager.processIncomingAudioUri(context, uri)
            result.onSuccess { metadata ->
                // Auto-switch to cached transcript if identical audio was previously transcribed
                if (metadata.fileHash.isNotBlank()) {
                    val cached = dao.findByAudioHash(metadata.fileHash)
                    if (cached != null) {
                        Log.d("MainViewModel", "Auto-switching to cached transcript for hash: ${metadata.fileHash}")
                        _transcriptionState.value = TranscriptionUiState.Completed(
                            metadata = metadata,
                            transcript = cached.transcript,
                            recordId = cached.id,
                            modelUsed = cached.modelUsed,
                            isCached = true
                        )
                        return@launch
                    }
                }

                val hasKey = secureKeyManager.hasApiKey()
                val model = if (hasKey) secureKeyManager.getSelectedModel() else OnDeviceSpeechService.MODEL_NAME
                _transcriptionState.value = TranscriptionUiState.Ready(
                    metadata = metadata,
                    isFallback = !hasKey,
                    modelName = model
                )
            }.onFailure { error ->
                _transcriptionState.value = TranscriptionUiState.Error(
                    metadata = null,
                    errorMessage = error.localizedMessage ?: "Failed to read shared audio stream"
                )
            }
        }
    }

    fun startTranscriptionForMetadata(metadata: AudioMetadata, forceFallback: Boolean = false, bypassCache: Boolean = false) {
        val useFallback = forceFallback || !secureKeyManager.hasApiKey()
        executeTranscription(metadata, useFallback = useFallback, bypassCache = bypassCache)
    }

    private fun executeTranscription(metadata: AudioMetadata, useFallback: Boolean, bypassCache: Boolean = false) {
        transcriptionJob?.cancel()
        transcriptionJob = viewModelScope.launch {
            // Check cache by SHA-256 audio hash so identical audio is never re-transcribed needlessly
            if (!bypassCache && metadata.fileHash.isNotBlank()) {
                val cached = dao.findByAudioHash(metadata.fileHash)
                if (cached != null) {
                    Log.d("MainViewModel", "Cache hit for audio hash: ${metadata.fileHash}")
                    _transcriptionState.value = TranscriptionUiState.Completed(
                        metadata = metadata,
                        transcript = cached.transcript,
                        recordId = cached.id,
                        modelUsed = cached.modelUsed,
                        isCached = true,
                        structuredDataJson = cached.structuredDataJson
                    )
                    return@launch
                }
            }

            if (useFallback) {
                // On-device speech recognition fallback mode
                _transcriptionState.value = TranscriptionUiState.Processing(
                    metadata = metadata,
                    statusMessage = "Analyzing audio with on-device speech engine...",
                    isFallback = true
                )

                val result = onDeviceSpeechService.transcribeAudio(
                    audioFile = metadata.file,
                    onProgress = { msg ->
                        _transcriptionState.value = TranscriptionUiState.Processing(
                            metadata = metadata,
                            statusMessage = msg,
                            isFallback = true
                        )
                    }
                )

                result.onSuccess { transcript ->
                    saveRecordAndComplete(metadata, transcript, OnDeviceSpeechService.MODEL_NAME, null)
                }.onFailure { error ->
                    _transcriptionState.value = TranscriptionUiState.Error(
                        metadata = metadata,
                        errorMessage = error.localizedMessage ?: "On-device speech processing failed",
                        canRetryFallback = false
                    )
                }

            } else {
                // Gemini Cloud API Mode
                val key = secureKeyManager.getApiKey()
                if (key.isNullOrBlank()) {
                    // If key was removed mid-way, fallback to on-device speech engine
                    executeTranscription(metadata, useFallback = true, bypassCache = bypassCache)
                    return@launch
                }

                _transcriptionState.value = TranscriptionUiState.Processing(
                    metadata = metadata,
                    statusMessage = "Preparing audio for Gemini...",
                    isFallback = false
                )

                val model = secureKeyManager.getSelectedModel()

                val result = geminiService.transcribeAudio(
                    apiKey = key,
                    model = model,
                    audioFile = metadata.file,
                    mimeType = metadata.mimeType,
                    onProgress = { msg ->
                        _transcriptionState.value = TranscriptionUiState.Processing(
                            metadata = metadata,
                            statusMessage = msg,
                            isFallback = false
                        )
                    }
                )

                result.onSuccess { response ->
                    saveRecordAndComplete(metadata, response.transcript, model, response.structuredDataJson)
                }.onFailure { error ->
                    _transcriptionState.value = TranscriptionUiState.Error(
                        metadata = metadata,
                        errorMessage = error.localizedMessage ?: "Gemini transcription error",
                        canRetryFallback = true
                    )
                }
            }
        }
    }

    private suspend fun saveRecordAndComplete(
        metadata: AudioMetadata,
        transcript: String,
        modelUsed: String,
        structuredDataJson: String? = null
    ) {
        val record = TranscriptionRecord(
            title = metadata.fileName,
            fileSize = metadata.sizeBytes,
            durationMs = metadata.durationMs,
            mimeType = metadata.mimeType,
            transcript = transcript,
            modelUsed = modelUsed,
            localFilePath = metadata.file.absolutePath,
            audioHash = metadata.fileHash,
            structuredDataJson = structuredDataJson
        )
        val newId = dao.insertTranscription(record)

        _transcriptionState.value = TranscriptionUiState.Completed(
            metadata = metadata,
            transcript = transcript,
            recordId = newId,
            modelUsed = modelUsed,
            isCached = false,
            structuredDataJson = structuredDataJson
        )
    }

    fun speakTranscript(text: String) {
        speechSynthesizer.speak(text)
    }

    fun stopSpeaking() {
        speechSynthesizer.stop()
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        audioPlayer.stop()
        speechSynthesizer.stop()
        _transcriptionState.value = TranscriptionUiState.Idle
    }

    fun dismissResult() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        audioPlayer.stop()
        speechSynthesizer.stop()
        _transcriptionState.value = TranscriptionUiState.Idle
    }

    fun validateAndSaveKey(
        key: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val validation = geminiService.validateApiKey(key)
            validation.onSuccess { msg ->
                secureKeyManager.saveApiKey(key)
                checkKeyStatus()
                onComplete(true, msg)
                // If there was a pending audio needing a key, resume transcription!
                val current = _transcriptionState.value
                if (current is TranscriptionUiState.KeyRequired) {
                    executeTranscription(current.metadata, useFallback = false)
                }
            }.onFailure { err ->
                onComplete(false, err.localizedMessage ?: "Invalid API key")
            }
        }
    }

    fun deleteKey() {
        secureKeyManager.deleteApiKey()
        checkKeyStatus()
    }

    fun setModel(model: String) {
        secureKeyManager.setSelectedModel(model)
        _currentModel.value = model
    }

    fun deleteTranscriptionRecord(record: TranscriptionRecord) {
        viewModelScope.launch {
            dao.deleteTranscription(record)
            if (_selectedHistoryRecord.value?.id == record.id) {
                _selectedHistoryRecord.value = null
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            dao.clearAll()
            _selectedHistoryRecord.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        speechSynthesizer.shutdown()
        transcriptionJob?.cancel()
        transcriptionJob = null
    }
}
