package com.smartcess.app.core.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.smartcess.app.data.repository.ReminderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification actions for fired reminders: mark done or snooze 10 minutes
 * (spec: REMINDER_SENT -> COMPLETED | SNOOZED). Manifest-registered, private.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var reminders: ReminderRepository
    @Inject lateinit var scheduler: ReminderScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1)
        val apptId = intent.getLongExtra(EXTRA_APPOINTMENT_ID, -1)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_COMPLETE -> if (id > 0) reminders.complete(id)
                    ACTION_SNOOZE -> if (id > 0) {
                        reminders.retarget(id, System.currentTimeMillis() + SNOOZE_MILLIS)
                        scheduler.schedule(id)
                    }
                    // Travel nudge: Done only dismisses (cancelled below); Snooze
                    // re-fires the same unique work 10 minutes later (M10).
                    ACTION_TRAVEL_DONE -> Unit
                    ACTION_TRAVEL_SNOOZE -> if (apptId > 0) {
                        scheduler.scheduleTravelNudge(apptId, System.currentTimeMillis() + SNOOZE_MILLIS)
                    }
                }
            } catch (_: Exception) {
                // Best effort from a notification action; the reminder row keeps the truth.
            } finally {
                NotificationManagerCompat.from(context).cancel(notificationId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.smartcess.app.REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "com.smartcess.app.REMINDER_SNOOZE"
        const val ACTION_TRAVEL_DONE = "com.smartcess.app.TRAVEL_DONE"
        const val ACTION_TRAVEL_SNOOZE = "com.smartcess.app.TRAVEL_SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_APPOINTMENT_ID = "appointment_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val SNOOZE_MILLIS = 10L * 60 * 1000
    }
}
