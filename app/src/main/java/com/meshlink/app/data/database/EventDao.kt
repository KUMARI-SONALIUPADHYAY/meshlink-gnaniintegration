package com.meshlink.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(event: EventEntity)
    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit") fun observe(limit: Int = 300): Flow<List<EventEntity>>
    @Query("DELETE FROM events WHERE timestamp < :cutoff") suspend fun prune(cutoff: Long)
}
