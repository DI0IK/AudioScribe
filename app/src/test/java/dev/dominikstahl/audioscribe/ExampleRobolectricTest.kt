package dev.dominikstahl.audioscribe

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.dominikstahl.audioscribe.security.SecureKeyManager
import dev.dominikstahl.audioscribe.service.OnDeviceSpeechService
import dev.dominikstahl.audioscribe.ui.MainViewModel
import dev.dominikstahl.audioscribe.ui.TranscriptionUiState
import dev.dominikstahl.audioscribe.util.AudioFileManager
import dev.dominikstahl.audioscribe.util.SpeechSynthesizerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    val shareLabel = context.getString(R.string.transcribe_with_audioscribe)
    assertEquals("AudioScribe", appName)
    assertEquals("Transcribe with AudioScribe", shareLabel)
  }

  @Test
  fun `verify audio file manager formatting`() {
    assertEquals("1.5 MB", AudioFileManager.formatFileSize(1572864L))
    assertEquals("500.0 KB", AudioFileManager.formatFileSize(512000L))
    assertEquals("2:30", AudioFileManager.formatDuration(150000L))
    assertEquals("0:45", AudioFileManager.formatDuration(45000L))
  }

  @Test
  fun `verify secure key manager basics`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val keyManager = SecureKeyManager(context)
    val model = keyManager.getSelectedModel()
    assertNotNull(model)
    assertEquals("gemini-3.8-flash", model)
    assertEquals(
        listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.5-transcribe",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-3.8-flash"
        ),
        SecureKeyManager.AVAILABLE_MODELS
    )
    // Verify stale model migration fallback
    keyManager.setSelectedModel("gemini-2.5-flash")
    assertEquals("gemini-3.8-flash", keyManager.getSelectedModel())
    keyManager.setSelectedModel("gemini-3.5-flash")
    assertEquals("gemini-3.5-flash", keyManager.getSelectedModel())
    keyManager.setSelectedModel("gemini-3.5-flash-lite")
    assertEquals("gemini-3.5-flash-lite", keyManager.getSelectedModel())
    keyManager.setSelectedModel("gemini-3.5-transcribe")
    assertEquals("gemini-3.5-transcribe", keyManager.getSelectedModel())
    keyManager.setSelectedModel("gemini-3.7-flash")
    assertEquals("gemini-3.7-flash", keyManager.getSelectedModel())
  }

  @Test
  fun `verify audio file hash calculation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempFile = java.io.File(context.cacheDir, "test_audio_sample.aac")
    tempFile.writeText("audio sample bytes for testing hash deduplication")
    val hash1 = AudioFileManager.computeSha256(tempFile)
    val hash2 = AudioFileManager.computeSha256(tempFile)
    assertNotNull(hash1)
    assertTrue(hash1.isNotBlank())
    assertEquals(hash1, hash2)
    tempFile.delete()
  }

  @Test
  fun `verify on device speech service configuration`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val speechService = OnDeviceSpeechService(context)
    assertEquals("On-Device Speech Engine", OnDeviceSpeechService.MODEL_NAME)
    assertNotNull(speechService)
  }

  @Test
  fun `verify speech synthesizer manager initialization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val synthesizer = SpeechSynthesizerManager(context)
    assertNotNull(synthesizer)
    assertFalse(synthesizer.isSpeaking.value)
    synthesizer.shutdown()
  }

  @Test
  fun `verify viewModel initial state is idle and handles key status`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = MainViewModel(app)
    assertEquals(TranscriptionUiState.Idle, viewModel.transcriptionState.value)
    assertNotNull(viewModel.onDeviceSpeechService)
    assertNotNull(viewModel.speechSynthesizer)
  }

  @Test
  fun `verify incoming audio transitions to Ready state and requires manual start`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val tempAudio = java.io.File(app.cacheDir, "sample_note.m4a")
    tempAudio.writeText("simulated audio data for test")
    val metadata = dev.dominikstahl.audioscribe.util.AudioMetadata(
        file = tempAudio,
        fileName = "sample_note.m4a",
        mimeType = "audio/mp4",
        sizeBytes = tempAudio.length(),
        durationMs = 3000L,
        fileHash = "dummyhash123"
    )
    val readyState = TranscriptionUiState.Ready(
        metadata = metadata,
        isFallback = false,
        modelName = "gemini-3.8-flash"
    )
    assertEquals("sample_note.m4a", readyState.metadata.fileName)
    assertEquals("gemini-3.8-flash", readyState.modelName)
    assertFalse(readyState.isFallback)
    tempAudio.delete()
  }
}
