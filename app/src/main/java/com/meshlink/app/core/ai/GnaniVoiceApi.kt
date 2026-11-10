package com.meshlink.app.core.ai

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Compatibility facade; Prisma and Timbre remain separate provider clients. */
@Singleton
class GnaniVoiceApi @Inject constructor(
    private val prisma: PrismaApiClient,
    private val timbre: TimbreApiClient
) {
    fun isConfigured(): Boolean = prisma.isConfigured() && timbre.isConfigured()

    suspend fun transcribe(
        audioFile: File,
        languageCode: String = "hi-IN",
        requestId: String,
        onProgress: (PrismaProgress) -> Unit = {}
    ): PrismaTranscript = prisma.transcribe(audioFile, languageCode, requestId, onProgress)

    suspend fun synthesize(
        text: String,
        language: String = "hi-IN",
        voice: String = "Nalini",
        messageId: String
    ): TimbreSynthesis = timbre.synthesize(text, language, voice, messageId)
}
