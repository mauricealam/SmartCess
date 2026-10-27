package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Persisted bill from Bill Watcher: scan -> extract -> validate -> review -> save. */
@Serializable
@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val amount: Double,
    val currency: String,
    /** ISO date YYYY-MM-DD; lexicographic compare = chronological. */
    val dueDate: String,
    val accountReference: String? = null,
    /** Legacy single-string period (v1 rows). New rows use [periodStart]/[periodEnd]. */
    @Deprecated("Use periodStart/periodEnd", ReplaceWith("periodStart"))
    val period: String? = null,
    /** Billing period bounds, ISO dates per spec schema. Null when unknown. */
    val periodStart: String? = null,
    val periodEnd: String? = null,
    /** Required-action enum: pay_before_due_date|review_bill|contact_provider|no_action_required. */
    val requiredAction: String = "review_bill",
    val confidence: Double,
    val missingFields: List<String> = emptyList(),
    val summary: String,
    val imagePath: String? = null,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Display billing period, preferring v2 bounds with v1 fallback. */
    fun displayPeriod(): String? {
        if (periodStart != null && periodEnd != null) return "$periodStart – $periodEnd"
        return periodStart ?: periodEnd ?: @Suppress("DEPRECATION") (period)
    }
}
