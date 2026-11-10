package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val timestamp: Long,
    val category: String,
    val nodeId: String?,
    val messageId: String?,
    val details: String
)
