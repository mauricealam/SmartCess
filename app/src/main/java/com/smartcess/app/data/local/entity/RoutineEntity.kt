package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Routine Guardian: medication/delivery/recurring with completion tracking. Never medical advice. */
@Serializable
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** MEDICATION|DELIVERY|RECURRING|OTHER. */
    val kind: String,
    /** Human-readable rule, e.g. "DAILY 08:00" or "WEEKLY MON 09:00". */
    val scheduleRule: String,
    val nextDueMillis: Long? = null,
    val lastCompletedMillis: Long? = null,
    val streakCount: Int = 0,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_MEDICATION = "MEDICATION"
        const val KIND_DELIVERY = "DELIVERY"
        const val KIND_RECURRING = "RECURRING"
        const val KIND_OTHER = "OTHER"
    }

    /**
     * Missed when the due time passed without a completion since (spec:
     * missed routines tracked; SCHEDULED -> MISSED).
     */
    fun isMissed(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val due = nextDueMillis ?: return false
        if (due >= nowMillis) return false
        val done = lastCompletedMillis ?: return true
        return done < due
    }
}
