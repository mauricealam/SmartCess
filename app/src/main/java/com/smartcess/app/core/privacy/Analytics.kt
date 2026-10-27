package com.smartcess.app.core.privacy

import android.content.Context
import com.smartcess.app.data.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Spec analytics events. Opt-in only; metadata only, never images/text/PII. */
enum class AnalyticsEvent {
    APP_OPEN,
    ONBOARDING,
    VOICE_COMMAND,
    BILL_SCAN_START,
    BILL_SCAN_COMPLETE,
    FRAMING_GUIDANCE,
    BILL_SAVED,
    REMINDER_CREATED,
    REMINDER_FIRED,
    REMINDER_COMPLETED,
    DOCUMENT_EVENT,
    EMAIL_EVENT,
    APPOINTMENT_EVENT,
    ROUTINE_EVENT,
    ERROR_SHOWN,
    API_CALL,
}

@Singleton
class AnalyticsLogger @Inject constructor(
    private val prefs: PreferencesRepository,
    @ApplicationContext private val context: Context,
) {
    /**
     * Metadata map must carry counts/flags only (e.g. intent name, success), no PII.
     * Opt-in sink: app-private JSONL log (Crashlytics/remote logging wires here later).
     */
    suspend fun log(event: AnalyticsEvent, metadata: Map<String, String> = emptyMap()) {
        if (!prefs.get().analyticsOptIn) return
        runCatching {
            withContext(Dispatchers.IO) {
                val meta = metadata.entries.joinToString(",") { (k, v) ->
                    "\"${jsonEscape(k)}\":\"${jsonEscape(v)}\""
                }
                val line = "{\"t\":${System.currentTimeMillis()},\"e\":\"${event.name}\",$meta}\n"
                context.openFileOutput(ANALYTICS_FILE, Context.MODE_APPEND).use {
                    it.write(line.toByteArray())
                }
            }
        }
    }

    suspend fun isEnabled(): Boolean = prefs.observe().first().analyticsOptIn

    companion object {
        private const val ANALYTICS_FILE = "analytics.log"

        internal fun jsonEscape(value: String): String = buildString {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(if (c < ' ') '?' else c)
                }
            }
        }
    }
}
