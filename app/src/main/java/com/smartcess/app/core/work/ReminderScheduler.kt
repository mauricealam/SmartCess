package com.smartcess.app.core.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.repository.AppointmentRepository
import com.smartcess.app.data.repository.ReminderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules reminders + travel nudges via WorkManager. Called only after the user
 * confirms (OPTIONS -> CONFIRM -> SCHEDULED); scheduling never happens silently.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val work: WorkManager,
    private val reminders: ReminderRepository,
    private val appointments: AppointmentRepository,
) {
    suspend fun schedule(reminderId: Long) {
        val reminder = reminders.getById(reminderId) ?: return
        val delay = (reminder.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(Data.Builder().putLong(ReminderWorker.KEY_REMINDER_ID, reminderId).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(workName(reminderId))
            .build()
        work.enqueueUniqueWork(workName(reminderId), ExistingWorkPolicy.REPLACE, request)
        reminders.setScheduled(reminderId, request.id.toString())
    }

    suspend fun scheduleTravelNudge(appointmentId: Long, triggerAtMillis: Long) {
        val delay = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<TravelReminderWorker>()
            .setInputData(Data.Builder().putLong(TravelReminderWorker.KEY_APPOINTMENT_ID, appointmentId).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(travelWorkName(appointmentId))
            .build()
        work.enqueueUniqueWork(travelWorkName(appointmentId), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(reminderId: Long) {
        val reminder = reminders.getById(reminderId)
        reminder?.workRequestId?.let {
            runCatching { work.cancelWorkById(UUID.fromString(it)) }
        }
        work.cancelUniqueWork(workName(reminderId))
        reminders.cancel(reminderId)
    }

    fun cancelTravelNudge(appointmentId: Long) {
        work.cancelUniqueWork(travelWorkName(appointmentId))
    }

    /** Re-enqueues every SCHEDULED reminder (boot, upgrade, or process death). */
    suspend fun rescheduleAll() {
        reminders.scheduled().forEach { schedule(it.id) }
        rescheduleTravelNudges()
    }

    /**
     * Travel nudges have no reminder rows, so reboot would drop them.
     * Re-derived from appointments that still have a future trigger.
     */
    suspend fun rescheduleTravelNudges(nowMillis: Long = System.currentTimeMillis()) {
        appointments.withTravelReminder().forEach { appt ->
            travelTriggerAt(appt.startMillis, appt.travelReminderMinutes, nowMillis)?.let {
                scheduleTravelNudge(appt.id, it)
            }
        }
    }

    private fun workName(reminderId: Long) = "reminder-$reminderId"
    private fun travelWorkName(appointmentId: Long) = "travel-$appointmentId"

    companion object {
        /**
         * Travel-nudge trigger, or null when unset or already past (a reboot
         * must not resurrect a nudge whose appointment already started).
         */
        fun travelTriggerAt(startMillis: Long, leadMinutes: Int?, nowMillis: Long): Long? {
            if (leadMinutes == null) return null
            val triggerAt = startMillis - leadMinutes * 60_000L
            return if (triggerAt > nowMillis) triggerAt else null
        }

        /** Creates a SCHEDULED reminder row for a bill due date minus [daysBefore]. */
        suspend fun billReminderSpec(
            billId: Long,
            title: String,
            dueDateMillis: Long,
            daysBefore: Int,
        ): Triple<String, Long, Long> = Triple(
            ReminderEntity.KIND_BILL,
            billId,
            dueDateMillis - daysBefore * 24L * 60 * 60 * 1000,
        )
    }
}
