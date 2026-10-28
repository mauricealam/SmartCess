package com.smartcess.app.core.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smartcess.app.data.repository.AppointmentRepository
import com.smartcess.app.data.repository.ReminderRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderWorkerEntryPoint {
    fun reminders(): ReminderRepository
    fun appointments(): AppointmentRepository
    fun notifications(): NotificationHelper
}

private fun entryPoint(context: Context): ReminderWorkerEntryPoint =
    EntryPointAccessors.fromApplication(context, ReminderWorkerEntryPoint::class.java)

/** Fires a scheduled reminder: marks FIRED and posts the notification. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_REMINDER_ID, -1)
        if (id < 0) return Result.failure()
        val entry = entryPoint(applicationContext)
        val reminder = entry.reminders().getById(id) ?: return Result.failure()
        entry.reminders().markFired(id)
        entry.notifications().showReminder(id.hashCode(), reminder.title, reminder.detail, reminderId = id)
        return Result.success()
    }
}

/** Travel nudge for an appointment (30/60/90 min before, per user choice). */
class TravelReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_APPOINTMENT_ID = "appointment_id"
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_APPOINTMENT_ID, -1)
        if (id < 0) return Result.failure()
        val entry = entryPoint(applicationContext)
        val appointment = entry.appointments().getById(id) ?: return Result.failure()
        val where = appointment.location?.let { " at $it" } ?: ""
        entry.notifications().showReminder(
            ("travel-$id").hashCode(),
            "Time to leave: ${appointment.title}",
            "Your appointment$where is coming up.",
            appointmentId = id,
        )
        return Result.success()
    }
}
