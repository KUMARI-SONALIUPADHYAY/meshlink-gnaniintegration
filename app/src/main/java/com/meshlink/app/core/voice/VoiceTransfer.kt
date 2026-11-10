package com.meshlink.app.core.voice
import java.nio.ByteBuffer
import java.util.UUID
data class VoiceChunk(val messageId:UUID,val index:Int,val total:Int,val durationMs:Long,val data:ByteArray)
object VoiceTransfer{
    const val CHUNK=120
    fun chunks(id:UUID,file:ByteArray,durationMs:Long):List<VoiceChunk>{
        val n=(file.size+CHUNK-1)/CHUNK
        return (0 until n).map{idx->VoiceChunk(id,idx,n,durationMs,file.copyOfRange(idx*CHUNK,minOf(file.size,(idx+1)*CHUNK)))}
    }
    fun encode(c:VoiceChunk):ByteArray{
        val b=ByteBuffer.allocate(16+2+2+8+c.data.size).putLong(c.messageId.mostSignificantBits).putLong(c.messageId.leastSignificantBits)
            .putShort(c.index.toShort()).putShort(c.total.toShort()).putLong(c.durationMs).put(c.data);return b.array()
    }
    fun decode(x:ByteArray):VoiceChunk? {
        return try {
            if (x.size < 28) return null
            val b=ByteBuffer.wrap(x)
            val id=UUID(b.long,b.long)
            val i=b.short.toInt() and 65535
            val t=b.short.toInt() and 65535
            val d=b.long
            if (t == 0 || i >= t) return null
            VoiceChunk(id,i,t,d,ByteArray(b.remaining()).also{b.get(it)})
        } catch(_:Throwable){null}
    }
}
