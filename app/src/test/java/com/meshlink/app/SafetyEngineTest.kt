package com.meshlink.app
import com.meshlink.app.core.protocol.*
import com.meshlink.app.core.safety.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SafetyEngineTest {
    @Test fun invalidTtlBlocks(){
        val p=MeshPacket(
            messageId=UUID.randomUUID(),
            senderNodeId=UUID.randomUUID(),
            originNodeId=UUID.randomUUID(),
            destinationNodeId=null,
            type=PacketType.TEXT,
            priority=30,
            createdAt=System.currentTimeMillis(),
            ttl=99,
            hopCount=0,
            flags=0,
            payload=ByteArray(0)
        )
        assertEquals(SafetyDecision.BLOCK,SafetyEngine().evaluate(p,"x").decision)
    }
}
