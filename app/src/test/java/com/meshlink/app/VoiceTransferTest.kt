package com.meshlink.app

import com.meshlink.app.core.voice.VoiceTransfer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class VoiceTransferTest {
    @Test
    fun chunks_roundTrip_preservesAllBytesAndMetadata() {
        val id = UUID.randomUUID()
        val source = ByteArray(997) { (it * 31 and 0xFF).toByte() }
        val chunks = VoiceTransfer.chunks(id, source, 4200L)

        assertNotNull(chunks)
        assertEquals(id, chunks.first().messageId)
        assertEquals(4200L, chunks.first().durationMs)

        val rebuilt = chunks.sortedBy { it.index }.flatMap { it.data.asList() }.toByteArray()
        assertArrayEquals(source, rebuilt)

        val decoded = chunks.map { VoiceTransfer.decode(VoiceTransfer.encode(it)) }
        assertEquals(chunks.size, decoded.size)
        decoded.forEachIndexed { index, value ->
            assertNotNull(value)
            assertEquals(chunks[index].index, value!!.index)
            assertEquals(chunks[index].total, value.total)
            assertArrayEquals(chunks[index].data, value.data)
        }
    }
}
