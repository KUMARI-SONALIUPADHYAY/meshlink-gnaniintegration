package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable per-stage retry record; the original audio remains in app-private storage. */
@Entity(tableName = "ai_workflow_queue", indices = [Index(value = ["messageId"])])
data class AiWorkflowQueueEntity(
    @PrimaryKey val jobId: String,
    val messageId: String,
    val audioPath: String,
    val sourceText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val targetVoice: String,
    val stage: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: Long
)
