package com.meshlink.app.core.ai

import android.text.Html
import android.os.Build
import com.meshlink.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Google Cloud Translation Basic v2 client; credentials are supplied only by local debug configuration. */
@Singleton
class CloudTranslationClient @Inject constructor() {
    private val apiKey = BuildConfig.TRANSLATION_API_KEY.trim()
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    suspend fun translate(text: String, targetLanguage: String, sourceLanguage: String? = null): TranslationResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Google Cloud Translation is not configured. Add translation.api.key locally." }
        require(text.isNotBlank()) { "There is no text to translate." }
        require(text.length <= 8_000) { "Translation input exceeds MeshLink's 8,000-character safety limit." }
        require(targetLanguage.matches(Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$"))) { "Choose a valid target language code." }
        val payload = JSONObject().apply {
            put("q", text)
            put("target", targetLanguage)
            put("format", "text")
            if (!sourceLanguage.isNullOrBlank()) put("source", sourceLanguage.substringBefore('-'))
        }
        val connection = (URL("$ENDPOINT?key=${encode(apiKey)}").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(64 * 1024) }.orEmpty()
            if (status !in 200..299) {
                val safe = when (status) {
                    400 -> "Google Translation rejected the language or request."
                    401, 403 -> "Google Translation rejected the API key or API permissions."
                    429 -> "Google Translation quota or rate limit reached."
                    in 500..599 -> "Google Translation is temporarily unavailable."
                    else -> "Google Translation failed (HTTP $status)."
                }
                throw GnaniApiException(safe, error = if (status in 500..599) GnaniApiError.SERVER else GnaniApiError.REQUEST, httpStatus = status)
            }
            val json = runCatching { JSONObject(body) }.getOrElse { throw GnaniApiException("Google Translation returned invalid JSON.", error = GnaniApiError.INVALID_RESPONSE) }
            val item = json.optJSONObject("data")?.optJSONArray("translations")?.optJSONObject(0)
                ?: throw GnaniApiException("Google Translation response did not contain a translation.", error = GnaniApiError.INVALID_RESPONSE)
            val htmlText = item.optString("translatedText")
            if (htmlText.isBlank()) throw GnaniApiException("Google Translation returned empty text.", error = GnaniApiError.INVALID_RESPONSE)
            val translated = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY).toString() else Html.fromHtml(htmlText).toString()
            TranslationResult(text, translated, sourceLanguage ?: item.optString("detectedSourceLanguage").ifBlank { "und" }, targetLanguage, "Google Cloud Translation Basic v2")
        } catch (e: GnaniApiException) {
            throw e
        } catch (e: IOException) {
            throw GnaniApiException("Could not reach Google Cloud Translation. The source text is preserved.", error = GnaniApiError.NETWORK)
        } finally { connection.disconnect() }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object { const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2" }
}

data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val provider: String
)
