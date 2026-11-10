package com.meshlink.app.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object PacketEncoder {
    private const val MAGIC = 0x4D4C
    private const val HEADER = 2 + 1 + 16 + 16 + 16 + 1 + 16 + 1 + 1 + 8 + 1 + 1 + 4 + 4 + 16 + 12
    fun encode(packet: MeshPacket, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size <= 60_000)
        val b = ByteBuffer.allocate(HEADER + ciphertext.size).order(ByteOrder.BIG_ENDIAN)
        b.putShort(MAGIC.toShort()).put(packet.protocolVersion)
        putUuid(b, packet.messageId)
        putUuid(b, packet.senderNodeId)
        putUuid(b, packet.originNodeId)
        b.put(if (packet.destinationNodeId != null) 1 else 0)
        putUuid(b, packet.destinationNodeId ?: UUID(0,0))
        b.put(packet.type.code)
        b.put(packet.priority.coerceIn(0,100).toByte()).putLong(packet.createdAt)
        b.put(packet.ttl.coerceIn(0,255).toByte()).put(packet.hopCount.coerceIn(0,255).toByte())
        b.putInt(packet.flags).putInt(ciphertext.size).put(java.security.MessageDigest.getInstance("SHA-256").digest(ciphertext).copyOf(16)).put(packet.nonce).put(ciphertext)
        return b.array()
    }
    fun decode(data: ByteArray): Decoded? {
        return try {
            if (data.size < HEADER) return null
            val b=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (b.short.toInt() != MAGIC) return null
            val version=b.get()
            if (version.toInt() != 1) return null
            val id=getUuid(b)
            val sender=getUuid(b)
            val origin=getUuid(b)
            val hasDest=b.get().toInt()!=0
            val destRaw=getUuid(b)
            val typeCode=b.get()
            val type=PacketType.entries.firstOrNull{it.code==typeCode} ?: return null
            val priority=b.get().toInt() and 0xff
            val created=b.long
            val ttl=b.get().toInt() and 0xff
            val hops=b.get().toInt() and 0xff
            val flags=b.int
            val len=b.int
            if(len<0 || len>60_000 || b.remaining()!=28+len)return null
            val hash=ByteArray(16); b.get(hash)
            val nonce=ByteArray(12); b.get(nonce)
            val cipher=ByteArray(len); b.get(cipher)
            val actual=java.security.MessageDigest.getInstance("SHA-256").digest(cipher).copyOf(16)
            if(!hash.contentEquals(actual))return null
            Decoded(MeshPacket(version,id,sender,origin,if(hasDest)destRaw else null,type,priority,created,ttl,hops,flags,ByteArray(0),hash,nonce),cipher)
        } catch(_:Throwable){null}
    }
    data class Decoded(val packet: MeshPacket,val ciphertext: ByteArray)
    private fun putUuid(b:ByteBuffer,u:UUID):ByteBuffer= b.putLong(u.mostSignificantBits).putLong(u.leastSignificantBits)
    private fun getUuid(b:ByteBuffer)=UUID(b.long,b.long)
}
