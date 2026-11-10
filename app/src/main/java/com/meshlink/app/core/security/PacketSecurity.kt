package com.meshlink.app.core.security
import com.meshlink.app.core.protocol.MeshPacket
import com.meshlink.app.core.protocol.PacketEncoder
import java.nio.ByteBuffer
import java.security.Signature
import java.util.UUID
class PacketSecurity(private val crypto:CryptoManager) {
    fun encode(packet:MeshPacket):ByteArray = encodeForPeer(packet, packet.senderNodeId)
    fun encodeForPeer(packet:MeshPacket, peer:UUID):ByteArray {
        val nonce=ByteArray(12).also{java.security.SecureRandom().nextBytes(it)}
        val plain=packet.payload
        val base=packet.copy(nonce=nonce,payload=ByteArray(0))
        val aad=headerAad(base)
        val cipher=if(packet.type==com.meshlink.app.core.protocol.PacketType.HELLO) plain else
            crypto.encrypt(peer,nonce,aad,plain)
        return PacketEncoder.encode(base,cipher)
    }
    fun decode(raw:ByteArray):MeshPacket? {
        val d=PacketEncoder.decode(raw)?:return null
        if(d.packet.type==com.meshlink.app.core.protocol.PacketType.HELLO) return d.packet.copy(payload=d.ciphertext)
        val aad=headerAad(d.packet)
        val plain=crypto.decrypt(d.packet.senderNodeId,d.packet.nonce,aad,d.ciphertext)?:return null
        return d.packet.copy(payload=plain)
    }
    private fun headerAad(p:MeshPacket)=ByteBuffer.allocate(16+16+16+16+1+1+8+1+1+4).apply{
        putLong(p.messageId.mostSignificantBits).putLong(p.messageId.leastSignificantBits)
        putLong(p.senderNodeId.mostSignificantBits).putLong(p.senderNodeId.leastSignificantBits)
        putLong(p.originNodeId.mostSignificantBits).putLong(p.originNodeId.leastSignificantBits)
        val d=p.destinationNodeId?:UUID(0,0); putLong(d.mostSignificantBits).putLong(d.leastSignificantBits)
        put(p.type.code).put(p.priority.toByte()).putLong(p.createdAt).put(p.ttl.toByte()).put(p.hopCount.toByte()).putInt(p.flags)
    }.array()
}
object HelloCodec {
    // [version=1][node UUID 16][public-key length u16][pubkey][signature length u16][signature]
    fun encode(nodeId:UUID,pub:ByteArray,signature:ByteArray,displayName:String = "Mesh Node"):ByteArray {
        val name = displayName.trim().replace("\n", " ").take(32).toByteArray(Charsets.UTF_8)
        val b=ByteBuffer.allocate(1+16+2+pub.size+2+signature.size+1+name.size)
        b.put(1).putLong(nodeId.mostSignificantBits).putLong(nodeId.leastSignificantBits)
            .putShort(pub.size.toShort()).put(pub).putShort(signature.size.toShort()).put(signature)
            .put(name.size.toByte()).put(name)
        return b.array()
    }
    data class Decoded(val nodeId:UUID,val publicKey:ByteArray,val signature:ByteArray,val displayName:String)
    fun decode(x:ByteArray):Decoded? {
        return try {
            val b=ByteBuffer.wrap(x)
            if(b.remaining() < 1 + 16 + 2 + 2) return null
            if(b.get().toInt()!=1)return null
            val id=UUID(b.long,b.long)
            val pl=b.short.toInt() and 65535
            if(pl>4096 || b.remaining() < pl + 2)return null
            val pub=ByteArray(pl);b.get(pub)
            val sl=b.short.toInt() and 65535
            if(sl>4096 || b.remaining()<sl)return null
            val sig=ByteArray(sl);b.get(sig)
            val displayName = if (b.hasRemaining()) {
                val nl = b.get().toInt() and 0xff
                if (nl > 32 || b.remaining() != nl) return null
                ByteArray(nl).also { b.get(it) }.toString(Charsets.UTF_8).ifBlank { "Mesh Node" }
            } else "Mesh Node"
            Decoded(id,pub,sig,displayName)
        }catch(_:Throwable){null}
    }
}
