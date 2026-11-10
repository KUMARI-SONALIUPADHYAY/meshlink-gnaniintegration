package com.meshlink.app.core.voice

import android.content.Context
import java.io.File
import java.util.UUID

/** Reassembles bounded voice chunks into the original local AAC/M4A asset. */
class VoiceReassembler(private val context: Context) {
    private data class Bucket(
        val total: Int,
        val durationMs: Long,
        val parts: MutableMap<Int, ByteArray>,
        val createdAt: Long,
        var byteCount: Int = 0
    )

    private val buckets = HashMap<UUID, Bucket>()

    @Synchronized
    fun accept(chunk: VoiceChunk): File? {
        val now = System.currentTimeMillis()
        buckets.entries.removeIf { now - it.value.createdAt > MAX_AGE_MS }

        if (chunk.total !in 1..MAX_CHUNKS) return null
        if (chunk.index !in 0 until chunk.total) return null
        if (chunk.durationMs !in 0L..MAX_DURATION_MS) return null
        if (chunk.data.isEmpty() || chunk.data.size > VoiceTransfer.CHUNK) return null

        val bucket = buckets[chunk.messageId] ?: run {
            if (buckets.size >= MAX_ACTIVE_TRANSFERS) {
                val oldest = buckets.minByOrNull { it.value.createdAt }?.key
                if (oldest != null) buckets.remove(oldest)
            }
            Bucket(chunk.total, chunk.durationMs, HashMap(), now).also {
                buckets[chunk.messageId] = it
            }
        }

        // Do not let inconsistent metadata keep an incomplete transfer alive.
        if (bucket.total != chunk.total || bucket.durationMs != chunk.durationMs) {
            buckets.remove(chunk.messageId)
            return null
        }

        val previousSize = bucket.parts[chunk.index]?.size ?: 0
        val nextSize = bucket.byteCount - previousSize + chunk.data.size
        if (nextSize > MAX_TRANSFER_BYTES) {
            buckets.remove(chunk.messageId)
            return null
        }

        bucket.parts[chunk.index] = chunk.data.copyOf()
        bucket.byteCount = nextSize
        if (bucket.parts.size != bucket.total) return null

        val assembled = ByteArray(bucket.byteCount)
        var offset = 0
        for (index in 0 until bucket.total) {
            val part = bucket.parts[index] ?: run {
                buckets.remove(chunk.messageId)
                return null
            }
            part.copyInto(assembled, destinationOffset = offset)
            offset += part.size
        }

        val output = File(context.filesDir, "voice_${chunk.messageId}.m4a")
        output.outputStream().use { it.write(assembled) }
        buckets.remove(chunk.messageId)
        return output
    }

    private companion object {
        const val MAX_ACTIVE_TRANSFERS = 4
        const val MAX_CHUNKS = 256
        const val MAX_TRANSFER_BYTES = 32 * 1024
        const val MAX_DURATION_MS = 15_000L
        const val MAX_AGE_MS = 2 * 60_000L
    }
}
