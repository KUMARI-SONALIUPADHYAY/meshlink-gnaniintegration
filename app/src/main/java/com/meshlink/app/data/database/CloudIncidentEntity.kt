package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_incidents")
data class CloudIncidentEntity(
    @PrimaryKey val incidentId: String,
    val messageId: String,
    val category: String,
    val severity: Int,
    val description: String,
    val latitude: Double?,
    val longitude: Double?,
    val affectedCount: Int?,
    val assistance: String?,
    val mediaId: String?,
    val status: String,
    val verification: String,
    val version: Int,
    val reportedAt: Long,
    val updatedAt: Long
)
