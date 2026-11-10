package com.meshlink.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VoiceTranscriptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transcription: VoiceTranscriptionEntity)

    @Query("SELECT * FROM voice_transcriptions WHERE messageId = :messageId LIMIT 1")
    suspend fun byMessageId(messageId: String): VoiceTranscriptionEntity?
}
