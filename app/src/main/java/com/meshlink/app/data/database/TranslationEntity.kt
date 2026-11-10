package com.meshlink.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_translations")
data class TranslationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val provider: String,
    val contentKind: String,
    val createdAt: Long
)
