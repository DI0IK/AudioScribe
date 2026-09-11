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
}
