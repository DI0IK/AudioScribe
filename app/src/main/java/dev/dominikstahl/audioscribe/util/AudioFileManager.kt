package dev.dominikstahl.audioscribe.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

data class AudioMetadata(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val fileHash: String = ""
)

object AudioFileManager {

    private const val TAG = "AudioFileManager"
    const val MAX_INLINE_AUDIO_BYTES = 20 * 1024 * 1024L // 20 MB

    fun processIncomingAudioUri(context: Context, uri: Uri): Result<AudioMetadata> {
        return try {
            val contentResolver = context.contentResolver

            // 1. Determine initial filename
            var fileName = "audio_memo_${System.currentTimeMillis()}"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        fileName = name
                    }
                }
            }

            // 2. Determine raw MIME type
            var rawMime = contentResolver.getType(uri) ?: ""
            Log.d(TAG, "Incoming URI: $uri, declared MIME: $rawMime, filename: $fileName")

            // 3. Copy content stream to temporary local cache file
            val tempDir = File(context.cacheDir, "audioscribe_cache").apply { mkdirs() }
            val tempFile = File(tempDir, "temp_${System.currentTimeMillis()}_$fileName")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(Exception("Unable to open audio stream from URI"))

            val sizeBytes = tempFile.length()
            if (sizeBytes == 0L) {
                tempFile.delete()
                return Result.failure(Exception("The audio file is empty (0 bytes)"))
            }

            if (sizeBytes > MAX_INLINE_AUDIO_BYTES) {
                tempFile.delete()
                val sizeMb = sizeBytes / (1024.0 * 1024.0)
                return Result.failure(
                    Exception("Audio file is too large (%.1f MB). Gemini inline audio currently supports files under 20MB.".format(Locale.US, sizeMb))
                )
            }

            // 4. Inspect magic bytes and normalize MIME type
            val resolvedMime = detectAndNormalizeMimeType(tempFile, rawMime, fileName)
            Log.d(TAG, "Resolved normalized MIME: $resolvedMime for size: $sizeBytes")

            // 5. Extract duration if possible
            val durationMs = extractDurationMs(context, tempFile)

            // 6. Compute SHA-256 file hash for deduplication/caching
            val fileHash = computeSha256(tempFile)

            Result.success(
                AudioMetadata(
                    file = tempFile,
                    fileName = fileName,
                    mimeType = resolvedMime,
                    sizeBytes = sizeBytes,
                    durationMs = durationMs,
                    fileHash = fileHash
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming audio URI", e)
            Result.failure(e)
        }
    }

    /**
     * Accurately detects and normalizes audio MIME types for Gemini API.
     * Crucially handles WhatsApp voice notes (.opus inside .ogg container or declared as application/ogg).
     */
    private fun detectAndNormalizeMimeType(file: File, declaredMime: String, fileName: String): String {
        val lowerMime = declaredMime.lowercase(Locale.ROOT)
        val lowerName = fileName.lowercase(Locale.ROOT)

        // Inspect header bytes
        val magicBytes = ByteArray(16)
        try {
            file.inputStream().use { it.read(magicBytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read magic bytes", e)
        }

        val magicStr = String(magicBytes, Charsets.ISO_8859_1)

        // Ogg container (OggS) - Typical for WhatsApp voice notes (Opus in Ogg)
        if (magicBytes.size >= 4 && magicBytes[0] == 'O'.code.toByte() &&
            magicBytes[1] == 'g'.code.toByte() &&
            magicBytes[2] == 'g'.code.toByte() &&
            magicBytes[3] == 'S'.code.toByte()) {
            return "audio/ogg"
        }

        // RIFF WAV
        if (magicBytes.size >= 4 && magicBytes[0] == 'R'.code.toByte() &&
            magicBytes[1] == 'I'.code.toByte() &&
            magicBytes[2] == 'F'.code.toByte() &&
            magicBytes[3] == 'F'.code.toByte()) {
            return "audio/wav"
        }

        // AMR (#!AMR)
        if (magicBytes.size >= 5 && magicBytes[0] == '#'.code.toByte() &&
            magicBytes[1] == '!'.code.toByte() &&
            magicBytes[2] == 'A'.code.toByte() &&
            magicBytes[3] == 'M'.code.toByte() &&
            magicBytes[4] == 'R'.code.toByte()) {
            return "audio/amr"
        }

        // MP3 (ID3 tag or sync frame 0xFF 0xFB)
        if (magicBytes.size >= 3 && magicBytes[0] == 'I'.code.toByte() &&
            magicBytes[1] == 'D'.code.toByte() &&
            magicBytes[2] == '3'.code.toByte()) {
            return "audio/mp3"
        }

        // MP4 / M4A (ftyp box)
        if (magicStr.contains("ftyp") || lowerName.endsWith(".m4a") || lowerName.endsWith(".mp4") || lowerMime.contains("mp4") || lowerMime.contains("m4a")) {
            return "audio/mp4"
        }

        // Check declared MIME and extensions
        return when {
            lowerMime.contains("ogg") || lowerName.endsWith(".ogg") || lowerName.endsWith(".opus") -> "audio/ogg"
            lowerMime.contains("mpeg") || lowerMime.contains("mp3") || lowerName.endsWith(".mp3") -> "audio/mp3"
            lowerMime.contains("wav") || lowerName.endsWith(".wav") -> "audio/wav"
            lowerMime.contains("amr") || lowerName.endsWith(".amr") -> "audio/amr"
            lowerMime.contains("aac") || lowerName.endsWith(".aac") -> "audio/aac"
            lowerMime.contains("flac") || lowerName.endsWith(".flac") -> "audio/flac"
            lowerMime.startsWith("audio/") -> lowerMime.split(";")[0].trim()
            else -> "audio/ogg" // Safe fallback default
        }
    }

    private fun extractDurationMs(context: Context, file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract audio duration", e)
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "--:--"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    fun clearCache(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "audioscribe_cache")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear audio cache", e)
        }
    }

    /**
     * Computes SHA-256 hex digest of a file to detect duplicates and prevent re-transcription.
     */
    fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute SHA-256 hash for ${file.name}", e)
            ""
        }
    }
}
