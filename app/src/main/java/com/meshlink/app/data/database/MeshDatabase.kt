package com.meshlink.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, PeerEntity::class, EventEntity::class, VoiceAssetEntity::class, VoiceTranscriptionEntity::class, GeneratedSpeechEntity::class, EmergencyAnalysisEntity::class, TranslationEntity::class, AiWorkflowQueueEntity::class, CloudSyncOutboxEntity::class, CloudIncidentEntity::class],
    version = 6,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun eventDao(): EventDao
    abstract fun voiceAssetDao(): VoiceAssetDao
    abstract fun voiceTranscriptionDao(): VoiceTranscriptionDao
    abstract fun generatedSpeechDao(): GeneratedSpeechDao
    abstract fun emergencyCoordinationDao(): EmergencyCoordinationDao

    companion object {
        @Volatile private var INSTANCE: MeshDatabase? = null
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS voice_transcriptions (messageId TEXT NOT NULL, transcript TEXT NOT NULL, languageCode TEXT NOT NULL, provider TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(messageId))")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS generated_speech (messageId TEXT NOT NULL, localPath TEXT NOT NULL, languageCode TEXT NOT NULL, voice TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(messageId))")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS emergency_analyses (messageId TEXT NOT NULL PRIMARY KEY, emergencyType TEXT NOT NULL, severity TEXT NOT NULL, locationMentioned TEXT, peopleAffected INTEGER, immediateAssistance TEXT NOT NULL, instructionsJson TEXT NOT NULL, warningsJson TEXT NOT NULL, suggestedResponse TEXT NOT NULL, rawJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS emergency_translations (id TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, sourceText TEXT NOT NULL, translatedText TEXT NOT NULL, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL, provider TEXT NOT NULL, contentKind TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS ai_workflow_queue (jobId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, audioPath TEXT NOT NULL, sourceText TEXT NOT NULL, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL, targetVoice TEXT NOT NULL, stage TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT, updatedAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ai_workflow_queue_messageId ON ai_workflow_queue(messageId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS cloud_sync_outbox (eventId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, eventType TEXT NOT NULL, payloadJson TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cloud_sync_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE cloud_sync_outbox ADD COLUMN lastError TEXT")
                database.execSQL("ALTER TABLE cloud_sync_outbox ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE cloud_sync_outbox ADD COLUMN ownerAccountId TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_sync_outbox_status_createdAt ON cloud_sync_outbox(status, createdAt)")
                database.execSQL("CREATE TABLE IF NOT EXISTS cloud_incidents (incidentId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, category TEXT NOT NULL, severity INTEGER NOT NULL, description TEXT NOT NULL, latitude REAL, longitude REAL, affectedCount INTEGER, assistance TEXT, mediaId TEXT, status TEXT NOT NULL, verification TEXT NOT NULL, version INTEGER NOT NULL, reportedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                database.execSQL("UPDATE cloud_sync_outbox SET updatedAt = createdAt WHERE updatedAt = 0")
            }
        }
        fun getDatabase(context: Context): MeshDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, MeshDatabase::class.java, "meshlink_database"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
    }
}
