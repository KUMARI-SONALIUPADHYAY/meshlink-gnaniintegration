package com.meshlink.app.core.cloud

import android.content.Context
import android.net.Uri
import com.meshlink.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudApiException(val statusCode: Int, val apiCode: String, message: String) : Exception(message)

/** Thin HTTPS-only API client. Authentication tokens are stored encrypted in Android Keystore. */
class CloudApiClient(private val context: Context) {
    private val tokenStore = EncryptedCloudTokenStore(context)
    private val baseUrl = BuildConfig.CLOUD_API_BASE_URL.trim().trimEnd('/')
    fun isConfigured(): Boolean = baseUrl.startsWith("https://")
    fun isAuthenticated(): Boolean = tokenStore.read() != null
    fun accountId(): String? = tokenStore.readUserId()
    fun logout() = tokenStore.clear()

    suspend fun authenticate(email: String, password: String, displayName: String?, register: Boolean): CloudUser = withContext(Dispatchers.IO) {
        ensureConfigured()
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (register) body.put("displayName", displayName.orEmpty())
        val result = request(if (register) "POST" else "POST", if (register) "/api/auth/register" else "/api/auth/login", body, authenticated = false)
        val token = result.getString("token")
        tokenStore.save(token, result.getJSONObject("user").getString("id"))
        parseUser(result.getJSONObject("user"))
    }

    suspend fun me(): CloudUser = withContext(Dispatchers.IO) { parseUser(request("GET", "/api/me").getJSONObject("user")) }

    suspend fun coordinationSummary(): CoordinationSummary = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/coordination/summary")
        val resources = json.optJSONObject("resourceDemand") ?: JSONObject()
        CoordinationSummary(
            records = json.optInt("records"), incidentReports = json.optInt("incidentReports"),
            peopleReported = json.optInt("peopleReported"), children = json.optInt("children"),
            elderly = json.optInt("elderly"), injured = json.optInt("injured"),
            disabilitySupport = json.optInt("disabilitySupport"), pregnant = json.optInt("pregnant"),
            volunteersAvailable = json.optInt("volunteersAvailable"), foodKits = resources.optInt("food_kits"),
            waterLitres = resources.optInt("water_litres"), firstAidKits = resources.optInt("first_aid_kits"),
            blankets = resources.optInt("blankets"), shelterSpaces = resources.optInt("shelter_spaces"),
            note = json.optString("note")
        )
    }

    suspend fun listIncidents(latitude: Double? = null, longitude: Double? = null): List<CloudIncident> = withContext(Dispatchers.IO) {
        val query = if (latitude != null && longitude != null) "?lat=$latitude&lng=$longitude&radiusKm=30" else ""
        val array = request("GET", "/api/incidents$query").getJSONArray("incidents")
        (0 until array.length()).map { parseIncident(array.getJSONObject(it)) }
    }

    suspend fun createIncident(
        clientEventId: String, messageId: String, category: String, severity: Int, description: String,
        latitude: Double?, longitude: Double?, locationConsent: Boolean, affectedCount: Int?, assistance: String?, mediaId: String?
    ): CloudIncident = withContext(Dispatchers.IO) {
        val body = JSONObject().put("clientEventId", clientEventId).put("messageId", messageId)
            .put("category", category).put("severity", severity).put("description", description)
            .put("locationConsent", locationConsent).put("reportedAt", java.time.Instant.now().toString())
        if (locationConsent && latitude != null && longitude != null) { body.put("latitude", latitude); body.put("longitude", longitude) }
        if (affectedCount != null) body.put("affectedCount", affectedCount)
        if (!assistance.isNullOrBlank()) body.put("assistance", assistance)
        if (mediaId != null) body.put("mediaId", mediaId)
        parseIncident(request("POST", "/api/incidents", body).getJSONObject("incident"))
    }

    suspend fun updateStatus(incident: CloudIncident, status: String): CloudIncident = withContext(Dispatchers.IO) {
        val body = JSONObject().put("status", status).put("expectedVersion", incident.version)
        parseIncident(request("PATCH", "/api/incidents/${incident.id}/status", body).getJSONObject("incident"))
    }

    suspend fun updateVerification(incident: CloudIncident, verification: String): CloudIncident = withContext(Dispatchers.IO) {
        val body = JSONObject().put("verification", verification).put("expectedVersion", incident.version)
        parseIncident(request("PATCH", "/api/incidents/${incident.id}/verification", body).getJSONObject("incident"))
    }

    suspend fun dispute(incidentId: String, reason: String): Unit = withContext(Dispatchers.IO) {
        request("POST", "/api/incidents/$incidentId/dispute", JSONObject().put("reason", reason)); Unit
    }

    suspend fun uploadImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: throw IllegalArgumentException("Image format is unknown.")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 5 * 1024 * 1024) { "Image exceeds 5 MB." }; out.write(buffer,0,n) }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Selected image could not be opened.")
        uploadImageBytes(bytes, mime, UUID.randomUUID().toString())
    }

    suspend fun uploadImage(file: File, mime: String, uploadId: String = UUID.randomUUID().toString()): String = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..(5L * 1024 * 1024)) { "Image is missing or exceeds 5 MB." }
        require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Only JPEG, PNG and WebP images are supported." }
        uploadImageBytes(file.readBytes(), mime, uploadId)
    }

    private fun uploadImageBytes(bytes: ByteArray, mime: String, uploadId: String): String {
        require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Only JPEG, PNG and WebP images are supported." }
        val boundary = "MeshLink-${UUID.randomUUID()}"
        val connection = open("POST", "/api/media", true)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.doOutput = true
        connection.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"clientUploadId\"\r\n\r\n$uploadId\r\n".toByteArray())
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"evidence\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            out.write(bytes); out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return parseResponse(connection).getString("mediaId")
    }

    suspend fun downloadMedia(mediaId: String): File = withContext(Dispatchers.IO) {
        val connection = open("GET", "/api/media/$mediaId", true)
        try {
            val status = connection.responseCode
            if (status !in 200..299) { parseResponse(connection); throw IllegalStateException("Evidence download failed.") }
            val mime = connection.contentType?.substringBefore(';') ?: "application/octet-stream"
            require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Server returned an unsupported evidence format." }
            val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "webp" }
            val evidenceDirectory = File(context.cacheDir, "cloud_evidence").apply { mkdirs() }
            val destination = File(evidenceDirectory, "evidence_${UUID.randomUUID()}.$extension")
            connection.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 5L * 1024L * 1024L) { "Evidence exceeds the 5 MB safety limit." }; output.write(buffer,0,n) }
            } }
            destination
        } finally { connection.disconnect() }
    }

    suspend fun syncEvents(events: List<JSONObject>): Set<String> = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext emptySet()
        val array = JSONArray(); events.forEach { event -> array.put(event as Any) }
        val results = request("POST", "/api/sync/events", JSONObject().put("events", array)).getJSONArray("results")
        buildSet { for (i in 0 until results.length()) { val item = results.getJSONObject(i); if (item.optBoolean("accepted")) add(item.getString("eventId")) } }
    }

    private fun request(method: String, path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject {
        val connection = open(method, path, authenticated)
        if (body != null) { connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        return parseResponse(connection)
    }

    private fun open(method: String, path: String, authenticated: Boolean): HttpURLConnection {
        ensureConfigured()
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 12_000; readTimeout = 20_000; useCaches = false
            setRequestProperty("Accept", "application/json")
            if (authenticated) { val token = tokenStore.read() ?: throw CloudApiException(401,"AUTH_REQUIRED","Sign in to use cloud services."); setRequestProperty("Authorization", "Bearer $token") }
        }
        return connection
    }

    private fun parseResponse(connection: HttpURLConnection): JSONObject {
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                if (status == 401) tokenStore.clear()
                val code = json.optString("error", "HTTP_$status")
                val message = when (code) {
                    "INVALID_CREDENTIALS" -> "Email or password is incorrect."
                    "INVALID_OR_EXPIRED_TOKEN", "AUTH_REQUIRED" -> "Your cloud session expired. Please sign in again."
                    "INSUFFICIENT_ROLE" -> "Only an authorized responder can update incident status."
                    "VERSION_CONFLICT" -> "This incident changed on the server. Refresh and try again."
                    "VALIDATION_ERROR" -> "The server rejected some report fields. Check the information and retry."
                    else -> "Cloud service request failed ($status)."
                }
                throw CloudApiException(status, code, message)
            }
            return json
        } finally { connection.disconnect() }
    }

    private fun ensureConfigured() { if (!isConfigured()) throw IllegalStateException("Cloud backend is not configured. Set cloud.api.base.url to your HTTPS server in local.properties.") }
    private fun parseUser(json: JSONObject) = CloudUser(json.getString("id"), json.getString("email"), json.getString("displayName"), json.getString("role"))
    private fun parseIncident(j: JSONObject): CloudIncident {
        val reporter = j.optJSONObject("reporter") ?: JSONObject()
        return CloudIncident(j.getString("id"), j.getString("messageId"), j.getString("category"), j.optInt("severity",2), j.getString("description"), if (j.isNull("latitude")) null else j.optDouble("latitude"), if (j.isNull("longitude")) null else j.optDouble("longitude"), if (j.isNull("affectedCount")) null else j.optInt("affectedCount"), j.optString("assistance").takeIf { it.isNotBlank() && it != "null" }, j.getString("status"), j.getString("verification"), j.optInt("version",1), parseTime(j.getString("reportedAt")), parseTime(j.getString("updatedAt")), reporter.optString("id",""), reporter.optString("displayName","Community member"), reporter.optString("role","COMMUNITY"), j.optString("mediaId").takeIf { it.isNotBlank() && it != "null" })
    }
    private fun parseTime(value: String): Long = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value)?.time }.getOrNull() ?: System.currentTimeMillis()
}
