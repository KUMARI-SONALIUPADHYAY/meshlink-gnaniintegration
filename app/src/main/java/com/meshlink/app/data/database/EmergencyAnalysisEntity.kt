package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_analyses")
data class EmergencyAnalysisEntity(
    @PrimaryKey val messageId: String,
    val emergencyType: String,
    val severity: String,
    val locationMentioned: String?,
    val peopleAffected: Int?,
    val immediateAssistance: String,
    val instructionsJson: String,
    val warningsJson: String,
    val suggestedResponse: String,
    val rawJson: String,
    val createdAt: Long
)
