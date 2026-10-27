package com.smartcess.app.core.privacy

import com.smartcess.app.core.serialization.SmartCessJson
import com.smartcess.app.data.local.dao.AppointmentDao
import com.smartcess.app.data.local.dao.BillDao
import com.smartcess.app.data.local.dao.ConversationTurnDao
import com.smartcess.app.data.local.dao.DocumentDao
import com.smartcess.app.data.local.dao.EmailItemDao
import com.smartcess.app.data.local.dao.ReminderDao
import com.smartcess.app.data.local.dao.RoutineDao
import com.smartcess.app.data.local.dao.UserPreferencesDao
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Local-first privacy: export, full deletion, and versioned migration hooks. */
@Serializable
data class SmartCessExport(
    val version: Int,
    val exportedAt: String,
    val bills: List<BillEntity>,
    val reminders: List<ReminderEntity>,
    val documents: List<DocumentEntity>,
    val emailItems: List<EmailItemEntity>,
    val appointments: List<AppointmentEntity>,
    val routines: List<RoutineEntity>,
)

@Singleton
class PrivacyManager @Inject constructor(
    private val bills: BillDao,
    private val reminders: ReminderDao,
    private val documents: DocumentDao,
    private val emails: EmailItemDao,
    private val appointments: AppointmentDao,
    private val routines: RoutineDao,
    private val turns: ConversationTurnDao,
    private val prefs: UserPreferencesDao,
) {
    companion object {
        const val CURRENT_EXPORT_VERSION = 1

        /** Minimal CSV escaping: quote when the cell holds a comma, quote, or newline. */
        fun csvCell(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
    }

    private val json = SmartCessJson.instance

    /** Exports user data as JSON (user-initiated only; stays on-device unless shared). */
    suspend fun exportAll(): String {
        val export = SmartCessExport(
            version = CURRENT_EXPORT_VERSION,
            exportedAt = Instant.now().toString(),
            bills = bills.allForExport(),
            reminders = reminders.all(),
            documents = documents.all(),
            emailItems = emails.all(),
            appointments = appointments.all(),
            routines = routines.all(),
        )
        return json.encodeToString(SmartCessExport.serializer(), export)
    }

    /**
     * Exports the same data as multi-section CSV (spec: JSON or CSV export).
     * One header + rows per section; fields are CSV-escaped.
     */
    suspend fun exportCsv(): String = buildString {
        appendLine("# SmartCess export v$CURRENT_EXPORT_VERSION,${Instant.now()}")
        appendSection(
            "bills",
            listOf("provider", "amount", "currency", "dueDate", "status"),
            bills.allForExport().map {
                listOf(it.provider, it.amount.toString(), it.currency, it.dueDate, if (it.archived) "archived" else "active")
            },
        )
        appendSection(
            "reminders",
            listOf("title", "triggerAt", "status"),
            reminders.all().map {
                listOf(it.title, Instant.ofEpochMilli(it.triggerAtMillis).toString(), it.status)
            },
        )
        appendSection(
            "documents",
            listOf("title", "deadline", "amount"),
            documents.all().map {
                listOf(it.title, it.deadline ?: "", it.amount?.toString() ?: "")
            },
        )
        appendSection(
            "email",
            listOf("sender", "subject", "urgency", "deadline"),
            emails.all().map {
                listOf(it.sender, it.subject, it.urgency, it.deadline ?: "")
            },
        )
        appendSection(
            "appointments",
            listOf("title", "start", "location"),
            appointments.all().map {
                listOf(it.title, Instant.ofEpochMilli(it.startMillis).toString(), it.location ?: "")
            },
        )
        appendSection(
            "routines",
            listOf("title", "kind", "schedule", "streak"),
            routines.all().map {
                listOf(it.title, it.kind, it.scheduleRule, it.streakCount.toString())
            },
        )
    }

    private fun StringBuilder.appendSection(name: String, header: List<String>, rows: List<List<String>>) {
        appendLine("## $name")
        appendLine(header.joinToString(",") { csvCell(it) })
        rows.forEach { row -> appendLine(row.joinToString(",") { csvCell(it) }) }
    }

    /** Deletes everything on-device: tables, conversation history, preferences. */
    suspend fun deleteAll() {
        bills.deleteAll()
        reminders.deleteAll()
        documents.deleteAll()
        emails.deleteAll()
        appointments.deleteAll()
        routines.deleteAll()
        turns.deleteAll()
        prefs.deleteAll()
    }

    /** Validates an export payload version before a future import/migration path. */
    fun checkImportVersion(payload: String): Boolean = runCatching {
        json.decodeFromString(SmartCessExport.serializer(), payload).version <= CURRENT_EXPORT_VERSION
    }.getOrDefault(false)
}
