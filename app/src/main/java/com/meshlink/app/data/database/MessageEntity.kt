package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val originNodeId: String,
    val senderNodeId: String,
    val receiverNodeId: String,
    val senderName: String,
    val type: String,
    val priority: Int,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val ttl: Int,
    val hopCount: Int,
    val payloadReference: String?,
    val payloadText: String?,
    val delivered: Boolean = false,
    val isIncoming: Boolean = false,
    val lastError: String? = null
)
