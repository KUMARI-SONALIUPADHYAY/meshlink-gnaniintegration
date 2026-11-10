package com.meshlink.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPeer(peer: PeerEntity)
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC") fun observeAll(): Flow<List<PeerEntity>>
    @Query("SELECT * FROM peers WHERE nodeId = :nodeId LIMIT 1") suspend fun byId(nodeId: String): PeerEntity?
    @Query("DELETE FROM peers WHERE lastSeen < :cutoff") suspend fun deleteStale(cutoff: Long)
}
