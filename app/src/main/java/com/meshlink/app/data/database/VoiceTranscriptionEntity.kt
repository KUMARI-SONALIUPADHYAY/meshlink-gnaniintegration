package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Prisma output is stored separately so the original audio asset is never overwritten. */
@Entity(tableName = "voice_transcriptions")
data class VoiceTranscriptionEntity(
    @PrimaryKey val messageId: String,
    val transcript: String,
    val languageCode: String,
    val provider: String,
    val updatedAt: Long
)
