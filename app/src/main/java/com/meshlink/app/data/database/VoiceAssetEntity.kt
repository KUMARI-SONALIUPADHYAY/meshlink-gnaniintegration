package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_assets")
data class VoiceAssetEntity(
    @PrimaryKey val messageId: String,
    val codec: String,
    val durationMs: Long,
    val localPath: String,
    val chunkCount: Int,
    val receivedChunks: Int
)
