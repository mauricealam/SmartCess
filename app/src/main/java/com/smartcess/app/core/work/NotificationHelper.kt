package com.smartcess.app.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartcess.app.MainActivity
import com.smartcess.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_REMINDERS = "smartcess_reminders"
        /** Keeps travel-action request codes distinct from reminder-action ones. */
        private const val TRAVEL_REQUEST_OFFSET = 1_000_000
    }

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notif_channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notif_channel_reminders_desc) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Shows a reminder notification; spoken aloud by TalkBack when focused.
     * Fired reminders carry Complete + Snooze actions (spec: SENT -> COMPLETED|SNOOZED).
     * Content taps deep-link to the reminder or appointment detail (C2/M4/M10);
     * travel nudges carry Done + Snooze actions instead of reminder actions.
     */
    fun showReminder(
        notificationId: Int,
        title: String,
        detail: String?,
        reminderId: Long? = null,
        appointmentId: Long? = null,
    ) {
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (reminderId != null && reminderId > 0) {
            intent.putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        if (appointmentId != null && appointmentId > 0) {
            intent.putExtra(ReminderActionReceiver.EXTRA_APPOINTMENT_ID, appointmentId)
        }
        val pending = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail ?: title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (reminderId != null) {
            builder.addAction(completeAction(notificationId, reminderId))
            builder.addAction(snoozeAction(notificationId, reminderId))
        } else if (appointmentId != null) {
            builder.addAction(travelDoneAction(notificationId, appointmentId))
            builder.addAction(travelSnoozeAction(notificationId, appointmentId))
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the reminder still marks fired in-repo.
        }
    }

    private fun completeAction(notificationId: Int, reminderId: Long): NotificationCompat.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_COMPLETE
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pending = PendingIntent.getBroadcast(
            context, notificationId * 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0, context.getString(R.string.notif_complete), pending,
        ).build()
    }

    private fun snoozeAction(notificationId: Int, reminderId: Long): NotificationCompat.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_SNOOZE
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pending = PendingIntent.getBroadcast(
            context, notificationId * 2 + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0, context.getString(R.string.notif_snooze), pending,
        ).build()
    }

    private fun travelDoneAction(notificationId: Int, appointmentId: Long): NotificationCompat.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_TRAVEL_DONE
            putExtra(ReminderActionReceiver.EXTRA_APPOINTMENT_ID, appointmentId)
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pending = PendingIntent.getBroadcast(
            context, notificationId * 2 + TRAVEL_REQUEST_OFFSET, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0, context.getString(R.string.notif_complete), pending,
        ).build()
    }

    private fun travelSnoozeAction(notificationId: Int, appointmentId: Long): NotificationCompat.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_TRAVEL_SNOOZE
            putExtra(ReminderActionReceiver.EXTRA_APPOINTMENT_ID, appointmentId)
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pending = PendingIntent.getBroadcast(
            context, notificationId * 2 + TRAVEL_REQUEST_OFFSET + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0, context.getString(R.string.notif_snooze), pending,
        ).build()
    }
}
