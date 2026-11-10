package com.meshlink.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeneratedSpeechDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: GeneratedSpeechEntity)

    @Query("SELECT * FROM generated_speech WHERE messageId = :messageId LIMIT 1")
    suspend fun byMessageId(messageId: String): GeneratedSpeechEntity?
}
