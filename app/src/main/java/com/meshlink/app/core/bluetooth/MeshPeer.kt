package com.meshlink.app.core.bluetooth

data class MeshPeer(
    val address: String,
    val nodeId: String,
    val rssi: Int,
    val displayName: String = "Mesh Node",
    val lastSeen: Long = System.currentTimeMillis()
)