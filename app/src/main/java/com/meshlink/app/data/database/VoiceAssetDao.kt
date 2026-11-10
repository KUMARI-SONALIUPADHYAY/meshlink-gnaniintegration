package com.meshlink.app.data.database

import androidx.room.*
@Dao
interface VoiceAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(asset: VoiceAssetEntity)
    @Query("SELECT * FROM voice_assets WHERE messageId = :id LIMIT 1") suspend fun byId(id: String): VoiceAssetEntity?
    @Update suspend fun update(asset: VoiceAssetEntity)
}
