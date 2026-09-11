package dev.dominikstahl.audioscribe.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcriptions")
data class TranscriptionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val fileSize: Long,
    val durationMs: Long,
    val mimeType: String,
    val transcript: String,
    val modelUsed: String,
    val localFilePath: String? = null,
    val audioHash: String = "",
    val structuredDataJson: String? = null
)
