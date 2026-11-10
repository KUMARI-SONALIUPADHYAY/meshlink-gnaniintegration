package com.meshlink.app.core.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ThreadLocalRandom

/**
 * Small GATT frames that also work when the negotiated ATT MTU stays at the
 * Android minimum (20-byte characteristic payload).
 *
 * Frame format: [magic:1][frameId:4][index:2][total:2][payload:<=11]
 */
object BleFrameCodec {
    private const val MAGIC: Byte = 0x5A
    private const val HEADER_SIZE = 1 + 4 + 2 + 2
    private const val MAX_GATT_VALUE = 20
    const val MAX_PAYLOAD = MAX_GATT_VALUE - HEADER_SIZE

    fun split(data: ByteArray, maxPayload: Int = MAX_PAYLOAD): List<ByteArray> {
        if (data.isEmpty()) return listOf(emptyFrame())
        val payloadSize = maxPayload.coerceIn(MAX_PAYLOAD, 235)
        val frameId = ThreadLocalRandom.current().nextInt()
        val chunks = data.asList().chunked(payloadSize)
        require(chunks.size <= 0xFFFF) { "BLE payload is too large" }
        val total = chunks.size
        return chunks.mapIndexed { index, chunk ->
            ByteBuffer.allocate(HEADER_SIZE + chunk.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(frameId)
                .putShort(index.toShort())
                .putShort(total.toShort())
                .put(chunk.toByteArray())
                .array()
        }
    }

    private fun emptyFrame(): ByteArray = byteArrayOf(
        MAGIC, 0, 0, 0, 0, 0, 0, 0, 1
    )

    data class Frame(
        val id: Int,
        val index: Int,
        val total: Int,
        val payload: ByteArray
    )

    fun decode(data: ByteArray): Frame? {
        return try {
            if (data.size < HEADER_SIZE || data[0] != MAGIC) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            b.get()
            val id = b.int
            val index = b.short.toInt() and 0xFFFF
            val total = b.short.toInt() and 0xFFFF
            if (total <= 0 || index >= total) return null
            Frame(id, index, total, ByteArray(b.remaining()).also { b.get(it) })
        } catch (_: Throwable) {
            null
        }
    }
}

class FrameReassembler {
    private data class Bucket(
        val total: Int,
        val parts: MutableMap<Int, ByteArray>,
        val created: Long,
        var byteCount: Int = 0
    )

    private val buckets = HashMap<Int, Bucket>()

    @Synchronized
    fun accept(data: ByteArray): ByteArray? {
        val frame = BleFrameCodec.decode(data) ?: return data
        val now = System.currentTimeMillis()
        buckets.entries.removeIf { now - it.value.created > MAX_AGE_MS }

        // A normal MeshLink packet is far smaller than these limits. Rejecting
        // unreasonable frame metadata here prevents peers from reserving large
        // numbers of incomplete assemblies in memory.
        if (frame.total !in 1..MAX_FRAMES || frame.payload.size > MAX_FRAME_PAYLOAD) return null

        val bucket = buckets[frame.id] ?: run {
            if (buckets.size >= MAX_ACTIVE_ASSEMBLIES) {
                val oldest = buckets.minByOrNull { it.value.created }?.key
                if (oldest != null) buckets.remove(oldest)
            }
            Bucket(frame.total, HashMap(), now).also { buckets[frame.id] = it }
        }

        if (bucket.total != frame.total) {
            buckets.remove(frame.id)
            return null
        }

        val previousSize = bucket.parts[frame.index]?.size ?: 0
        val nextSize = bucket.byteCount - previousSize + frame.payload.size
        if (nextSize > MAX_ASSEMBLED_BYTES) {
            buckets.remove(frame.id)
            return null
        }

        bucket.parts[frame.index] = frame.payload.copyOf()
        bucket.byteCount = nextSize
        if (bucket.parts.size != bucket.total) return null

        val output = ByteArray(bucket.byteCount)
        var offset = 0
        for (i in 0 until bucket.total) {
            val part = bucket.parts[i] ?: run {
                buckets.remove(frame.id)
                return null
            }
            System.arraycopy(part, 0, output, offset, part.size)
            offset += part.size
        }
        buckets.remove(frame.id)
        return output
    }

    private companion object {
        const val MAX_AGE_MS = 30_000L
        const val MAX_ACTIVE_ASSEMBLIES = 8
        const val MAX_FRAMES = 4_096
        const val MAX_FRAME_PAYLOAD = 235
        const val MAX_ASSEMBLED_BYTES = 512 * 1024
    }
}
