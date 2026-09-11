package dev.dominikstahl.audioscribe.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.dominikstahl.audioscribe.data.model.TranscriptionRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionRecord>>

    @Query("SELECT * FROM transcriptions WHERE id = :id")
    suspend fun getTranscriptionById(id: Long): TranscriptionRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscription(record: TranscriptionRecord): Long

    @Delete
    suspend fun deleteTranscription(record: TranscriptionRecord)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transcriptions")
    suspend fun clearAll()
}
