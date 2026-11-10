package com.meshlink.app.core.mesh

import android.content.Context
import com.meshlink.app.core.cloud.CloudApiClient
import com.meshlink.app.data.database.CloudIncidentEntity
import com.meshlink.app.data.database.CloudSyncOutboxEntity
import com.meshlink.app.data.database.MeshDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable, consent-gated, idempotent event outbox. BLE and local SOS never call this transport. */
@Singleton
class CloudSyncCoordinator @Inject constructor(@ApplicationContext context: Context) {
    private val database = MeshDatabase.getDatabase(context)
    private val dao get() = database.emergencyCoordinationDao()
    private val syncMutex = Mutex()

    suspend fun stageConsentGrantedEmergencySummary(userConsent: Boolean, messageId: String, eventType: String, summary: String, ownerAccountId: String? = null) {
        require(userConsent) { "Explicit consent is required before cloud synchronization." }
        require(messageId.isNotBlank() && summary.isNotBlank())
        require(eventType in setOf("SOS", "EMERGENCY_REPORT", "COORDINATION_UPDATE"))
        val payload = JSONObject().put("schema_version", 1).put("message_id", messageId).put("summary", summary.take(2_000))
        enqueue(messageId, eventType, payload, System.currentTimeMillis(), ownerAccountId)
    }

    suspend fun stageCommunityReport(
        userConsent: Boolean, messageId: String, category: String, severity: Int, description: String,
        latitude: Double?, longitude: Double?, locationConsent: Boolean, affectedCount: Int?, assistance: String?, mediaId: String?, localImagePath: String? = null, imageMimeType: String? = null, ownerAccountId: String? = null
    ) {
        require(userConsent) { "Confirm cloud report sharing before submitting." }
        require(messageId.isNotBlank() && description.isNotBlank())
        require(category in setOf("MEDICAL", "FIRE", "FLOOD", "ROAD_HAZARD", "SECURITY", "EARTHQUAKE", "OTHER"))
        require(severity in 1..5)
        require(locationConsent || (latitude == null && longitude == null))
        require((latitude == null) == (longitude == null))
        val payload = JSONObject().put("schema_version", 1).put("category", category).put("severity", severity)
            .put("description", description.take(3_000)).put("locationConsent", locationConsent)
        if (locationConsent && latitude != null && longitude != null) { payload.put("latitude", latitude); payload.put("longitude", longitude) }
        if (affectedCount != null) payload.put("affectedCount", affectedCount.coerceIn(0,100_000))
        if (!assistance.isNullOrBlank()) payload.put("assistance", assistance.take(1_000))
        if (mediaId != null) payload.put("mediaId", mediaId)
        if (!localImagePath.isNullOrBlank() && !imageMimeType.isNullOrBlank()) { payload.put("localImagePath", localImagePath); payload.put("imageMimeType", imageMimeType) }
        enqueue(messageId, "EMERGENCY_REPORT", payload, System.currentTimeMillis(), ownerAccountId)
    }

    suspend fun stageCoordinationRecord(
        userConsent: Boolean, messageId: String, recordType: String, payloadJson: String, ownerAccountId: String? = null
    ) {
        require(userConsent) { "Explicit consent is required before sending a coordination record to the cloud." }
        require(recordType in setOf("INCIDENT", "PEOPLE", "RESOURCES", "VOLUNTEER", "SAFE_ZONE"))
        require(messageId.isNotBlank())
        val supplied = JSONObject(payloadJson)
        require(supplied.optString("record_type") == recordType) { "Record type does not match the submitted payload." }
        supplied.put("schema_version", 1)
        supplied.put("record_type", recordType)
        enqueue(messageId, "COORDINATION_UPDATE", supplied, System.currentTimeMillis(), ownerAccountId)
    }

    private suspend fun enqueue(messageId: String, eventType: String, payload: JSONObject, createdAt: Long, ownerAccountId: String?) {
        val stableId = UUID.nameUUIDFromBytes("meshlink:$eventType:$messageId".toByteArray(Charsets.UTF_8)).toString()
        dao.enqueueCloudEvent(CloudSyncOutboxEntity(stableId, messageId, eventType, payload.toString(), "PENDING", createdAt, updatedAt = createdAt, ownerAccountId = ownerAccountId))
    }

    suspend fun syncPending(api: CloudApiClient): Result<Int> = syncMutex.withLock {
        if (!api.isConfigured() || !api.isAuthenticated()) return@withLock Result.failure(IllegalStateException("Sign in and configure the cloud API before synchronizing."))
        var totalAccepted = 0
        try {
            val currentUser = api.me()
            repeat(10) {
                val now = System.currentTimeMillis()
                val pending = dao.pendingCloudEvents(now - 30_000L)
                if (pending.isEmpty()) return@withLock Result.success(totalAccepted)
                val eligible = mutableListOf<com.meshlink.app.data.database.CloudSyncOutboxEntity>()
                for (item in pending) {
                    when {
                        item.ownerAccountId == null -> { dao.bindCloudEventOwner(item.eventId, currentUser.id, now); eligible += item.copy(ownerAccountId = currentUser.id) }
                        item.ownerAccountId == currentUser.id -> eligible += item
                        else -> dao.blockCloudEventForAccount(item.eventId, "This queued report belongs to a different MeshLink account. Sign in with its original account to upload it.", now)
                    }
                }
                if (eligible.isEmpty()) return@withLock Result.failure(IllegalStateException("Queued reports are bound to a different cloud account. Sign in with the original account to synchronize them."))
                val payloads = eligible.map { item ->
                    val payload = JSONObject(item.payloadJson)
                    val localImagePath = payload.optString("localImagePath").takeIf { it.isNotBlank() && it != "null" }
                    if (payload.optString("mediaId").isBlank() && localImagePath != null) {
                        val file = java.io.File(localImagePath)
                        if (!file.isFile) throw IllegalStateException("Queued evidence image is missing; report retained for review.")
                        val mediaId = api.uploadImage(file, payload.optString("imageMimeType"), item.eventId)
                        payload.put("mediaId", mediaId); payload.remove("localImagePath"); payload.remove("imageMimeType")
                        dao.updateCloudPayload(item.eventId, payload.toString(), now)
                        file.delete()
                    }
                    JSONObject().put("eventId", item.eventId).put("messageId", item.messageId).put("eventType", item.eventType)
                        .put("payload", payload).put("createdAt", item.createdAt)
                }
                val accepted = api.syncEvents(payloads)
                eligible.forEach { item ->
                    if (item.eventId in accepted) {
                        dao.updateCloudEvent(item.eventId, "SYNCED", null, now)
                        dao.removeSyncedLocalIncident(item.messageId)
                    } else dao.updateCloudEvent(item.eventId, "PENDING", "Server did not acknowledge this event.", now)
                }
                totalAccepted += accepted.size
                if (accepted.size < eligible.size) return@withLock Result.success(totalAccepted)
            }
            Result.success(totalAccepted)
        } catch (error: Exception) {
            val now = System.currentTimeMillis()
            val needsUserAction = (error is com.meshlink.app.core.cloud.CloudApiException && error.statusCode in setOf(401, 403, 409)) || error.message.orEmpty().contains("different cloud account", ignoreCase = true)
            // Authentication/account errors wait for the user; transient network/server errors use retry backoff.
            if (!needsUserAction) dao.pendingCloudEvents(Long.MAX_VALUE).filter { it.status == "PENDING" }.forEach { item -> dao.updateCloudEvent(item.eventId, "PENDING", error.message?.take(300), now) }
            Result.failure(error)
        }
    }

    suspend fun pendingCount(): Int {
        dao.deleteOldSyncedCloudEvents(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        return dao.countPendingCloudEvents()
    }
    suspend fun cacheLocalReport(messageId: String, category: String, severity: Int, description: String, latitude: Double?, longitude: Double?, affectedCount: Int?) {
        val now = System.currentTimeMillis()
        dao.cacheCloudIncident(CloudIncidentEntity("local:$messageId", messageId, category, severity, description, latitude, longitude, affectedCount, null, null, "PENDING_SYNC", "UNVERIFIED", 1, now, now))
    }
    suspend fun cacheIncidents(items: List<com.meshlink.app.core.cloud.CloudIncident>) {
        dao.cacheCloudIncidents(items.map { CloudIncidentEntity(it.id,it.messageId,it.category,it.severity,it.description,it.latitude,it.longitude,it.affectedCount,it.assistance,it.mediaId,it.status,it.verification,it.version,it.reportedAt,it.updatedAt) })
    }
    suspend fun cachedIncidents(): List<CloudIncidentEntity> = dao.cachedCloudIncidents()
}
