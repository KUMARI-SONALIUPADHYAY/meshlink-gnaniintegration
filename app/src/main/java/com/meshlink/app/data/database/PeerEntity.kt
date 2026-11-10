package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val address: String,
    val displayName: String,
    val lastSeen: Long,
    val connectionState: String,
    val rssi: Int,
    val capabilities: String,
    val trustState: String
)
