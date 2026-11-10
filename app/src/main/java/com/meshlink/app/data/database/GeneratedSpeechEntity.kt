package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A generated TTS file is retained locally and linked to its originating emergency message. */
@Entity(tableName = "generated_speech")
data class GeneratedSpeechEntity(
    @PrimaryKey val messageId: String,
    val localPath: String,
    val languageCode: String,
    val voice: String,
    val model: String,
    val createdAt: Long
)
