package com.smartcess.app.features.voice

import android.content.Context
import com.smartcess.app.R
import com.smartcess.app.core.ai.AppointmentExtraction
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.RateLimitedException
import com.smartcess.app.core.ai.ReminderExtraction
import com.smartcess.app.core.ai.RoutineHelp
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.repository.AppointmentRepository
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.BriefingRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.EmailRepository
import com.smartcess.app.data.repository.ReminderRepository
import com.smartcess.app.data.repository.RoutineRepository
import com.smartcess.app.data.repository.TodayData
import com.smartcess.app.data.repository.ROUTINE_KINDS
import com.smartcess.app.features.appointments.AppointmentsViewModel
import com.smartcess.app.features.bills.BillResultViewModel
import com.smartcess.app.features.routines.RoutinesViewModel
import com.smartcess.app.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeoutException

/**
 * Intents the voice executor completes end-to-end (Milestones 1-3).
 * COMPLETE_ROUTINE is fast-route-only: the AI classifier vocabulary has no
 * completion intent, so only crisp "mark X done" commands trigger it.
 */
object VoiceActionIntents {
    const val ADD_APPOINTMENT = "add_appointment"
    const val ADD_ROUTINE = "add_routine"
    const val DAILY_BRIEFING = "daily_briefing"
    const val ASK_BILL_QUESTION = "ask_bill_question"
    const val CREATE_REMINDER = "create_reminder"
    const val CANCEL_REMINDER = "cancel_reminder"
    const val ARCHIVE_BILL = "archive_bill"
    const val EMAIL_BRIEFING = "email_briefing"
    const val READ_DOCUMENT = "read_document"
    const val SCAN_BILL = "scan_bill"
    const val COMPLETE_ROUTINE = "complete_routine"
    private val ALL = setOf(
        ADD_APPOINTMENT, ADD_ROUTINE, DAILY_BRIEFING, ASK_BILL_QUESTION,
        CREATE_REMINDER, CANCEL_REMINDER, ARCHIVE_BILL, EMAIL_BRIEFING,
        READ_DOCUMENT, SCAN_BILL, COMPLETE_ROUTINE,
    )
    fun isAction(intent: String): Boolean = intent in ALL
}

/** One executor turn: exactly what to speak, whether a record persisted, and an optional screen to open after speaking (capture guidance). */
data class ActionTurn(val speak: String, val saved: Boolean = false, val navigateTo: String? = null)

private sealed interface PendingAction {
    /** Everything heard for this action, oldest first (slot-fill context). */
    val heard: List<String>
    /** This task's turns only (no prior Q&A/history) for local slot parsing. */
    val taskTurns: List<String>
}

private data class PendingAppointment(
    val title: String,
    val provider: String?,
    val location: String?,
    val doctor: String?,
    val startIso: String,
    val startMillis: Long?,
    val endIso: String?,
    val prepNotes: String?,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction {
    val missingTitle: Boolean get() = title.isBlank()
    val missingWhen: Boolean get() = startMillis == null
    val complete: Boolean get() = !missingTitle && !missingWhen
    fun withHeard(full: List<String>): PendingAppointment = copy(heard = full)
}

private data class PendingRoutine(
    val title: String,
    val kind: String,
    val scheduleRule: String,
    val minutes: Int?,
    val notes: String?,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction {
    val missingTitle: Boolean get() = title.isBlank()
    val missingWhen: Boolean get() = minutes == null
    val complete: Boolean get() = !missingTitle && !missingWhen
    fun withHeard(full: List<String>): PendingRoutine = copy(heard = full)
}

private data class PendingReminder(
    val title: String,
    val triggerMillis: Long?,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction {
    val missingTitle: Boolean get() = title.isBlank()
    val missingWhen: Boolean get() = triggerMillis == null
    val complete: Boolean get() = !missingTitle && !missingWhen
    fun withHeard(full: List<String>): PendingReminder = copy(heard = full)
}

/** Awaiting the user's pick (by number or name) before answering. Read-only. */
private data class PendingBillChoice(
    val candidates: List<BillEntity>,
    val question: String,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Awaiting the user's pick before the cancellation read-back. Nothing touched yet. */
private data class PendingCancelChoice(
    val candidates: List<ReminderEntity>,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Named cancellation target awaiting explicit "yes". */
private data class PendingCancelConfirm(
    val target: ReminderEntity,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Awaiting the user's pick before the archive read-back. Nothing touched yet. */
private data class PendingArchiveChoice(
    val candidates: List<BillEntity>,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Named archive target awaiting explicit "yes". */
private data class PendingArchiveConfirm(
    val target: BillEntity,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Awaiting the user's pick (by number or name) before reading. Read-only. */
private data class PendingDocChoice(
    val candidates: List<DocumentEntity>,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Awaiting the user's pick before the completion read-back. Nothing touched yet. */
private data class PendingCompleteChoice(
    val candidates: List<RoutineEntity>,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/** Named completion target awaiting explicit "yes". */
private data class PendingCompleteConfirm(
    val target: RoutineEntity,
    override val heard: List<String>,
    override val taskTurns: List<String> = emptyList(),
) : PendingAction

/**
 * Executes voice actions end-to-end: extract details from speech, ask for
 * missing slots across follow-up turns, read back the exact proposal, and
 * save only on explicit spoken confirmation — through the same repositories
 * the manual screens use (same tables, same observed lists).
 *
 * Safety contract (each point is unit-tested):
 * - "No"/"cancel" (or any cancel word) discards the pending action unsaved.
 * - Any new details rebuild the draft and its summary, so a "yes" always
 *   answers the latest read-back — never a stale one.
 * - Confirmation is scoped to the exact current action: a "yes" with no
 *   pending action, or with slots still missing, saves nothing.
 * - Single-flight save: the pending action is cleared before the repository
 *   call and a saving flag absorbs repeats, so double confirmation cannot
 *   double-save (same as manual, which cannot cancel mid-save either).
 * - TTS can never self-confirm: confirmation is evaluated only from a new
 *   user turn (speech or typed); the mic opens on user tap only, and a new
 *   tap stops speech first (barge-in, owned by the ViewModel).
 * - Banned actions (pay, send email, calls, …) have no path here by
 *   construction: only [VoiceActionIntents] reach the executor.
 * - Record targeting (Milestone 2): cancel/archive resolve the exact record
 *   first; multiple matches are spoken as numbered options and the user picks
 *   — the top match is never silently chosen. The named target still needs an
 *   explicit "yes".
 * - Reading (Milestone 3): email briefing and document reading speak saved
 *   content only; new captures navigate to the camera with spoken guidance.
 */
@Singleton
class VoiceActionHandler @Inject constructor(
    private val spark: SparkClient,
    private val appointments: AppointmentRepository,
    private val routines: RoutineRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val briefing: BriefingRepository,
    private val bills: BillRepository,
    private val emails: EmailRepository,
    private val documents: DocumentRepository,
    @ApplicationContext private val appContext: Context,
) {
    private val lock = Any()
    private var pending: PendingAction? = null
    private var saving = false

    val hasPending: Boolean get() = synchronized(lock) { pending != null }

    /**
     * First turn of an action intent: extract from speech (+ prior Q&A
     * context) and either ask for the first missing slot or read back the
     * full proposal for confirmation.
     */
    suspend fun start(intent: String, text: String, priorContext: String? = null): ActionTurn {
        val heard = listOfNotNull(priorContext?.ifBlank { null }, text)
        val taskTurns = listOf(text)
        return when (intent) {
            VoiceActionIntents.ADD_APPOINTMENT -> startAppointment(heard, taskTurns)
            VoiceActionIntents.ADD_ROUTINE -> startRoutine(heard, taskTurns)
            VoiceActionIntents.CREATE_REMINDER -> startReminder(heard, taskTurns)
            VoiceActionIntents.DAILY_BRIEFING -> speakBriefing()
            VoiceActionIntents.ASK_BILL_QUESTION -> startBillQuestion(text, heard)
            VoiceActionIntents.CANCEL_REMINDER -> startCancelReminder(text)
            VoiceActionIntents.ARCHIVE_BILL -> startArchiveBill(text)
            VoiceActionIntents.EMAIL_BRIEFING -> speakEmailBriefing()
            VoiceActionIntents.READ_DOCUMENT -> startReadDocument(text)
            VoiceActionIntents.SCAN_BILL -> guideBillCapture()
            VoiceActionIntents.COMPLETE_ROUTINE -> startCompleteRoutine(text)
            else -> ActionTurn(appContext.getString(R.string.error_generic))
        }
    }

    /**
     * Follow-up turn while an action is pending: confirmation, cancellation,
     * selection, or more details. Null when nothing is pending (caller falls
     * back to normal classification — a stray "yes" can never save).
     */
    suspend fun followUp(text: String): ActionTurn? {
        val current = synchronized(lock) { pending } ?: return null
        if (synchronized(lock) { saving }) {
            return ActionTurn(appContext.getString(R.string.voice_action_still_saving))
        }
        val norm = normalize(text)
        return when (current) {
            is PendingAppointment, is PendingRoutine, is PendingReminder ->
                followUpDraft(current, norm, text)
            is PendingBillChoice -> followUpBillChoice(current, norm, text)
            is PendingCancelChoice -> followUpCancelChoice(current, norm, text)
            is PendingCancelConfirm -> followUpCancelConfirm(current, norm)
            is PendingArchiveChoice -> followUpArchiveChoice(current, norm, text)
            is PendingArchiveConfirm -> followUpArchiveConfirm(current, norm)
            is PendingDocChoice -> followUpDocChoice(current, norm, text)
            is PendingCompleteChoice -> followUpCompleteChoice(current, norm, text)
            is PendingCompleteConfirm -> followUpCompleteConfirm(current, norm)
        }
    }

    private suspend fun followUpDraft(current: PendingAction, norm: String, text: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_action_cancelled))
        }
        if (isConfirm(norm)) {
            // A bare "yes" with slots still missing is not confirmation.
            firstMissingQuestion(current)?.let { return ActionTurn(it) }
            return save(current)
        }
        // Slot-fill or edit: re-extract over everything heard. The draft and
        // its summary are rebuilt, invalidating any earlier read-back.
        return continueWith(current, text)
    }

    private fun followUpBillChoice(current: PendingBillChoice, norm: String, text: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_dismissed))
        }
        // A bare "yes" picks nothing; anything unrecognized re-asks.
        selectCandidate(current.candidates, text, BillEntity::provider)?.let {
            return answerBill(it, current.question)
        }
        return ActionTurn(billChoiceQuestion(current.candidates))
    }

    private fun followUpCancelChoice(current: PendingCancelChoice, norm: String, text: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_keep))
        }
        selectCandidate(current.candidates, text, ReminderEntity::title)?.let {
            return confirmCancel(it, current.heard)
        }
        return ActionTurn(cancelChoiceQuestion(current.candidates))
    }

    private suspend fun followUpCancelConfirm(current: PendingCancelConfirm, norm: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_keep))
        }
        if (isConfirm(norm)) return save(current)
        // Anything else re-speaks the named confirmation (never executes).
        return ActionTurn(cancelConfirmQuestion(current.target))
    }

    private fun followUpArchiveChoice(current: PendingArchiveChoice, norm: String, text: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_keep))
        }
        selectCandidate(current.candidates, text, BillEntity::provider)?.let {
            return confirmArchive(it, current.heard)
        }
        return ActionTurn(archiveChoiceQuestion(current.candidates))
    }

    private suspend fun followUpArchiveConfirm(current: PendingArchiveConfirm, norm: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_keep))
        }
        if (isConfirm(norm)) return save(current)
        return ActionTurn(archiveConfirmQuestion(current.target))
    }

    private fun followUpDocChoice(current: PendingDocChoice, norm: String, text: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_dismissed))
        }
        selectCandidate(current.candidates, text, DocumentEntity::title)?.let {
            return readDocument(it)
        }
        return ActionTurn(docChoiceQuestion(current.candidates))
    }

    private fun followUpCompleteChoice(current: PendingCompleteChoice, norm: String, text: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_keep))
        }
        selectCandidate(current.candidates, text, RoutineEntity::title)?.let {
            return confirmComplete(it, current.heard)
        }
        return ActionTurn(completeChoiceQuestion(current.candidates))
    }

    private suspend fun followUpCompleteConfirm(current: PendingCompleteConfirm, norm: String): ActionTurn {
        if (isCancel(norm)) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_keep))
        }
        if (isConfirm(norm)) return save(current)
        return ActionTurn(completeConfirmQuestion(current.target))
    }

    /** Silent discard (screen exit). In-flight saves complete like manual. */
    fun discard() {
        clear()
    }

    // Starts

    private suspend fun startAppointment(heard: List<String>, taskTurns: List<String>): ActionTurn {
        fastAppointment(taskTurns)?.let { return present(it.withHeard(heard)) }
        val result = runCatching { spark.extractAppointment(anchored(heard)) }
        val ex = result.getOrNull()
            ?: return ActionTurn(failureSpeech(result.exceptionOrNull()))
        return present(buildAppointment(ex, heard, taskTurns))
    }

    private suspend fun startRoutine(heard: List<String>, taskTurns: List<String>): ActionTurn {
        fastRoutine(taskTurns)?.let { return presentRoutine(it.withHeard(heard)) }
        val result = runCatching { spark.routineHelp(heard.joinToString("\n")) }
        val ex = result.getOrNull()
            ?: return ActionTurn(failureSpeech(result.exceptionOrNull()))
        return presentRoutine(buildRoutine(ex, heard, taskTurns))
    }

    private suspend fun startReminder(heard: List<String>, taskTurns: List<String>): ActionTurn {
        fastReminder(taskTurns)?.let { return presentReminder(it) }
        val result = runCatching { spark.extractReminder(anchored(heard)) }
        val ex = result.getOrNull()
            ?: return ActionTurn(failureSpeech(result.exceptionOrNull()))
        return presentReminder(buildReminder(ex, heard, taskTurns))
    }

    /**
     * Local slots for command-shaped turns: same drafts, guards, and
     * read-backs as the AI path, but instant. Null (fuzzy/past) falls back
     * to Spark extraction.
     */
    private fun fastAppointment(taskTurns: List<String>): PendingAppointment? {
        if (VoiceFastPath.route(taskTurns.joinToString("\n")) != VoiceActionIntents.ADD_APPOINTMENT) return null
        val slots = VoiceFastPath.parseCreate(VoiceActionIntents.ADD_APPOINTMENT, taskTurns) ?: return null
        return buildAppointment(
            AppointmentExtraction(
                title = slots.title ?: "",
                start_iso = slots.triggerMillis?.let { VoiceFastPath.formatTrigger(it) } ?: "",
            ),
            heard = taskTurns,
            taskTurns = taskTurns,
            local = true,
        )
    }

    private fun fastRoutine(taskTurns: List<String>): PendingRoutine? {
        if (VoiceFastPath.route(taskTurns.joinToString("\n")) != VoiceActionIntents.ADD_ROUTINE) return null
        val slots = VoiceFastPath.parseCreate(VoiceActionIntents.ADD_ROUTINE, taskTurns) ?: return null
        return buildRoutine(
            RoutineHelp(
                title = slots.title ?: "",
                kind = slots.kind ?: "OTHER",
                schedule_rule = slots.routineRule ?: "DAILY",
            ),
            heard = taskTurns,
            taskTurns = taskTurns,
            local = true,
        )
    }

    private fun fastReminder(taskTurns: List<String>): PendingReminder? {
        if (VoiceFastPath.route(taskTurns.joinToString("\n")) != VoiceActionIntents.CREATE_REMINDER) return null
        val slots = VoiceFastPath.parseCreate(VoiceActionIntents.CREATE_REMINDER, taskTurns) ?: return null
        return buildReminder(
            ReminderExtraction(
                title = slots.title ?: "",
                trigger_iso = slots.triggerMillis?.let { VoiceFastPath.formatTrigger(it) } ?: "",
            ),
            heard = taskTurns,
            taskTurns = taskTurns,
            local = true,
        )
    }

    private suspend fun continueWith(current: PendingAction, text: String): ActionTurn {
        val heard = current.heard + text
        val taskTurns = current.taskTurns + text
        return when (current) {
            is PendingAppointment -> {
                fastAppointment(taskTurns)?.let { return present(it.withHeard(heard)) }
                val result = runCatching { spark.extractAppointment(anchored(heard)) }
                val ex = result.getOrNull()
                    ?: return ActionTurn(failureSpeech(result.exceptionOrNull()))
                present(buildAppointment(ex, heard, taskTurns))
            }
            is PendingRoutine -> {
                fastRoutine(taskTurns)?.let { return presentRoutine(it.withHeard(heard)) }
                val result = runCatching { spark.routineHelp(heard.joinToString("\n")) }
                val ex = result.getOrNull()
                    ?: return ActionTurn(failureSpeech(result.exceptionOrNull()))
                presentRoutine(buildRoutine(ex, heard, taskTurns))
            }
            is PendingReminder -> {
                fastReminder(taskTurns)?.let { return presentReminder(it.withHeard(heard)) }
                val result = runCatching { spark.extractReminder(anchored(heard)) }
                val ex = result.getOrNull()
                    ?: return ActionTurn(failureSpeech(result.exceptionOrNull()))
                presentReminder(buildReminder(ex, heard, taskTurns))
            }
            else -> ActionTurn(appContext.getString(R.string.error_generic))
        }
    }

    // Draft building

    /** "Today is …" anchor so the extractor can resolve "tomorrow / Friday". */
    private fun anchored(heard: List<String>): String {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd", Locale.US))
        return "Today is $today.\n" + heard.joinToString("\n")
    }

    private fun buildAppointment(
        ex: AppointmentExtraction,
        heard: List<String>,
        taskTurns: List<String>,
        local: Boolean = false,
    ): PendingAppointment {
        // Same parser the manual form uses.
        val startMillis = AppointmentsViewModel.parseDateTime(ex.start_iso)
        return PendingAppointment(
            title = ex.title.trim(),
            provider = ex.provider?.trim()?.ifBlank { null },
            location = ex.location?.trim()?.ifBlank { null },
            doctor = ex.doctor?.trim()?.ifBlank { null },
            startIso = ex.start_iso,
            startMillis = startMillis,
            endIso = ex.end_iso?.trim()?.ifBlank { null },
            prepNotes = ex.prep_notes?.trim()?.ifBlank { null },
            heard = heard,
            taskTurns = taskTurns,
        )
    }

    private fun buildRoutine(
        ex: RoutineHelp,
        heard: List<String>,
        taskTurns: List<String>,
        local: Boolean = false,
    ): PendingRoutine {
        // Same rule normalization + kind vocabulary as the manual form.
        val rule = ex.schedule_rule.trim().uppercase()
        var minutes = RoutinesViewModel.parseScheduleMinutes(rule)
        // Deterministic anti-invention guard: the extractor sometimes copies
        // the prompt example's time when the user stated none. A time that
        // appears nowhere in what was heard is dropped, so the app asks for
        // it instead of confirming an hour never stated. Local slots are
        // derived from the words, so they skip the guard (offsets like
        // "in 2 hours" would otherwise false-positive).
        if (!local && minutes != null && !heardMentionsTime(heard, minutes)) minutes = null
        return PendingRoutine(
            title = ex.title.trim(),
            kind = ex.kind.trim().uppercase().takeIf { it in ROUTINE_KINDS } ?: "OTHER",
            scheduleRule = rule,
            minutes = minutes,
            notes = ex.notes?.trim()?.ifBlank { null },
            heard = heard,
            taskTurns = taskTurns,
        )
    }

    /**
     * True when the heard words actually state the extracted time: digit runs
     * or number words matching the hour (12-hour or 24-hour form) and, for
     * non-zero minutes, the minutes. "0800" reads as HHMM.
     */
    private fun heardMentionsTime(heard: List<String>, minutes: Int): Boolean {
        var text = heard.joinToString(" ").lowercase()
        NUMBER_WORDS.forEach { (word, digit) ->
            text = text.replace(Regex("\\b$word\\b"), digit.toString())
        }
        val numbers = mutableSetOf<Int>()
        text.split(Regex("[^a-z0-9]+")).forEach { token ->
            Regex("\\d+").findAll(token).forEach { run ->
                val digits = run.value
                digits.toIntOrNull()?.let { numbers.add(it) }
                if (digits.length in 3..4) {
                    val split = digits.length - 2
                    digits.substring(0, split).toIntOrNull()?.let { numbers.add(it) }
                    digits.substring(split).toIntOrNull()?.let { numbers.add(it) }
                }
            }
        }
        val hour24 = minutes / 60
        val minute = minutes % 60
        val hour12 = hour24 % 12
        val hourOk = hour24 in numbers || hour12 in numbers || (hour12 == 0 && 12 in numbers)
        val minuteOk = minute == 0 || minute in numbers
        return hourOk && minuteOk
    }

    private fun buildReminder(
        ex: ReminderExtraction,
        heard: List<String>,
        taskTurns: List<String>,
        local: Boolean = false,
    ): PendingReminder {
        // Same parser the manual form uses, plus the M1 time guard: an
        // extracted hour absent from the heard words is invention, not data.
        // Local slots skip the guard (derived from the words by construction).
        var trigger = AppointmentsViewModel.parseDateTime(ex.trigger_iso)
        if (!local && trigger != null) {
            val at = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault()).toLocalTime()
            if (!heardMentionsTime(heard, at.hour * 60 + at.minute)) trigger = null
        }
        return PendingReminder(
            title = ex.title.trim(),
            triggerMillis = trigger,
            heard = heard,
            taskTurns = taskTurns,
        )
    }

    // Presentation

    private fun present(draft: PendingAction): ActionTurn {
        synchronized(lock) { pending = draft }
        firstMissingQuestion(draft)?.let { return ActionTurn(it) }
        return ActionTurn(readBack(draft))
    }

    /**
     * Voice recurrence guard: the app's reminder math only computes daily
     * occurrences, so an explicit weekly/monthly/custom rule must never be
     * confirmed as-is — that would promise weekly and schedule daily. Block
     * with a spoken explanation instead. A bare time ("08:00", same daily
     * occurrence the manual form computes) stays allowed and is read back
     * without claiming a recurrence.
     */
    private fun presentRoutine(draft: PendingRoutine): ActionTurn {
        if (NON_DAILY_RULE.any { it in draft.scheduleRule }) {
            clear()
            return ActionTurn(appContext.getString(R.string.voice_action_routine_daily_only))
        }
        return present(draft)
    }

    /** Past triggers re-ask: a reminder must fire in the future. */
    private fun presentReminder(draft: PendingReminder): ActionTurn {
        if (draft.triggerMillis != null && draft.triggerMillis <= System.currentTimeMillis()) {
            synchronized(lock) { pending = draft.copy(triggerMillis = null) }
            return ActionTurn(appContext.getString(R.string.voice_reminder_past))
        }
        synchronized(lock) { pending = draft }
        firstMissingQuestion(draft)?.let { return ActionTurn(it) }
        return ActionTurn(readBack(draft))
    }

    private fun firstMissingQuestion(draft: PendingAction): String? = when (draft) {
        is PendingAppointment -> when {
            draft.missingTitle -> appContext.getString(R.string.voice_action_ask_appt_title)
            draft.missingWhen -> appContext.getString(R.string.voice_action_ask_appt_when)
            else -> null
        }
        is PendingRoutine -> when {
            draft.missingTitle -> appContext.getString(R.string.voice_action_ask_routine_title)
            draft.missingWhen -> appContext.getString(R.string.voice_action_ask_routine_when)
            else -> null
        }
        is PendingReminder -> when {
            draft.missingTitle -> appContext.getString(R.string.voice_reminder_ask_title)
            draft.missingWhen -> appContext.getString(R.string.voice_reminder_ask_when)
            else -> null
        }
        // Choice states re-ask by number/name; confirms are complete.
        is PendingBillChoice -> billChoiceQuestion(draft.candidates)
        is PendingCancelChoice -> cancelChoiceQuestion(draft.candidates)
        is PendingArchiveChoice -> archiveChoiceQuestion(draft.candidates)
        is PendingDocChoice -> docChoiceQuestion(draft.candidates)
        is PendingCompleteChoice -> completeChoiceQuestion(draft.candidates)
        is PendingCancelConfirm, is PendingArchiveConfirm, is PendingCompleteConfirm -> null
    }

    private fun readBack(draft: PendingAction): String = when (draft) {
        is PendingAppointment -> {
            val parts = mutableListOf(draft.title, spokenWhen(draft.startMillis!!))
            draft.location?.let { parts.add("at $it") }
            draft.doctor?.let { parts.add("with $it") }
            draft.provider?.let { parts.add("via $it") }
            appContext.getString(R.string.voice_action_confirm_appt, parts.joinToString(", "))
        }
        is PendingRoutine -> {
            val parts = mutableListOf(draft.title, draft.kind.lowercase(), spokenRecurrence(draft))
            draft.notes?.let { parts.add(it) }
            appContext.getString(R.string.voice_action_confirm_routine, parts.joinToString(", "))
        }
        is PendingReminder -> {
            appContext.getString(
                R.string.voice_reminder_confirm,
                "${draft.title}, ${spokenWhen(draft.triggerMillis!!)}",
            )
        }
        // Choice/confirm states speak their own questions, never readBack.
        is PendingBillChoice -> billChoiceQuestion(draft.candidates)
        is PendingCancelChoice -> cancelChoiceQuestion(draft.candidates)
        is PendingCancelConfirm -> cancelConfirmQuestion(draft.target)
        is PendingArchiveChoice -> archiveChoiceQuestion(draft.candidates)
        is PendingArchiveConfirm -> archiveConfirmQuestion(draft.target)
        is PendingDocChoice -> docChoiceQuestion(draft.candidates)
        is PendingCompleteChoice -> completeChoiceQuestion(draft.candidates)
        is PendingCompleteConfirm -> completeConfirmQuestion(draft.target)
    }

    // Milestone 2: briefing, bill Q&A, reminder create/cancel, bill archive

    /**
     * Same snapshot as the briefing screen, composed on-device and spoken
     * inside Talk. Local (no AI round trip): the old AI call only received
     * item counts yet took ~19s (10s timeout + retry); naming the actual
     * records is both instant and more useful over speech.
     */
    private suspend fun speakBriefing(): ActionTurn {
        val data = runCatching { briefing.getTodayData() }.getOrNull()
            ?: return ActionTurn(appContext.getString(R.string.error_generic))
        if (data.isEmpty) {
            return ActionTurn(appContext.getString(R.string.voice_briefing_empty))
        }
        return ActionTurn(composeBriefing(data))
    }

    internal fun composeBriefing(data: TodayData, nowMillis: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val parts = mutableListOf(greeting(nowMillis, zone))
        parts.addAll(billLines("Overdue bill", data.overdueBills))
        parts.addAll(billLines("Bill due soon", data.billsDueSoon))
        data.appointments.take(BRIEFING_MAX).forEach {
            parts.add("Appointment: ${it.title}, ${spokenWhen(it.startMillis)}.")
        }
        if (data.appointments.size > BRIEFING_MAX) {
            parts.add("and ${data.appointments.size - BRIEFING_MAX} more appointments.")
        }
        data.urgentEmails.take(BRIEFING_MAX).forEach {
            parts.add("Urgent email from ${it.sender}: ${it.subject}.")
        }
        if (data.urgentEmails.size > BRIEFING_MAX) {
            parts.add("and ${data.urgentEmails.size - BRIEFING_MAX} more urgent emails.")
        }
        data.documentsWithDeadlines.take(BRIEFING_MAX).forEach {
            val due = it.deadline?.let { d -> ", due ${prettyDate(d)}" } ?: ""
            parts.add("Document ${it.title}$due.")
        }
        if (data.documentsWithDeadlines.size > BRIEFING_MAX) {
            parts.add("and ${data.documentsWithDeadlines.size - BRIEFING_MAX} more documents.")
        }
        data.routinesDue.take(BRIEFING_MAX).forEach {
            val at = it.nextDueMillis?.let { due -> " at ${spokenTime(due)}" } ?: ""
            parts.add("Routine due: ${it.title}$at.")
        }
        if (data.routinesDue.size > BRIEFING_MAX) {
            parts.add("and ${data.routinesDue.size - BRIEFING_MAX} more routines.")
        }
        return parts.joinToString(" ")
    }

    private fun billLines(label: String, bills: List<BillEntity>): List<String> {
        val lines = bills.take(BRIEFING_MAX).map {
            "$label: ${it.provider}, ${spokenAmount(it.amount, it.currency)}, due ${prettyDate(it.dueDate)}."
        }.toMutableList()
        if (bills.size > BRIEFING_MAX) lines.add("and ${bills.size - BRIEFING_MAX} more.")
        return lines
    }

    private fun greeting(nowMillis: Long, zone: ZoneId): String {
        val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        return when {
            hour < 12 -> "Good morning."
            hour < 18 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    private fun spokenTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

    private fun spokenAmount(amount: Double, currency: String): String {
        val whole = if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount)
        val unit = CURRENCY_WORDS[currency.uppercase()] ?: currency
        return "$whole $unit"
    }

    private suspend fun startBillQuestion(text: String, heard: List<String>): ActionTurn {
        val all = bills.observeActive().first()
        if (all.isEmpty()) {
            return ActionTurn(appContext.getString(R.string.voice_bill_none))
        }
        val matches = matchLabels(all, heard.joinToString(" "), BillEntity::provider)
        return when {
            matches.size == 1 -> answerBill(matches[0], text)
            // A lone bill is named in the answer, so answering it is explicit.
            all.size == 1 -> answerBill(all[0], text)
            else -> {
                val candidates = matches.ifEmpty { all }.take(MAX_CHOICES)
                synchronized(lock) { pending = PendingBillChoice(candidates, text, heard) }
                ActionTurn(billChoiceQuestion(candidates))
            }
        }
    }

    /** Same on-device keyword Q&A the bill screen uses; no network. */
    private fun answerBill(bill: BillEntity, question: String): ActionTurn {
        clear()
        val raw = BillResultViewModel.answerBillQuestion(bill, question)
            ?: return ActionTurn(appContext.getString(R.string.voice_bill_no_answer))
        return ActionTurn(
            appContext.getString(
                R.string.voice_bill_answer,
                "${bill.provider}, ${prettyAnswer(raw)}",
            ),
        )
    }

    private suspend fun startCancelReminder(text: String): ActionTurn {
        val all = reminders.scheduled()
        if (all.isEmpty()) {
            return ActionTurn(appContext.getString(R.string.voice_cancel_none))
        }
        val matches = matchLabels(all, text, ReminderEntity::title)
        return when {
            matches.size == 1 -> confirmCancel(matches[0], listOf(text))
            // A lone reminder is named in the confirmation, so the "yes" is explicit.
            all.size == 1 -> confirmCancel(all[0], listOf(text))
            else -> {
                val candidates = matches.ifEmpty { all }.take(MAX_CHOICES)
                synchronized(lock) { pending = PendingCancelChoice(candidates, listOf(text)) }
                ActionTurn(cancelChoiceQuestion(candidates))
            }
        }
    }

    private suspend fun startArchiveBill(text: String): ActionTurn {
        val all = bills.observeActive().first()
        if (all.isEmpty()) {
            return ActionTurn(appContext.getString(R.string.voice_bill_none))
        }
        val matches = matchLabels(all, text, BillEntity::provider)
        return when {
            matches.size == 1 -> confirmArchive(matches[0], listOf(text))
            all.size == 1 -> confirmArchive(all[0], listOf(text))
            else -> {
                val candidates = matches.ifEmpty { all }.take(MAX_CHOICES)
                synchronized(lock) { pending = PendingArchiveChoice(candidates, listOf(text)) }
                ActionTurn(archiveChoiceQuestion(candidates))
            }
        }
    }

    private fun confirmCancel(target: ReminderEntity, heard: List<String>): ActionTurn {
        synchronized(lock) { pending = PendingCancelConfirm(target, heard) }
        return ActionTurn(cancelConfirmQuestion(target))
    }

    private fun confirmArchive(target: BillEntity, heard: List<String>): ActionTurn {
        synchronized(lock) { pending = PendingArchiveConfirm(target, heard) }
        return ActionTurn(archiveConfirmQuestion(target))
    }

    // Milestone 3: email briefing, document reading, capture guidance, routine completion

    /**
     * Spoken email briefing over the local inbox (demo-seeded until Gmail
     * lands). Read-only: nothing is marked read. Sample data is announced.
     */
    private suspend fun speakEmailBriefing(): ActionTurn {
        runCatching { emails.seedDemoInboxIfEmpty() }
        val all = emails.observeAll().first()
        if (all.isEmpty()) {
            return ActionTurn(appContext.getString(R.string.voice_email_none))
        }
        val parts = mutableListOf(
            if (all.size == 1) "Sample inbox: 1 email." else "Sample inbox: ${all.size} emails.",
        )
        all.take(BRIEFING_MAX).forEach {
            val urgency = it.urgency.lowercase()
            val urgent = if (urgency == "high" || urgency == "urgent") "${it.urgency} urgency from " else "From "
            parts.add("$urgent${it.sender}: ${it.subject}.")
        }
        if (all.size > BRIEFING_MAX) parts.add("and ${all.size - BRIEFING_MAX} more.")
        return ActionTurn(parts.joinToString(" "))
    }

    /**
     * Reads a SAVED document's content aloud. With no saved match, guides
     * into the capture flow instead of inventing content.
     */
    private suspend fun startReadDocument(text: String): ActionTurn {
        val all = documents.observeAll().first()
        if (all.isEmpty()) return guideDocCapture()
        val matches = matchLabels(all, text, DocumentEntity::title)
        return when {
            matches.size == 1 -> readDocument(matches[0])
            all.size == 1 -> readDocument(all[0])
            else -> {
                val candidates = matches.ifEmpty { all }.take(MAX_CHOICES)
                synchronized(lock) { pending = PendingDocChoice(candidates, listOf(text)) }
                ActionTurn(docChoiceQuestion(candidates))
            }
        }
    }

    private fun readDocument(doc: DocumentEntity): ActionTurn {
        clear()
        val summary = doc.summary.trim()
        if (summary.isEmpty() && doc.keyPoints.isEmpty()) {
            return ActionTurn(appContext.getString(R.string.voice_doc_no_content))
        }
        val parts = mutableListOf(appContext.getString(R.string.voice_doc_summary_of, doc.title))
        if (summary.isNotEmpty()) parts.add(summary)
        doc.keyPoints.take(3).forEach { parts.add(it) }
        doc.deadline?.let { parts.add("Deadline ${prettyDate(it)}.") }
        return ActionTurn(parts.joinToString(" "))
    }

    /** New documents/bills: spoken guidance + the camera in the matching mode. */
    private fun guideDocCapture(): ActionTurn =
        ActionTurn(
            appContext.getString(R.string.voice_doc_capture),
            navigateTo = Routes.camera(Routes.MODE_DOC),
        )

    private fun guideBillCapture(): ActionTurn =
        ActionTurn(
            appContext.getString(R.string.voice_bill_capture),
            navigateTo = Routes.camera(Routes.MODE_BILL),
        )

    private suspend fun startCompleteRoutine(text: String): ActionTurn {
        val all = routines.dueToday()
        if (all.isEmpty()) {
            return ActionTurn(appContext.getString(R.string.voice_routine_none_due))
        }
        val matches = matchLabels(all, text, RoutineEntity::title)
        return when {
            matches.size == 1 -> confirmComplete(matches[0], listOf(text))
            all.size == 1 -> confirmComplete(all[0], listOf(text))
            else -> {
                val candidates = matches.ifEmpty { all }.take(MAX_CHOICES)
                synchronized(lock) { pending = PendingCompleteChoice(candidates, listOf(text)) }
                ActionTurn(completeChoiceQuestion(candidates))
            }
        }
    }

    private fun confirmComplete(target: RoutineEntity, heard: List<String>): ActionTurn {
        synchronized(lock) { pending = PendingCompleteConfirm(target, heard) }
        return ActionTurn(completeConfirmQuestion(target))
    }

    // Record matching + spoken options

    /** Word-overlap match on provider/title, strongest first. Pure. */
    private fun <T> matchLabels(all: List<T>, text: String, label: (T) -> String): List<T> {
        val words = significantWords(text)
        if (words.isEmpty()) return emptyList()
        return all.map { candidate ->
            candidate to significantWords(label(candidate)).count { it in words }
        }.filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (candidate, _) -> candidate }
    }

    private fun significantWords(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in MATCH_STOPWORDS }.toSet()

    /**
     * Picks one candidate by spoken number ("2", "two", "the second one") or a
     * uniquely matching name fragment. Null re-asks; never guesses.
     */
    private fun <T> selectCandidate(all: List<T>, text: String, label: (T) -> String): T? {
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        tokens.forEach { token ->
            val n = token.toIntOrNull()
                ?: NUMBER_WORDS[token]
                ?: ORDINAL_WORDS[token]
            if (n != null && n in 1..all.size) return all[n - 1]
        }
        val matches = matchLabels(all, text, label)
        return matches.singleOrNull()
    }

    private fun billChoiceQuestion(candidates: List<BillEntity>): String =
        appContext.getString(
            R.string.voice_bill_which,
            candidates.mapIndexed { i, bill ->
                "${i + 1}. ${bill.provider}, due ${prettyDate(bill.dueDate)}"
            }.joinToString("; "),
        )

    private fun cancelChoiceQuestion(candidates: List<ReminderEntity>): String =
        appContext.getString(
            R.string.voice_cancel_which,
            candidates.mapIndexed { i, reminder ->
                "${i + 1}. ${reminder.title}, ${spokenWhen(reminder.triggerAtMillis)}"
            }.joinToString("; "),
        )

    private fun archiveChoiceQuestion(candidates: List<BillEntity>): String =
        billChoiceQuestion(candidates)

    private fun docChoiceQuestion(candidates: List<DocumentEntity>): String =
        appContext.getString(
            R.string.voice_doc_which,
            candidates.mapIndexed { i, doc -> "${i + 1}. ${doc.title}" }.joinToString("; "),
        )

    private fun completeChoiceQuestion(candidates: List<RoutineEntity>): String =
        appContext.getString(
            R.string.voice_routine_which,
            candidates.mapIndexed { i, routine -> "${i + 1}. ${routine.title}" }.joinToString("; "),
        )

    private fun completeConfirmQuestion(target: RoutineEntity): String =
        appContext.getString(R.string.voice_routine_confirm, target.title)

    private fun cancelConfirmQuestion(target: ReminderEntity): String =
        appContext.getString(
            R.string.voice_cancel_confirm,
            "${target.title}, ${spokenWhen(target.triggerAtMillis)}",
        )

    private fun archiveConfirmQuestion(target: BillEntity): String =
        appContext.getString(
            R.string.voice_archive_confirm,
            "${target.provider} bill, due ${prettyDate(target.dueDate)}",
        )

    /** ISO answers ("2026-12-01") speak as dates; everything else verbatim. */
    private fun prettyAnswer(raw: String): String {
        if (ISO_DATE.matches(raw)) return prettyDate(raw)
        return raw
    }

    private fun prettyDate(iso: String): String {
        val parsed = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        return parsed.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US))
    }

    private fun spokenWhen(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return at.format(DateTimeFormatter.ofPattern("EEEE, d MMMM 'at' h:mm a", Locale.US))
    }

    private fun spokenRecurrence(draft: PendingRoutine): String {
        val minutes = draft.minutes!!
        val time = java.time.LocalTime.of(minutes / 60, minutes % 60)
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return when {
            "DAILY" in draft.scheduleRule -> "every day at $time"
            "WEEKLY" in draft.scheduleRule -> "every week at $time"
            else -> "at $time"
        }
    }

    // Saving (same repositories as the manual confirm flows)

    private suspend fun save(current: PendingAction): ActionTurn {
        synchronized(lock) {
            if (saving) return ActionTurn(appContext.getString(R.string.voice_action_still_saving))
            saving = true
            // Clear first: a repeated "yes" during the save finds no pending
            // action and cannot double-save.
            pending = null
        }
        try {
            return when (current) {
                is PendingAppointment -> saveAppointment(current)
                is PendingRoutine -> saveRoutine(current)
                is PendingReminder -> saveReminder(current)
                is PendingCancelConfirm -> executeCancel(current.target)
                is PendingArchiveConfirm -> executeArchive(current.target)
                is PendingCompleteConfirm -> executeComplete(current.target)
                // Choice states never reach save (a bare "yes" re-asks).
                is PendingBillChoice, is PendingCancelChoice, is PendingArchiveChoice,
                is PendingDocChoice, is PendingCompleteChoice ->
                    ActionTurn(appContext.getString(R.string.error_generic))
            }
        } finally {
            synchronized(lock) { saving = false }
        }
    }

    private suspend fun saveAppointment(draft: PendingAppointment): ActionTurn {
        // Same entity mapping + repository as AppointmentsViewModel.confirmSave.
        // Manual save schedules nothing (travel nudge/calendar are separate
        // detail-screen actions), so there is nothing extra to report.
        val outcome = appointments.save(
            AppointmentEntity(
                title = draft.title.trim(),
                provider = draft.provider,
                location = draft.location,
                doctorName = draft.doctor,
                startMillis = draft.startMillis!!,
                endMillis = draft.endIso?.let { AppointmentsViewModel.parseDateTime(it) },
                prepNotes = draft.prepNotes,
            ),
        )
        if (outcome.validation.hasErrors || outcome.savedId == null) {
            return ActionTurn(appContext.getString(R.string.voice_action_save_failed))
        }
        return ActionTurn(
            appContext.getString(R.string.voice_action_saved_appt, spokenWhen(draft.startMillis)),
            saved = true,
        )
    }

    private suspend fun saveRoutine(draft: PendingRoutine): ActionTurn {
        // Same entity mapping, repository, and reminder scheduling as
        // RoutinesViewModel.confirmSave — except scheduling failures are
        // reported instead of swallowed, so a saved routine is never
        // silently missing its reminder.
        val minutes = draft.minutes!!
        val nextDue = RoutinesViewModel.nextDailyOccurrence(
            minutes / 60, minutes % 60, System.currentTimeMillis(),
        )
        val outcome = routines.save(
            RoutineEntity(
                title = draft.title.trim(),
                kind = draft.kind,
                scheduleRule = draft.scheduleRule,
                notes = draft.notes,
                nextDueMillis = nextDue,
            ),
        )
        val savedId = outcome.savedId
        if (outcome.validation.hasErrors || savedId == null) {
            return ActionTurn(appContext.getString(R.string.voice_action_save_failed))
        }
        val saved = appContext.getString(
            R.string.voice_action_saved_routine,
            "${draft.title}, ${draft.kind.lowercase()}, ${spokenRecurrence(draft)}",
        )
        return try {
            val id = reminders.create(
                kind = ReminderEntity.KIND_ROUTINE,
                title = draft.title.trim(),
                triggerAtMillis = nextDue,
                referenceId = savedId,
            )
            scheduler.schedule(id)
            ActionTurn(saved, saved = true)
        } catch (e: Exception) {
            ActionTurn(
                "$saved ${appContext.getString(R.string.voice_action_schedule_warn)}",
                saved = true,
            )
        }
    }

    private suspend fun saveReminder(draft: PendingReminder): ActionTurn {
        // Same repository + scheduler the manual paths use; scheduling
        // failures are reported instead of swallowed.
        val trigger = draft.triggerMillis!!
        val id = runCatching {
            reminders.create(
                kind = ReminderEntity.KIND_GENERAL,
                title = draft.title.trim(),
                triggerAtMillis = trigger,
            )
        }.getOrNull() ?: return ActionTurn(appContext.getString(R.string.voice_action_save_failed))
        val saved = appContext.getString(
            R.string.voice_reminder_saved,
            "${draft.title}, ${spokenWhen(trigger)}",
        )
        return try {
            scheduler.schedule(id)
            ActionTurn(saved, saved = true)
        } catch (e: Exception) {
            ActionTurn(
                "$saved ${appContext.getString(R.string.voice_action_schedule_warn)}",
                saved = true,
            )
        }
    }

    /** Same scheduler path as the manual reminder screen's confirm-cancel. */
    private suspend fun executeCancel(target: ReminderEntity): ActionTurn {
        runCatching { scheduler.cancel(target.id) }.getOrNull()
            ?: return ActionTurn(appContext.getString(R.string.voice_action_save_failed))
        return ActionTurn(appContext.getString(R.string.voice_cancelled), saved = true)
    }

    /** Same DAO archive the bills screen uses. */
    private suspend fun executeArchive(target: BillEntity): ActionTurn {
        runCatching { bills.archive(target.id) }.getOrNull()
            ?: return ActionTurn(appContext.getString(R.string.voice_action_save_failed))
        return ActionTurn(appContext.getString(R.string.voice_archived), saved = true)
    }

    /**
     * Same complete + reschedule-next the routine detail screen runs: stale
     * reminders drop, the rolled-forward due time schedules. A scheduling
     * failure is reported instead of silently missing the next reminder.
     */
    private suspend fun executeComplete(target: RoutineEntity): ActionTurn {
        runCatching { routines.complete(target.id) }.getOrNull()
            ?: return ActionTurn(appContext.getString(R.string.voice_action_save_failed))
        val done = appContext.getString(R.string.voice_routine_done, target.title)
        return try {
            runCatching {
                reminders.scheduledFor(ReminderEntity.KIND_ROUTINE, target.id).forEach {
                    scheduler.cancel(it.id)
                }
            }
            val updated = routines.getById(target.id)
            val nextDue = updated?.nextDueMillis
            if (nextDue != null && nextDue > System.currentTimeMillis()) {
                val id = reminders.create(
                    kind = ReminderEntity.KIND_ROUTINE,
                    title = updated.title,
                    triggerAtMillis = nextDue,
                    referenceId = target.id,
                )
                scheduler.schedule(id)
            }
            ActionTurn(done, saved = true)
        } catch (e: Exception) {
            ActionTurn(
                "$done ${appContext.getString(R.string.voice_action_schedule_warn)}",
                saved = true,
            )
        }
    }

    // Input matching

    private fun normalize(text: String): String = text.lowercase()
        .replace("’", "").replace("'", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun isCancel(norm: String): Boolean =
        norm in CANCEL_EXACT || (norm.startsWith("cancel ") && norm.length > 7)

    private fun isConfirm(norm: String): Boolean = norm in CONFIRM_EXACT

    private fun clear() {
        synchronized(lock) { pending = null }
    }

    /** Mirror of the ViewModel's failure mapping, for extractor calls. */
    private fun failureSpeech(e: Throwable?): String = when (e) {
        is SessionExpiredException -> appContext.getString(R.string.auth_sign_in_required)
        is RateLimitedException -> appContext.getString(R.string.error_rate_limit)
        is TimeoutException -> appContext.getString(R.string.voice_timeout)
        is OfflineModeException -> appContext.getString(R.string.voice_api_unavailable)
        else -> appContext.getString(R.string.error_generic)
    }

    companion object {
        /** Explicit non-daily scheduling the voice path refuses to confirm. */
        private val NON_DAILY_RULE = listOf("WEEK", "MONTH", "YEAR", "CUSTOM")

        /** Spoken hour numbers the time guard reads as digits. */
        private val NUMBER_WORDS = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12,
        )

        /** Ordinals for "the second one" style picks. */
        private val ORDINAL_WORDS = mapOf(
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4,
            "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        )

        /** Cap on spoken options; longer lists ask the user to narrow it down. */
        private const val MAX_CHOICES = 5

        /** Bare ISO date, spoken as a real date instead of digits. */
        private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** Spoken items per briefing section before "and N more". */
        private const val BRIEFING_MAX = 3

        /** Currency codes spoken as words; unknown codes read as-is. */
        private val CURRENCY_WORDS = mapOf(
            "USD" to "dollars", "MYR" to "ringgit", "EUR" to "euros",
            "GBP" to "pounds", "SGD" to "Singapore dollars", "JPY" to "yen",
            "CNY" to "yuan", "INR" to "rupees", "AUD" to "Australian dollars",
        )

        /** Filler words ignored when matching providers/titles. */
        private val MATCH_STOPWORDS = setOf(
            "the", "and", "for", "bill", "bills", "reminder", "remind",
            "cancel", "archive", "what", "when", "which", "about", "much",
        )

        /**
         * Short, unambiguous confirmations only. A bare "yes" saves; anything
         * longer ("yes, tomorrow at 9") is treated as an edit and re-asked —
         * safer than guessing what the extra words change.
         */
        private val CONFIRM_EXACT = setOf(
            "yes", "yeah", "yep", "yup", "yah", "sure", "confirm", "confirmed",
            "save", "save it", "ok", "okay", "correct", "right", "do it",
            "go ahead", "sounds good", "thats right", "that is right",
            "yes please", "please save", "absolutely", "definitely",
        )

        /**
         * Exact cancellations; "cancel …" with more words also cancels.
         * Note "no, …" with more words is deliberately NOT here: "no,
         * tomorrow at 9" is a correction (edit path), not a cancellation.
         */
        private val CANCEL_EXACT = setOf(
            "no", "nope", "nah", "no way", "cancel", "cancelled", "cancel it",
            "stop", "quit", "abort", "discard", "dont save", "do not save",
            "never mind", "nevermind", "forget it", "start over", "not now",
        )
    }
}
