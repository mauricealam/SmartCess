package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Appointment Assistant event. Calendar + travel reminder only after approval. */
@Serializable
@Entity(tableName = "appointments")
data class AppointmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val provider: String? = null,
    val location: String? = null,
    val doctorName: String? = null,
    val startMillis: Long,
    val endMillis: Long? = null,
    val prepNotes: String? = null,
    /** Calendar event id once written after user approval; null until then. */
    val calendarEventId: Long? = null,
    /** Travel reminder offset; spec offers 30/60/90 min choices. */
    val travelReminderMinutes: Int? = null,
    val confidence: Double = 1.0,
    val createdAt: Long = System.currentTimeMillis(),
)
