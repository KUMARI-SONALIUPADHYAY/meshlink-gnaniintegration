package com.meshlink.app.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Idempotent, durable outbox. A stable eventId is retained across retry attempts. */
@Entity(tableName = "cloud_sync_outbox", indices = [Index(value = ["status", "createdAt"])])
data class CloudSyncOutboxEntity(
    @PrimaryKey val eventId: String,
    val messageId: String,
    val eventType: String,
    val payloadJson: String,
    val status: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    val ownerAccountId: String? = null
)
