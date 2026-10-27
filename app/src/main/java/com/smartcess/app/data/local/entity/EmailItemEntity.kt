package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Local email item. MVP may use a demo inbox; never auto send/reply/delete/forward. */
@Serializable
@Entity(tableName = "email_items")
data class EmailItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val subject: String,
    val body: String,
    val receivedAt: Long,
    /** low|normal|high|urgent. */
    val urgency: String = "normal",
    val isRead: Boolean = false,
    val hasDeadline: Boolean = false,
    val hasAttachment: Boolean = false,
    /** ISO date YYYY-MM-DD when a deadline was detected. */
    val deadline: String? = null,
    val briefingNote: String? = null,
)
