package dev.dominikstahl.audioscribe.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionMetadataTest {

    @Test
    fun `test parse offset to milliseconds`() {
        assertEquals(0L, AudioTranscriptionData.parseOffsetToMs("0.000s"))
        assertEquals(2450L, AudioTranscriptionData.parseOffsetToMs("2.450s"))
        assertEquals(4120L, AudioTranscriptionData.parseOffsetToMs("4.120s"))
        assertEquals(65432L, AudioTranscriptionData.parseOffsetToMs("65.432s"))
        assertEquals(0L, AudioTranscriptionData.parseOffsetToMs(""))
        assertEquals(0L, AudioTranscriptionData.parseOffsetToMs(null))
    }

    @Test
    fun `test format speaker label`() {
        assertEquals("Speaker 1", AudioTranscriptionData.formatSpeaker("spk_1"))
        assertEquals("Speaker 2", AudioTranscriptionData.formatSpeaker("spk_2"))
        assertEquals("Speaker 8", AudioTranscriptionData.formatSpeaker("spk_8"))
        assertEquals("Interviewer", AudioTranscriptionData.formatSpeaker("interviewer"))
        assertEquals("Speaker", AudioTranscriptionData.formatSpeaker(""))
    }

    @Test
    fun `test format duration`() {
        assertEquals("00:00", AudioTranscriptionData.formatDuration(0L))
        assertEquals("00:02", AudioTranscriptionData.formatDuration(2450L))
        assertEquals("01:05", AudioTranscriptionData.formatDuration(65000L))
        assertEquals("1:01:05", AudioTranscriptionData.formatDuration(3665000L))
    }

    @Test
    fun `test parse sample gemini transcribe response`() {
        val jsonPayload = """
        {
          "audio_transcription": {
            "segments": [
              {
                "speaker_label": "spk_1",
                "start_offset": "0.000s",
                "end_offset": "2.450s",
                "text": "Hello, welcome to the quarterly review."
              },
              {
                "speaker_label": "spk_2",
                "start_offset": "2.680s",
                "end_offset": "4.120s",
                "text": "Thanks, glad to be here."
              }
            ],
            "words": [
              {
                "word": "Hello,",
                "start_offset": "0.000s",
                "end_offset": "0.420s",
                "speaker_label": "spk_1"
              },
              {
                "word": "welcome",
                "start_offset": "0.480s",
                "end_offset": "0.850s",
                "speaker_label": "spk_1"
              },
              {
                "word": "to",
                "start_offset": "0.870s",
                "end_offset": "0.980s",
                "speaker_label": "spk_1"
              },
              {
                "word": "the",
                "start_offset": "1.010s",
                "end_offset": "1.150s",
                "speaker_label": "spk_1"
              },
              {
                "word": "quarterly",
                "start_offset": "1.180s",
                "end_offset": "1.820s",
                "speaker_label": "spk_1"
              },
              {
                "word": "review.",
                "start_offset": "1.850s",
                "end_offset": "2.450s",
                "speaker_label": "spk_1"
              },
              {
                "word": "Thanks,",
                "start_offset": "2.680s",
                "end_offset": "3.100s",
                "speaker_label": "spk_2"
              },
              {
                "word": "glad",
                "start_offset": "3.150s",
                "end_offset": "3.380s",
                "speaker_label": "spk_2"
              },
              {
                "word": "to",
                "start_offset": "3.410s",
                "end_offset": "3.520s",
                "speaker_label": "spk_2"
              },
              {
                "word": "be",
                "start_offset": "3.550s",
                "end_offset": "3.700s",
                "speaker_label": "spk_2"
              },
              {
                "word": "here.",
                "start_offset": "3.720s",
                "end_offset": "4.120s",
                "speaker_label": "spk_2"
              }
            ]
          }
        }
        """.trimIndent()

        val parsed = AudioTranscriptionData.fromJson(jsonPayload)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.segments.size)
        assertEquals(11, parsed.words.size)

        val seg1 = parsed.segments[0]
        assertEquals("spk_1", seg1.speakerLabel)
        assertEquals(0L, seg1.startOffsetMs)
        assertEquals(2450L, seg1.endOffsetMs)
        assertEquals("Hello, welcome to the quarterly review.", seg1.text)
        assertEquals(6, seg1.words.size)

        val seg2 = parsed.segments[1]
        assertEquals("spk_2", seg2.speakerLabel)
        assertEquals(2680L, seg2.startOffsetMs)
        assertEquals(4120L, seg2.endOffsetMs)
        assertEquals("Thanks, glad to be here.", seg2.text)
        assertEquals(5, seg2.words.size)

        // Test roundtrip serialization
        val serialized = parsed.toJsonString()
        val reparsed = AudioTranscriptionData.fromJson(serialized)
        assertNotNull(reparsed)
        assertEquals(2, reparsed!!.segments.size)
        assertEquals(11, reparsed.words.size)
        assertEquals(seg1.text, reparsed.segments[0].text)
        assertEquals(seg2.text, reparsed.segments[1].text)
    }

    @Test
    fun `test build transcription json for gemini-3_5-transcribe`() {
        val service = dev.dominikstahl.audioscribe.service.GeminiService()
        val jsonStr = service.buildTranscriptionJson("dGVzdA==", "audio/mp4", isTranscribeModel = true)
        val json = org.json.JSONObject(jsonStr)

        // Must NOT have systemInstruction (HTTP 400 rejection in gemini-3.5-transcribe)
        org.junit.Assert.assertFalse(json.has("systemInstruction"))

        // Contents must have exactly 1 part (audio inlineData) and NO text parts
        val contents = json.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        val inlineData = parts.getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/mp4", inlineData.getString("mimeType"))
        assertEquals("dGVzdA==", inlineData.getString("data"))

        // Generation config must contain transcription_config and NO temperature
        val genConfig = json.getJSONObject("generationConfig")
        org.junit.Assert.assertFalse(genConfig.has("temperature"))
        assertTrue(genConfig.has("transcription_config"))
        val transConfig = genConfig.getJSONObject("transcription_config")
        val mode = transConfig.getJSONObject("mode")
        assertEquals("verbatim", mode.getString("type"))
        assertEquals("speaker", mode.getString("diarization_mode"))
        val granularities = mode.getJSONArray("timestamp_granularities")
        assertEquals(1, granularities.length())
        assertEquals("word", granularities.getString(0))
    }

    @Test
    fun `test build transcription json for standard multimodal model`() {
        val service = dev.dominikstahl.audioscribe.service.GeminiService()
        val jsonStr = service.buildTranscriptionJson("dGVzdA==", "audio/mp4", isTranscribeModel = false)
        val json = org.json.JSONObject(jsonStr)

        // Must have systemInstruction
        assertTrue(json.has("systemInstruction"))

        // Contents must have 2 parts: audio inlineData and text prompt
        val contents = json.getJSONArray("contents")
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertTrue(parts.getJSONObject(0).has("inlineData"))
        assertTrue(parts.getJSONObject(1).has("text"))

        // Generation config must have temperature 0.0
        val genConfig = json.getJSONObject("generationConfig")
        assertEquals(0.0, genConfig.getDouble("temperature"), 0.001)
        org.junit.Assert.assertFalse(genConfig.has("transcription_config"))
    }

    @Test
    fun `test build interactions json for transcribe model`() {
        val service = dev.dominikstahl.audioscribe.service.GeminiService()
        val jsonStr = service.buildInteractionsJson("gemini-3.5-transcribe", "dGVzdA==", "audio/mp4")
        val json = org.json.JSONObject(jsonStr)

        assertEquals("gemini-3.5-transcribe", json.getString("model"))
        val input = json.getJSONArray("input")
        assertEquals(1, input.length())
        val audioInput = input.getJSONObject(0)
        assertEquals("audio", audioInput.getString("type"))
        assertEquals("audio/mp4", audioInput.getString("mime_type"))
        assertEquals("dGVzdA==", audioInput.getString("data"))

        val genConfig = json.getJSONObject("generation_config")
        assertTrue(genConfig.has("transcription_config"))
    }

    @Test
    fun `test parse interactions api response with word_info annotations`() {
        val service = dev.dominikstahl.audioscribe.service.GeminiService()
        val interactionsPayload = """
        {
          "id": "interactions/test_123",
          "status": "completed",
          "output_text": "Hello world from Gemini",
          "steps": [
            {
              "id": "step_001",
              "type": "model_output",
              "content": [
                {
                  "type": "text",
                  "text": "Hello world from Gemini",
                  "annotations": [
                    {
                      "type": "word_info",
                      "text": "Hello",
                      "speaker": "spk_1",
                      "start_offset": "0.100s",
                      "end_offset": "0.450s"
                    },
                    {
                      "type": "word_info",
                      "text": "world",
                      "speaker": "spk_1",
                      "start_offset": "0.500s",
                      "end_offset": "0.850s"
                    },
                    {
                      "type": "word_info",
                      "text": "from",
                      "speaker": "spk_2",
                      "start_offset": "1.000s",
                      "end_offset": "1.300s"
                    },
                    {
                      "type": "word_info",
                      "text": "Gemini",
                      "speaker": "spk_2",
                      "start_offset": "1.350s",
                      "end_offset": "1.800s"
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val result = service.parseInteractionsResponse(interactionsPayload)
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("Hello world from Gemini", response.transcript)
        assertNotNull(response.structuredDataJson)

        val metadata = AudioTranscriptionData.fromJson(response.structuredDataJson)
        assertNotNull(metadata)
        assertEquals(4, metadata!!.words.size)
        // Grouped by speakers spk_1 and spk_2
        assertEquals(2, metadata.segments.size)
        assertEquals("spk_1", metadata.segments[0].speakerLabel)
        assertEquals("Hello world", metadata.segments[0].text)
        assertEquals(100L, metadata.segments[0].startOffsetMs)
        assertEquals(850L, metadata.segments[0].endOffsetMs)

        assertEquals("spk_2", metadata.segments[1].speakerLabel)
        assertEquals("from Gemini", metadata.segments[1].text)
        assertEquals(1000L, metadata.segments[1].startOffsetMs)
        assertEquals(1800L, metadata.segments[1].endOffsetMs)
    }
}
