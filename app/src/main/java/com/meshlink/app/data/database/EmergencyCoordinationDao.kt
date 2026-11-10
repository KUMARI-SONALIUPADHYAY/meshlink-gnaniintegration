package com.meshlink.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmergencyCoordinationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAnalysis(item: EmergencyAnalysisEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTranslation(item: TranslationEntity)

    @Query("DELETE FROM ai_workflow_queue WHERE messageId = :messageId AND stage = :stage")
    suspend fun removeWorkflowStage(messageId: String, stage: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueWorkflow(item: AiWorkflowQueueEntity)

    @Query("SELECT * FROM ai_workflow_queue WHERE status = 'PENDING' ORDER BY updatedAt ASC")
    suspend fun pendingWorkflows(): List<AiWorkflowQueueEntity>

    @Query("DELETE FROM ai_workflow_queue WHERE jobId = :jobId")
    suspend fun completeWorkflow(jobId: String)

    @Query("DELETE FROM ai_workflow_queue WHERE messageId = :messageId AND stage = :stage")
    suspend fun completeWorkflowStage(messageId: String, stage: String)

    @Query("UPDATE ai_workflow_queue SET stage = :stage, sourceText = :sourceText, targetLanguage = :targetLanguage, targetVoice = :targetVoice, attempts = attempts + 1, lastError = :lastError, updatedAt = :updatedAt WHERE jobId = :jobId")
    suspend fun updateWorkflow(jobId: String, stage: String, sourceText: String, targetLanguage: String, targetVoice: String, lastError: String?, updatedAt: Long)

    @Query("UPDATE ai_workflow_queue SET status = 'FAILED', lastError = :error, updatedAt = :updatedAt WHERE jobId = :jobId")
    suspend fun failWorkflow(jobId: String, error: String?, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueCloudEvent(item: CloudSyncOutboxEntity)

    @Query("SELECT * FROM cloud_sync_outbox WHERE status IN ('PENDING','BLOCKED_ACCOUNT') AND (attempts = 0 OR updatedAt <= :retryBefore) ORDER BY createdAt ASC LIMIT 50")
    suspend fun pendingCloudEvents(retryBefore: Long): List<CloudSyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM cloud_sync_outbox WHERE status IN ('PENDING','BLOCKED_ACCOUNT')")
    suspend fun countPendingCloudEvents(): Int

    @Query("DELETE FROM cloud_sync_outbox WHERE status = 'SYNCED' AND updatedAt < :cutoff")
    suspend fun deleteOldSyncedCloudEvents(cutoff: Long)

    @Query("UPDATE cloud_sync_outbox SET ownerAccountId = :userId, status = 'PENDING', updatedAt = :updatedAt WHERE eventId = :eventId AND ownerAccountId IS NULL")
    suspend fun bindCloudEventOwner(eventId: String, userId: String, updatedAt: Long)

    @Query("UPDATE cloud_sync_outbox SET payloadJson = :payloadJson, updatedAt = :updatedAt WHERE eventId = :eventId")
    suspend fun updateCloudPayload(eventId: String, payloadJson: String, updatedAt: Long)

    @Query("UPDATE cloud_sync_outbox SET status = 'BLOCKED_ACCOUNT', lastError = :error, updatedAt = :updatedAt WHERE eventId = :eventId")
    suspend fun blockCloudEventForAccount(eventId: String, error: String, updatedAt: Long)

    @Query("UPDATE cloud_sync_outbox SET status = :status, attempts = attempts + 1, lastError = :error, updatedAt = :updatedAt WHERE eventId = :eventId")
    suspend fun updateCloudEvent(eventId: String, status: String, error: String?, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cacheCloudIncident(item: CloudIncidentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cacheCloudIncidents(items: List<CloudIncidentEntity>)

    @Query("SELECT * FROM cloud_incidents ORDER BY reportedAt DESC LIMIT 100")
    suspend fun cachedCloudIncidents(): List<CloudIncidentEntity>

    @Query("DELETE FROM cloud_incidents WHERE messageId = :messageId AND status = 'PENDING_SYNC'")
    suspend fun removeSyncedLocalIncident(messageId: String)
}
