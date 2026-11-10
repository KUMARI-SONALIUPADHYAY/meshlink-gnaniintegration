package com.meshlink.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessage(message: MessageEntity)
    @Query("SELECT * FROM messages ORDER BY createdAt ASC") fun observeAll(): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE messageId = :id LIMIT 1") suspend fun byId(id: String): MessageEntity?
    @Query("UPDATE messages SET delivered = 1, status = 'DELIVERED' WHERE messageId = :id") suspend fun markDelivered(id: String)
    @Query("UPDATE messages SET status = :status, lastError = :error WHERE messageId = :id") suspend fun updateStatus(id: String, status: String, error: String? = null)
    @Query("UPDATE messages SET payloadText = :text WHERE messageId = :id") suspend fun updatePayloadText(id: String, text: String?)
    @Query("UPDATE messages SET payloadReference = :path, delivered = 1, status = 'DELIVERED' WHERE messageId = :id") suspend fun attachPayloadFile(id: String, path: String)
    @Query("UPDATE messages SET senderNodeId = :senderNodeId, status = :status, ttl = :ttl, hopCount = :hopCount, payloadReference = :payloadReference, payloadText = :payloadText, expiresAt = :expiresAt WHERE messageId = :id")
    suspend fun updateRoutingState(id: String, senderNodeId: String, status: String, ttl: Int, hopCount: Int, payloadReference: String?, payloadText: String?, expiresAt: Long)
    @Query("SELECT * FROM messages WHERE status IN ('QUEUED','FORWARDED','HELD') AND expiresAt > :now ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun queued(now: Long, limit: Int): List<MessageEntity>
    @Query("DELETE FROM messages WHERE expiresAt <= :now AND status NOT IN ('DELIVERED')") suspend fun deleteExpired(now: Long)
    @Query("SELECT COUNT(*) FROM messages WHERE status IN ('QUEUED','FORWARDED')") suspend fun queueSize(): Int
    @Query("SELECT * FROM messages WHERE status IN ('HELD','BLOCKED') ORDER BY createdAt DESC") fun observeSafety(): Flow<List<MessageEntity>>
    @Query("DELETE FROM messages WHERE messageId = (SELECT messageId FROM messages WHERE status IN ('QUEUED','FORWARDED') ORDER BY priority ASC, createdAt ASC LIMIT 1)") suspend fun deleteLowestPriority()
}
