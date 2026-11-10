package com.meshlink.app.core.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Gnani Timbre v2.5 REST client. Sends the documented JSON contract and receives raw WAV bytes. */
@Singleton
class TimbreApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: GnaniApiCredentials
) {
    fun isConfigured(): Boolean = credentials.isConfigured()

    suspend fun synthesize(
        text: String,
        language: String = "hi-IN",
        voice: String = "Nalini",
        messageId: String
    ): TimbreSynthesis = withContext(Dispatchers.IO) {
        val key = credentials.authenticationHeaderValue()
        require(text.isNotBlank()) { "Enter the response text to speak." }
        require(text.length <= MAX_TEXT_CHARACTERS) {
            "Text exceeds MeshLink's local 4,000-character limit. Split it into shorter messages."
        }
        require(TimbreCatalog.isSupported(language, voice)) {
            val supported = TimbreCatalog.voicesFor(language).joinToString(", ")
            if (supported.isBlank()) "Timbre v2.5 does not support language '$language'. Choose one of the listed languages."
            else "Voice '$voice' is not in the Timbre v2.5 catalog for $language. Available voices: $supported."
        }
        require(messageId.isNotBlank()) { "A message ID is required to associate generated speech." }

        val payload = JSONObject().apply {
            put("text", text)
            put("voice", voice)
            put("model", MODEL)
            put("language", language)
            put("speed", 1.0)
            put("audio_config", JSONObject().apply {
                put("sample_rate", 48_000)
                put("num_channels", 1)
                put("sample_width", 2)
                put("encoding", "linear_pcm")
                put("container", "wav")
            })
        }
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("X-API-Key-ID", key)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/wav")
        }

        val directory = File(context.filesDir, "generated_speech").apply { mkdirs() }
        val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "message" }
        val destination = File(directory, "${safeId}_timbre_v25.wav")
        val temporary = File(directory, "${safeId}_timbre_v25.wav.part")
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(MAX_ERROR_BODY_CHARS) }.orEmpty()
                throw GnaniApiErrors.fromHttp(status, body, "Timbre", key)
            }

            // Official REST contract: HTTP 200 returns raw binary audio, not a JSON envelope.
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType !in SUPPORTED_WAV_CONTENT_TYPES) {
                throw GnaniApiException(
                    "Timbre returned an unexpected content type ('$contentType'). MeshLink requested WAV audio.",
                    error = GnaniApiError.INVALID_RESPONSE
                )
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_AUDIO_RESPONSE_BYTES) {
                            throw GnaniApiException(
                                "Timbre audio exceeded MeshLink's local 25 MB safety limit.",
                                error = GnaniApiError.INVALID_RESPONSE
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!isValidWav(temporary)) {
                throw GnaniApiException(
                    "Timbre response did not contain a valid WAV file. No generated audio was saved.",
                    error = GnaniApiError.INVALID_RESPONSE
                )
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the previous generated speech file.")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not save the generated speech file locally.")
            }
            TimbreSynthesis(
                messageId = messageId,
                file = destination,
                model = MODEL,
                language = language,
                voice = voice
            )
        } catch (error: GnaniApiException) {
            temporary.delete()
            throw error
        } catch (error: IOException) {
            temporary.delete()
            throw GnaniApiErrors.fromNetwork(error)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidWav(file: File): Boolean {
        if (!file.isFile || file.length() < 44L) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                input.read(header) == 12 &&
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            }
        }.getOrDefault(false)
    }

    companion object {
        const val ENDPOINT = "https://api.vachana.ai/api/v1/tts/inference"
        const val MODEL = "timbre-v2.5"
        private const val MAX_TEXT_CHARACTERS = 4_000 // MeshLink guardrail; not a provider-published limit.
        private const val MAX_AUDIO_RESPONSE_BYTES = 25L * 1024L * 1024L
        private const val MAX_ERROR_BODY_CHARS = 8_192
        private val SUPPORTED_WAV_CONTENT_TYPES = setOf("audio/wav", "audio/x-wav", "application/octet-stream")
    }
}

data class TimbreSynthesis(
    val messageId: String,
    val file: File,
    val model: String,
    val language: String,
    val voice: String
)
