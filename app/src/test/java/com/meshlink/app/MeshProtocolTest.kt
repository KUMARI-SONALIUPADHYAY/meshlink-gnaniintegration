package com.meshlink.app

import com.meshlink.app.core.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MeshProtocolTest {
    @Test fun binaryPacketRoundTrip(){
        val p=MeshPacket(messageId=UUID.randomUUID(),senderNodeId=UUID.randomUUID(),originNodeId=UUID.randomUUID(),
            destinationNodeId=UUID.randomUUID(),type=PacketType.TEXT,priority=30,createdAt=1_000,ttl=6,hopCount=2,flags=0,payload=byteArrayOf(1,2,3))
        val raw=PacketEncoder.encode(p,p.payload)
        val d=PacketEncoder.decode(raw)!!
        assertEquals(p.messageId,d.packet.messageId);assertEquals(p.originNodeId,d.packet.originNodeId)
        assertArrayEquals(p.payload,d.ciphertext)
    }
}
