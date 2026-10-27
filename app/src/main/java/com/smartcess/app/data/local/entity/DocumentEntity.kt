package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Document Reader result: 1-page import first, plain summary + key points. */
@Serializable
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: String,
    val summary: String,
    val keyPoints: List<String> = emptyList(),
    /** ISO date YYYY-MM-DD when the document carries a deadline. */
    val deadline: String? = null,
    val amount: Double? = null,
    val requiredAction: String? = null,
    val sourcePath: String? = null,
    val confidence: Double,
    val createdAt: Long = System.currentTimeMillis(),
)
