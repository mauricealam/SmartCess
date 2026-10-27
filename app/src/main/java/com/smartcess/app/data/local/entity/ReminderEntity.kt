package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Reminder lifecycle: OPTIONS -> CONFIRM -> SCHEDULED -> FIRED -> COMPLETED. */
@Serializable
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** BILL|APPOINTMENT|ROUTINE|DOCUMENT|GENERAL. */
    val kind: String,
    /** Owning row id in the kind's table, or -1 for standalone reminders. */
    val referenceId: Long = -1,
    val title: String,
    val detail: String? = null,
    val triggerAtMillis: Long,
    val offsetMinutes: Int? = null,
    /** SCHEDULED|FIRED|COMPLETED|CANCELLED. */
    val status: String = STATUS_SCHEDULED,
    /** WorkManager request id for cancellation; null until scheduled. */
    val workRequestId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val firedAt: Long? = null,
) {
    companion object {
        const val KIND_BILL = "BILL"
        const val KIND_APPOINTMENT = "APPOINTMENT"
        const val KIND_ROUTINE = "ROUTINE"
        const val KIND_DOCUMENT = "DOCUMENT"
        const val KIND_GENERAL = "GENERAL"

        const val STATUS_SCHEDULED = "SCHEDULED"
        const val STATUS_FIRED = "FIRED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
