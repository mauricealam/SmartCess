package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Voice conversation turn for context and AI eval sampling. Text stays on-device. */
@Entity(tableName = "conversation_turns")
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** USER|ASSISTANT. */
    val role: String,
    val text: String,
    val intent: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
