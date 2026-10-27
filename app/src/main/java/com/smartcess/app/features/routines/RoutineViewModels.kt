package com.smartcess.app.features.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.data.repository.ReminderRepository
import com.smartcess.app.data.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

data class RoutineFormState(
    val title: String = "",
    val kind: String = RoutineEntity.KIND_MEDICATION,
    val scheduleRule: String = "",
    val notes: String = "",
    val describeText: String = "",
    val fieldErrorRes: Int? = null,
    val messageRes: Int? = null,
    val errorRes: Int? = null,
)

@HiltViewModel
class RoutinesViewModel @Inject constructor(
    private val routines: RoutineRepository,
    private val spark: SparkClient,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val prefs: PreferencesRepository,
) : ViewModel() {

    val items: StateFlow<List<RoutineEntity>> = routines.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(RoutineFormState())
    val form: StateFlow<RoutineFormState> = _form.asStateFlow()

    fun onForm(form: RoutineFormState) {
        _form.value = form.copy(fieldErrorRes = null, messageRes = null, errorRes = null)
    }

    /** Drafts a routine from plain words via Spark; offline leaves the form untouched. */
    fun draftFromWords() {
        val text = _form.value.describeText
        if (text.isBlank()) return
        viewModelScope.launch {
            val help = runCatching { spark.routineHelp(text) }.getOrNull()
            if (help == null) {
                _form.value = _form.value.copy(errorRes = R.string.banner_offline)
                return@launch
            }
            _form.value = _form.value.copy(
                title = help.title.ifBlank { _form.value.title },
                kind = help.kind.ifBlank { _form.value.kind },
                scheduleRule = help.schedule_rule.ifBlank { _form.value.scheduleRule },
                notes = help.notes ?: _form.value.notes,
            )
        }
    }

    /** Saves only after the caller shows the confirmation dialog, then schedules its reminder. */
    fun confirmSave(onSaved: (Long) -> Unit) {
        val form = _form.value
        if (form.title.isBlank() || form.scheduleRule.isBlank()) {
            _form.value = form.copy(fieldErrorRes = R.string.error_generic)
            return
        }
        viewModelScope.launch {
            val rule = form.scheduleRule.trim().uppercase()
            val minutes = parseScheduleMinutes(rule) ?: prefs.get().defaultReminderTime
            val nextDue = nextDailyOccurrence(minutes / 60, minutes % 60, System.currentTimeMillis())
            val outcome = routines.save(
                RoutineEntity(
                    title = form.title.trim(),
                    kind = form.kind,
                    scheduleRule = rule,
                    notes = form.notes.trim().ifBlank { null },
                    nextDueMillis = nextDue,
                ),
            )
            val savedId = outcome.savedId
            if (outcome.validation.hasErrors || savedId == null) {
                _form.value = form.copy(errorRes = R.string.error_generic)
            } else {
                scheduleRoutineReminder(savedId, form.title.trim(), nextDue)
                _form.value = RoutineFormState(messageRes = R.string.routine_saved)
                onSaved(savedId)
            }
        }
    }

    private suspend fun scheduleRoutineReminder(routineId: Long, title: String, triggerAt: Long) {
        runCatching {
            val id = reminders.create(
                kind = ReminderEntity.KIND_ROUTINE,
                title = title,
                triggerAtMillis = triggerAt,
                referenceId = routineId,
            )
            scheduler.schedule(id)
        }
    }

    companion object {
        private val TIME_IN_RULE = Regex("(\\d{1,2}):(\\d{2})")

        /** HH:MM inside a rule like "DAILY 08:00", as minutes since midnight. Null when absent. */
        fun parseScheduleMinutes(rule: String): Int? {
            val match = TIME_IN_RULE.find(rule) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        /** Next occurrence of a daily time-of-day, today or tomorrow. Pure and JVM-testable. */
        fun nextDailyOccurrence(
            hour: Int,
            minute: Int,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long {
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            var next = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59))
                .withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return next.atZone(zone).toInstant().toEpochMilli()
        }
    }
}

data class RoutineDetailUiState(
    val loading: Boolean = true,
    val routine: RoutineEntity? = null,
    val notFound: Boolean = false,
    val messageRes: Int? = null,
)

@HiltViewModel
class RoutineDetailViewModel @Inject constructor(
    private val routines: RoutineRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(RoutineDetailUiState())
    val state: StateFlow<RoutineDetailUiState> = _state.asStateFlow()

    fun load(routineId: Long) {
        viewModelScope.launch {
            val routine = routines.getById(routineId)
            _state.value = if (routine == null) {
                RoutineDetailUiState(loading = false, notFound = true)
            } else {
                RoutineDetailUiState(loading = false, routine = routine)
            }
        }
    }

    /** Marks complete only after the caller confirms. Tracking only, never advice. */
    fun confirmComplete() {
        val routine = _state.value.routine ?: return
        viewModelScope.launch {
            routines.complete(routine.id)
            rescheduleNext(routine.id)
            _state.value = _state.value.copy(messageRes = R.string.routine_completed)
            load(routine.id)
        }
    }

    /** Drops stale routine reminders and schedules the rolled-forward due time. */
    private suspend fun rescheduleNext(routineId: Long) {
        runCatching {
            reminders.scheduledFor(ReminderEntity.KIND_ROUTINE, routineId).forEach {
                scheduler.cancel(it.id)
            }
            val updated = routines.getById(routineId) ?: return
            val nextDue = updated.nextDueMillis
            if (nextDue != null && nextDue > System.currentTimeMillis()) {
                val id = reminders.create(
                    kind = ReminderEntity.KIND_ROUTINE,
                    title = updated.title,
                    triggerAtMillis = nextDue,
                    referenceId = routineId,
                )
                scheduler.schedule(id)
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val routine = _state.value.routine ?: return
        viewModelScope.launch {
            runCatching {
                reminders.scheduledFor(ReminderEntity.KIND_ROUTINE, routine.id).forEach {
                    scheduler.cancel(it.id)
                }
            }
            routines.delete(routine.id)
            onDone()
        }
    }
}
