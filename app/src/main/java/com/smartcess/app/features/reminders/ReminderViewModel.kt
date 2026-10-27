package com.smartcess.app.features.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ReminderUiState(
    val loading: Boolean = true,
    val reminder: ReminderEntity? = null,
    val notFound: Boolean = false,
    val messageRes: Int? = null,
    /** Days-before options for bill-linked reminders; empty when not applicable. */
    val leadOptions: List<Int> = emptyList(),
    val currentLeadDays: Int? = null,
)

@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val bills: BillRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReminderUiState())
    val state: StateFlow<ReminderUiState> = _state.asStateFlow()

    fun load(reminderId: Long) {
        viewModelScope.launch {
            val reminder = reminders.getById(reminderId)
            if (reminder == null) {
                _state.value = ReminderUiState(loading = false, notFound = true)
                return@launch
            }
            val (options, current) = leadChoices(reminder)
            _state.value = ReminderUiState(
                loading = false,
                reminder = reminder,
                leadOptions = options,
                currentLeadDays = current,
            )
        }
    }

    /** Days-before choices from the linked bill's due date (future triggers only). */
    private suspend fun leadChoices(reminder: ReminderEntity): Pair<List<Int>, Int?> {
        if (reminder.kind != ReminderEntity.KIND_BILL || reminder.referenceId <= 0) {
            return emptyList<Int>() to null
        }
        val bill = bills.getById(reminder.referenceId) ?: return emptyList<Int>() to null
        val dueMillis = runCatching {
            LocalDate.parse(bill.dueDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return emptyList<Int>() to null
        val now = System.currentTimeMillis()
        val options = LEAD_DAY_OPTIONS.filter { dueMillis - it * DAY_MILLIS > now }
        if (options.isEmpty()) return emptyList<Int>() to null
        val current = options.minByOrNull { option ->
            kotlin.math.abs((dueMillis - option * DAY_MILLIS) - reminder.triggerAtMillis)
        }
        return options to current
    }

    /** Changes the lead time only after the caller confirms the choice. */
    fun changeLeadTime(daysBefore: Int) {
        val reminder = _state.value.reminder ?: return
        viewModelScope.launch {
            if (reminder.kind != ReminderEntity.KIND_BILL || reminder.referenceId <= 0) return@launch
            val bill = bills.getById(reminder.referenceId) ?: return@launch
            val dueMillis = runCatching {
                LocalDate.parse(bill.dueDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return@launch
            val triggerAt = dueMillis - daysBefore * DAY_MILLIS
            if (triggerAt <= System.currentTimeMillis()) {
                _state.value = _state.value.copy(messageRes = R.string.error_generic)
                return@launch
            }
            reminders.retarget(reminder.id, triggerAt)
            scheduler.schedule(reminder.id)
            _state.value = _state.value.copy(messageRes = R.string.reminder_lead_updated)
            load(reminder.id)
        }
    }

    /** Confirms + schedules. The caller shows the confirmation dialog first. */
    fun confirmSchedule() {
        val reminder = _state.value.reminder ?: return
        viewModelScope.launch {
            scheduler.schedule(reminder.id)
            _state.value = _state.value.copy(messageRes = R.string.reminder_scheduled)
            load(reminder.id)
        }
    }

    /** Cancels. The caller shows the confirmation dialog first. */
    fun confirmCancel(onDone: () -> Unit) {
        val reminder = _state.value.reminder ?: return
        viewModelScope.launch {
            scheduler.cancel(reminder.id)
            _state.value = _state.value.copy(messageRes = R.string.reminder_cancelled)
            onDone()
        }
    }

    fun markComplete(onDone: () -> Unit) {
        val reminder = _state.value.reminder ?: return
        viewModelScope.launch {
            reminders.complete(reminder.id)
            _state.value = _state.value.copy(messageRes = R.string.reminder_completed)
            onDone()
        }
    }

    companion object {
        private val LEAD_DAY_OPTIONS = listOf(0, 1, 3, 7)
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
