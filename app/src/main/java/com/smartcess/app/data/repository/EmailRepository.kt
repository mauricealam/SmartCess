package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.EmailItemDao
import com.smartcess.app.data.local.entity.EmailItemEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local email store. MVP seeds a demo inbox; Gmail/Outlook arrive later.
 * Read-only surface: never send, reply, delete, or forward.
 */
@Singleton
class EmailRepository @Inject constructor(private val dao: EmailItemDao) {

    fun observeAll(): Flow<List<EmailItemEntity>> = dao.observeAll()
    suspend fun getById(id: Long): EmailItemEntity? = dao.getById(id)
    suspend fun unreadUrgent(): List<EmailItemEntity> = dao.unreadUrgent()
    suspend fun markRead(id: Long) = dao.markRead(id)
    suspend fun deleteAll() = dao.deleteAll()

    /** Seeds the demo inbox once when empty. Safe to call on every launch. */
    suspend fun seedDemoInboxIfEmpty() {
        if (dao.count() == 0) {
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            listOf(
                EmailItemEntity(
                    sender = "Tenaga Nasional",
                    subject = "Your electricity bill is ready",
                    body = SAMPLE_PREFIX + TENAGA_BODY,
                    receivedAt = now - day,
                    urgency = "high",
                    hasDeadline = true,
                    hasAttachment = true,
                ),
                EmailItemEntity(
                    sender = "City Clinic",
                    subject = "Appointment reminder",
                    body = SAMPLE_PREFIX + CLINIC_BODY,
                    receivedAt = now - 2 * day,
                    urgency = "normal",
                ),
                EmailItemEntity(
                    sender = "Parcel Express",
                    subject = "Delivery attempted",
                    body = SAMPLE_PREFIX + PARCEL_BODY,
                    receivedAt = now - 3 * day,
                    urgency = "low",
                ),
            ).forEach { dao.upsert(it) }
        }
        // One-time refresh for installs seeded before bodies self-identified.
        // Matches zero rows on fresh installs; never touches anything else.
        DEMO_SEEDS.forEach { (sender, subject, oldBody) ->
            dao.refreshSeedBody(sender, subject, oldBody, SAMPLE_PREFIX + oldBody)
        }
    }

    companion object {
        /** Spoken/read first so demo content is never mistaken for real mail. */
        const val SAMPLE_PREFIX = "Sample email: "

        private const val TENAGA_BODY =
            "Your electricity bill of RM 87.40 is due on Friday. Please review before paying."
        private const val CLINIC_BODY =
            "Reminder: your check-up is tomorrow at 10:30 AM. Bring your ID card."
        private const val PARCEL_BODY =
            "We attempted delivery today. Reply is not needed; pick up at the branch office."

        private val DEMO_SEEDS = listOf(
            Triple("Tenaga Nasional", "Your electricity bill is ready", TENAGA_BODY),
            Triple("City Clinic", "Appointment reminder", CLINIC_BODY),
            Triple("Parcel Express", "Delivery attempted", PARCEL_BODY),
        )
    }
}
