package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.SecureKeyManager
import com.example.service.OnDeviceSpeechService
import com.example.ui.MainViewModel
import com.example.ui.TranscriptionUiState
import com.example.util.AudioFileManager
import com.example.util.SpeechSynthesizerManager
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
    assertEquals("AudioScribe", appName)
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
    assertTrue(model.startsWith("gemini"))
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
}
