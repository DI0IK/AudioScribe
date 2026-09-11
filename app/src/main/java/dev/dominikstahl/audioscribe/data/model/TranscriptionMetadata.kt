package dev.dominikstahl.audioscribe.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TranscriptWord(
    val word: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val speakerLabel: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("word", word)
            put("start_offset", String.format(Locale.US, "%.3fs", startOffsetMs / 1000.0))
            put("end_offset", String.format(Locale.US, "%.3fs", endOffsetMs / 1000.0))
            speakerLabel?.let { put("speaker_label", it) }
        }
    }
}

data class TranscriptSegment(
    val speakerLabel: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val text: String,
    val words: List<TranscriptWord> = emptyList()
) {
    fun toJson(): JSONObject {
        val json = JSONObject().apply {
            put("speaker_label", speakerLabel)
            put("start_offset", String.format(Locale.US, "%.3fs", startOffsetMs / 1000.0))
            put("end_offset", String.format(Locale.US, "%.3fs", endOffsetMs / 1000.0))
            put("text", text)
        }
        if (words.isNotEmpty()) {
            val wordsArray = JSONArray()
            words.forEach { wordsArray.put(it.toJson()) }
            json.put("words", wordsArray)
        }
        return json
    }
}

data class AudioTranscriptionData(
    val segments: List<TranscriptSegment> = emptyList(),
    val words: List<TranscriptWord> = emptyList()
) {
    fun toJsonString(): String {
        val root = JSONObject()
        val segmentsArray = JSONArray()
        segments.forEach { segmentsArray.put(it.toJson()) }
        root.put("segments", segmentsArray)

        val wordsArray = JSONArray()
        words.forEach { wordsArray.put(it.toJson()) }
        root.put("words", wordsArray)

        return root.toString()
    }

    companion object {
        fun parseOffsetToMs(offset: String?): Long {
            if (offset.isNullOrBlank()) return 0L
            val cleaned = offset.trim().removeSuffix("s").removeSuffix("S").trim()
            val seconds = cleaned.toDoubleOrNull() ?: 0.0
            return (seconds * 1000.0).toLong()
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        fun formatSpeaker(speakerLabel: String?): String {
            if (speakerLabel.isNullOrBlank()) return "Speaker"
            val regex = Regex("spk_?(\\d+)", RegexOption.IGNORE_CASE)
            val match = regex.find(speakerLabel)
            return if (match != null) {
                "Speaker ${match.groupValues[1]}"
            } else {
                speakerLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
        }

        fun fromJsonObject(json: JSONObject): AudioTranscriptionData {
            val segmentsList = mutableListOf<TranscriptSegment>()
            val wordsList = mutableListOf<TranscriptWord>()

            val wordsArray = json.optJSONArray("words")
            if (wordsArray != null) {
                for (i in 0 until wordsArray.length()) {
                    val wordObj = wordsArray.optJSONObject(i) ?: continue
                    val word = wordObj.optString("word")
                    val start = parseOffsetToMs(wordObj.optString("start_offset"))
                    val end = parseOffsetToMs(wordObj.optString("end_offset"))
                    val spk = wordObj.optString("speaker_label").takeIf { it.isNotBlank() }
                    wordsList.add(TranscriptWord(word = word, startOffsetMs = start, endOffsetMs = end, speakerLabel = spk))
                }
            }

            val segmentsArray = json.optJSONArray("segments")
            if (segmentsArray != null) {
                for (i in 0 until segmentsArray.length()) {
                    val segObj = segmentsArray.optJSONObject(i) ?: continue
                    val spk = segObj.optString("speaker_label", "spk_1")
                    val start = parseOffsetToMs(segObj.optString("start_offset"))
                    val end = parseOffsetToMs(segObj.optString("end_offset"))
                    val text = segObj.optString("text")

                    // Associate words belonging to this segment if available
                    val segWords = if (wordsList.isNotEmpty()) {
                        wordsList.filter { it.startOffsetMs in start..end }
                    } else emptyList()

                    segmentsList.add(
                        TranscriptSegment(
                            speakerLabel = spk,
                            startOffsetMs = start,
                            endOffsetMs = end,
                            text = text,
                            words = segWords
                        )
                    )
                }
            }

            return AudioTranscriptionData(segments = segmentsList, words = wordsList)
        }

        fun fromJson(jsonString: String?): AudioTranscriptionData? {
            if (jsonString.isNullOrBlank()) return null
            return try {
                val json = JSONObject(jsonString)
                val targetJson = if (json.has("audio_transcription")) {
                    json.getJSONObject("audio_transcription")
                } else {
                    json
                }
                fromJsonObject(targetJson)
            } catch (e: Exception) {
                null
            }
        }
    }
}
