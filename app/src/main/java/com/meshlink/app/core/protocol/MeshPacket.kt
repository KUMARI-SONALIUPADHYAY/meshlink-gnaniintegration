package com.meshlink.app.core.protocol

import java.util.UUID

enum class PacketStatus { RECEIVED, SAFE, HELD, FORWARDED, DELIVERED, BLOCKED, EXPIRED, QUEUED }

data class MeshPacket(
    val protocolVersion: Byte = 1,
    val messageId: UUID,
    val senderNodeId: UUID,
    val originNodeId: UUID,
    val destinationNodeId: UUID?,
    val type: PacketType,
    val priority: Int,
    val createdAt: Long,
    val ttl: Int,
    val hopCount: Int,
    val flags: Int,
    val payload: ByteArray,
    val payloadHash: ByteArray = ByteArray(16),
    val nonce: ByteArray = ByteArray(12)
) {
    val payloadLength get() = payload.size
}
