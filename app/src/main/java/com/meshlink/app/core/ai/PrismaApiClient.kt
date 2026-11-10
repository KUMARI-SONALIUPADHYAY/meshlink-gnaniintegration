package com.meshlink.app.core.ai

import android.media.MediaMetadataRetriever
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Gnani Prisma v2.5 REST client. Its multipart contract is isolated from Timbre. */
@Singleton
class PrismaApiClient @Inject constructor(private val credentials: GnaniApiCredentials) {
    fun isConfigured(): Boolean = credentials.isConfigured()

    suspend fun transcribe(
        audioFile: File,
        languageCode: String = "hi-IN",
        requestId: String = UUID.randomUUID().toString(),
        onProgress: (PrismaProgress) -> Unit = {}
    ): PrismaTranscript = withContext(Dispatchers.IO) {
        val key = credentials.authenticationHeaderValue()
        validateAudio(audioFile)
        require(languageCode in SUPPORTED_LANGUAGES) {
            "Prisma v2.5 supports bn-IN, en-IN, gu-IN, hi-IN, kn-IN, ml-IN, mr-IN, pa-IN, ta-IN and te-IN."
        }
        val boundary = "MeshLink-${UUID.randomUUID()}"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("X-API-Key-ID", key)
            setRequestProperty("X-API-Request-ID", requestId)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setChunkedStreamingMode(64 * 1024)
        }
        try {
            onProgress(PrismaProgress.UPLOADING)
            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { out ->
                writeField(out, boundary, "language_code", languageCode)
                // Prisma v2.5 is the documented default when model is omitted.
                writeField(out, boundary, "format", "transcribe")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"audio_file\"; filename=\"${safeFilename(audioFile)}\"\r\n")
                out.writeBytes("Content-Type: ${supportedAudioMime(audioFile)}\r\n\r\n")
                audioFile.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            onProgress(PrismaProgress.PROCESSING)
            val status = connection.responseCode
            val body = responseText(connection, status)
            if (status !in 200..299) throw GnaniApiErrors.fromHttp(status, body, "Prisma", key)
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw GnaniApiException("Prisma returned an unreadable response.", error = GnaniApiError.INVALID_RESPONSE)
            }
            if (!json.optBoolean("success", false)) {
                val error = json.optJSONObject("error")
                val type = error?.optString("type").orEmpty()
                val message = error?.optString("message").orEmpty()
                throw GnaniApiException(
                    "Prisma could not transcribe this recording${if (type.isNotBlank()) " ($type)" else ""}.${if (message.isNotBlank()) " $message" else ""}",
                    error = GnaniApiError.REQUEST
                )
            }
            // Prisma v2.5 documents transcript as a string; an empty string is valid
            // for audio containing no detectable speech (e.g. clips shorter than 0.1 s).
            if (!json.has("transcript") || json.isNull("transcript") || !json.opt("transcript").let { it is String }) {
                throw GnaniApiException("Prisma response did not contain a transcript string.", error = GnaniApiError.INVALID_RESPONSE)
            }
            PrismaTranscript(
                text = json.getString("transcript"),
                requestId = json.optString("request_id").takeIf { it.isNotBlank() } ?: requestId,
                model = json.optString("model").takeIf { it.isNotBlank() } ?: "prisma-v2.5",
                truncated = json.optBoolean("truncated", false)
            )
        } catch (e: GnaniApiException) {
            throw e
        } catch (e: IOException) {
            throw GnaniApiErrors.fromNetwork(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateAudio(file: File) {
        require(file.isFile && file.length() > 0L) { "Choose a non-empty voice recording first." }
        require(file.length() <= MAX_AUDIO_BYTES) {
            "This recording is larger than MeshLink's 25 MB local safety limit. Choose a shorter or smaller file."
        }
        supportedAudioMime(file) // Reject unsupported extensions before network use.
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (durationMs != null) {
            require(durationMs in 1L..MAX_DURATION_MS) {
                "Prisma v2.5 accepts audio clips up to 30 seconds. Trim this recording before transcription."
            }
        }
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun responseText(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(8_192) }.orEmpty()
    }

    private fun safeFilename(file: File): String = "voice.${file.extension.lowercase().filter { it.isLetterOrDigit() }.take(8)}"

    private fun supportedAudioMime(file: File): String = when (file.extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> throw GnaniApiException(
            "Unsupported audio format '.${file.extension}'. Prisma supports WAV, MP3, OGG, FLAC, AAC and M4A.",
            error = GnaniApiError.UNSUPPORTED_AUDIO
        )
    }

    companion object {
        const val ENDPOINT = "https://api.vachana.ai/stt/v3"
        private const val MAX_DURATION_MS = 30_000L
        private val SUPPORTED_LANGUAGES = setOf("bn-IN", "en-IN", "gu-IN", "hi-IN", "kn-IN", "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN")
        // Local application guardrail, not a provider-published byte limit.
        private const val MAX_AUDIO_BYTES = 25L * 1024L * 1024L
    }
}

enum class PrismaProgress { UPLOADING, PROCESSING }

data class PrismaTranscript(
    val text: String,
    val requestId: String,
    val model: String,
    val truncated: Boolean
)
