package com.smartcess.app.core.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootRescheduleEntryPoint {
    fun scheduler(): ReminderScheduler
}

/** Re-enqueues SCHEDULED reminders after reboot so none are lost. */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entry = EntryPointAccessors.fromApplication(context, BootRescheduleEntryPoint::class.java)
                entry.scheduler().rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
