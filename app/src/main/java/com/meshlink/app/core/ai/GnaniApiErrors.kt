package com.meshlink.app.core.ai

import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

enum class GnaniApiError {
    MISSING_CREDENTIAL, INVALID_CREDENTIAL, PERMISSION_DENIED, RATE_LIMITED,
    UNSUPPORTED_AUDIO, NETWORK, SERVER, INVALID_RESPONSE, REQUEST
}

class GnaniApiException(
    message: String,
    val error: GnaniApiError? = null,
    val httpStatus: Int? = null
) : Exception(message)

internal object GnaniApiErrors {
    fun fromHttp(status: Int, responseBody: String, service: String, apiKey: String): GnaniApiException {
        val parsed = runCatching { JSONObject(responseBody) }.getOrNull()
        val detail = parsed?.optJSONObject("detail")
        val errorObject = parsed?.optJSONObject("error")
        val message = sequenceOf(
            errorObject?.optString("message"),
            detail?.optString("message"),
            parsed?.optString("message"),
            parsed?.optString("detail")
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
        val code = sequenceOf(
            errorObject?.optString("type"),
            detail?.optString("error_code"),
            parsed?.optString("code")
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
        val redactedMessage = if (apiKey.isNotEmpty()) message.replace(apiKey, "[redacted]") else message
        val safeMessage = redactedMessage
            .replace(Regex("(?i)(api[-_ ]?key|authorization)\\s*[:=]\\s*[^,;\\s]+"), "[redacted]")
            .take(180)

        val (kind, fallback) = when (status) {
            400, 422 -> when (code) {
                "UNSUPPORTED_AUDIO_FORMAT", "AUDIO_CONVERSION_ERROR", "MAX_AUDIO_DURATION_EXCEEDED", "EMPTY_AUDIO" ->
                    GnaniApiError.UNSUPPORTED_AUDIO to "$service could not process this audio. Check the selected media format and length."
                "INVALID_LANGUAGE_CODE" -> GnaniApiError.REQUEST to "The selected language is not supported by $service. Choose a supported language code."
                else -> GnaniApiError.REQUEST to "The $service request was rejected. Check the request fields and supported options."
            }
            401 -> GnaniApiError.INVALID_CREDENTIAL to "Gnani rejected the API key. Verify the local key and confirm it is active."
            403 -> GnaniApiError.PERMISSION_DENIED to "This Gnani key does not have permission for $service. Check its dashboard permissions."
            402 -> GnaniApiError.PERMISSION_DENIED to "Gnani account credits are insufficient. Check your dashboard balance."
            413 -> GnaniApiError.REQUEST to "Gnani $service rejected the request because its payload is too large. Shorten the text or choose a smaller input."
            415 -> if (service == "Prisma") {
                GnaniApiError.UNSUPPORTED_AUDIO to "The audio format is not supported by Prisma. Choose WAV, MP3, OGG, FLAC, AAC or M4A."
            } else {
                GnaniApiError.REQUEST to "Timbre rejected the request media type or output format. Check the JSON request and WAV configuration."
            }
            429 -> GnaniApiError.RATE_LIMITED to "Gnani rate limit reached. Wait briefly, then retry."
            504 -> GnaniApiError.SERVER to "Gnani $service timed out. Retry when the service is available."
            in 500..599 -> GnaniApiError.SERVER to "Gnani $service is temporarily unavailable. Try again later."
            else -> GnaniApiError.REQUEST to "Gnani $service request failed (HTTP $status)."
        }
        val detailSuffix = if (safeMessage.isBlank()) "" else " Details: $safeMessage"
        return GnaniApiException("$fallback$detailSuffix", error = kind, httpStatus = status)
    }

    fun fromNetwork(error: IOException): GnaniApiException = when (error) {
        is SocketTimeoutException -> GnaniApiException("Gnani request timed out. Check your connection and retry.", error = GnaniApiError.NETWORK)
        else -> GnaniApiException("Could not reach Gnani. Check internet access and try again.", error = GnaniApiError.NETWORK)
    }
}
