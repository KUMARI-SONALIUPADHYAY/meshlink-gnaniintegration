package com.meshlink.app.core.ai

import com.meshlink.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Client for a user-provisioned vLLM/SGLang OpenAI-compatible Evon endpoint. */
@Singleton
class EvonApiClient @Inject constructor() {
    private val baseUrl = BuildConfig.EVON_BASE_URL.trim().trimEnd('/')
    private val model = BuildConfig.EVON_MODEL.trim().ifBlank { "gnani/gnani-evon-v3.3-30B-A3B" }
    private val accessToken = BuildConfig.EVON_API_KEY.trim()

    fun isConfigured(): Boolean = baseUrl.startsWith("https://", ignoreCase = true)

    /** Requests a constrained emergency report and validates the returned JSON locally. */
    suspend fun analyzeEmergency(transcription: String, messageId: String): EvonEmergencyAnalysis {
        require(messageId.isNotBlank()) { "An emergency message ID is required." }
        require(transcription.isNotBlank()) { "Prisma transcription is empty. There is nothing to analyze." }
        val prompt = """Analyze this user-provided emergency transcription. Return ONLY one JSON object with exactly these fields: {"emergency_type":string,"severity":"unknown|low|moderate|high|critical","location":string|null,"people_affected":integer|null,"immediate_assistance":string,"instructions":string[],"warnings":string[],"suggested_response":string}. Use null or "unknown" when information is not stated. Do not invent a location, people count, injuries, resources, or facts. Distinguish quoted facts from uncertainty. Never claim responders have been contacted. Keep instructions concise and safe. This is advisory and requires human review.\nTRANSCRIPTION:\n$transcription"""
        val raw = complete(prompt, "You are a strict emergency information extractor. Return only valid JSON matching the requested schema. Never invent missing facts. Use null for unknown values.")
        return EvonEmergencyAnalysis.parse(raw)
    }

    suspend fun ask(prompt: String): String = complete(prompt, "You are MeshLink's multilingual emergency communication assistant. Respond in the same language and script as the user. Be concise, practical, and clear. Do not claim to dispatch emergency services, verify real-world facts, or replace emergency responders. For immediate danger, tell the user to contact local emergency services.")

    private suspend fun complete(prompt: String, systemPrompt: String): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw GnaniApiException(
            "Evon is not configured. Add an HTTPS evon.base.url for your self-hosted inference server to local.properties.",
            error = GnaniApiError.MISSING_CREDENTIAL
        )
        require(prompt.isNotBlank()) { "Enter a question for Evon." }
        require(prompt.length <= 16_000) { "Evon prompt is too long. Keep it under 16,000 characters." }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", prompt))
            })
            put("max_tokens", 1024)
            put("temperature", 0.6)
            put("top_p", 0.95)
            put("stream", false)
        }
        val connection = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(64 * 1024) }.orEmpty()
            if (status !in 200..299) {
                val redactedBody = if (accessToken.isNotEmpty()) body.replace(accessToken, "[redacted]") else body
                val safe = redactedBody.take(220)
                throw GnaniApiException("Evon inference request failed (HTTP $status). ${safe.ifBlank { "Check the configured inference server." }}", error = when (status) {
                    401, 403 -> GnaniApiError.PERMISSION_DENIED
                    in 500..599 -> GnaniApiError.SERVER
                    else -> GnaniApiError.REQUEST
                }, httpStatus = status)
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw GnaniApiException("Evon returned an unreadable response.", error = GnaniApiError.INVALID_RESPONSE)
            }
            val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
            if (content.isBlank()) throw GnaniApiException("Evon returned no assistant text.", error = GnaniApiError.INVALID_RESPONSE)
            content
        } catch (error: GnaniApiException) {
            throw error
        } catch (error: IOException) {
            throw GnaniApiErrors.fromNetwork(error)
        } finally {
            connection.disconnect()
        }
    }
}
