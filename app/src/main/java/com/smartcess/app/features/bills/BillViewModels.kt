package com.smartcess.app.features.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.LOW_CONFIDENCE_THRESHOLD
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.data.repository.ReminderRepository
import com.smartcess.app.data.repository.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(
    billRepository: BillRepository,
) : ViewModel() {
    val active: StateFlow<List<BillEntity>> = billRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val archived: StateFlow<List<BillEntity>> = billRepository.observeArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class BillResultUiState(
    val loading: Boolean = true,
    val bill: BillEntity? = null,
    val notFound: Boolean = false,
    /** Editable review fields (user corrects extraction mistakes here). */
    val provider: String = "",
    val amount: String = "",
    val currency: String = "",
    val dueDate: String = "",
    val fieldErrorRes: Int? = null,
    val validation: ValidationResult? = null,
    val savedMessageRes: Int? = null,
    val archivedMessageRes: Int? = null,
    val reminderMessageRes: Int? = null,
    val errorRes: Int? = null,
    /** Local Q&A over saved bill fields (spec: answers use saved information). */
    val question: String = "",
    val answer: String? = null,
    val noAnswer: Boolean = false,
) {
    val lowConfidence: Boolean get() = (bill?.confidence ?: 1.0) < LOW_CONFIDENCE_THRESHOLD
}

@HiltViewModel
class BillResultViewModel @Inject constructor(
    private val bills: BillRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val prefs: PreferencesRepository,
    private val speaker: Speaker,
) : ViewModel() {

    private val _state = MutableStateFlow(BillResultUiState())
    val state: StateFlow<BillResultUiState> = _state.asStateFlow()

    fun load(billId: Long) {
        viewModelScope.launch {
            val bill = bills.getById(billId)
            if (bill == null) {
                _state.value = BillResultUiState(loading = false, notFound = true)
                return@launch
            }
            _state.value = BillResultUiState(
                loading = false,
                bill = bill,
                provider = bill.provider,
                amount = if (bill.amount > 0) bill.amount.toString() else "",
                currency = bill.currency,
                dueDate = bill.dueDate,
            )
        }
    }

    fun onField(provider: String, amount: String, currency: String, dueDate: String) {
        _state.value = _state.value.copy(
            provider = provider, amount = amount, currency = currency, dueDate = dueDate,
            savedMessageRes = null, fieldErrorRes = null,
        )
    }

    /** Saves only after the caller shows the confirmation dialog. */
    fun confirmSave() {
        val current = _state.value
        val bill = current.bill ?: return
        val amount = current.amount.toDoubleOrNull()
        if (amount == null) {
            _state.value = current.copy(fieldErrorRes = R.string.error_generic)
            return
        }
        viewModelScope.launch {
            val outcome = bills.save(
                bill.copy(
                    provider = current.provider.trim(),
                    amount = amount,
                    currency = current.currency.trim().uppercase(),
                    dueDate = current.dueDate.trim(),
                ),
            )
            if (outcome.validation.hasErrors) {
                _state.value = current.copy(validation = outcome.validation, errorRes = R.string.error_generic)
            } else {
                load(bill.id)
                _state.value = _state.value.copy(
                    validation = outcome.validation,
                    savedMessageRes = R.string.bill_saved,
                )
            }
        }
    }

    /** Archives only after the caller shows the confirmation dialog. */
    fun confirmArchive(onDone: () -> Unit) {
        val bill = _state.value.bill ?: return
        viewModelScope.launch {
            bills.archive(bill.id)
            _state.value = _state.value.copy(archivedMessageRes = R.string.bill_archived)
            onDone()
        }
    }

    /** Creates + schedules the reminder only after the caller confirms days-before. */
    fun confirmReminder(daysBefore: Int) {
        val bill = _state.value.bill ?: return
        viewModelScope.launch {
            val dueMillis = runCatching {
                LocalDate.parse(bill.dueDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return@launch
            val spec = ReminderScheduler.billReminderSpec(bill.id, bill.provider, dueMillis, daysBefore)
            val triggerAt = spec.third
            if (triggerAt <= System.currentTimeMillis()) {
                _state.value = _state.value.copy(errorRes = R.string.error_generic)
                return@launch
            }
            val id = reminders.create(
                kind = ReminderEntity.KIND_BILL,
                title = bill.provider,
                triggerAtMillis = triggerAt,
                referenceId = bill.id,
            )
            scheduler.schedule(id)
            _state.value = _state.value.copy(reminderMessageRes = R.string.bill_reminder_set)
        }
    }

    suspend fun defaultReminderDays(): Int = prefs.get().defaultBillReminderDays

    fun speak(text: String) = speaker.speak(text)

    fun stop() = speaker.stop()

    /** Discards only after the caller shows the confirmation dialog. */
    fun confirmDiscard(onDone: () -> Unit) {
        val bill = _state.value.bill ?: return
        viewModelScope.launch {
            bills.delete(bill.id)
            onDone()
        }
    }

    fun onQuestion(value: String) {
        _state.value = _state.value.copy(question = value, answer = null, noAnswer = false)
    }

    /** On-device Q&A over the saved bill: no network, answers from local fields. */
    fun ask() {
        val bill = _state.value.bill ?: return
        val question = _state.value.question.trim()
        if (question.isEmpty()) return
        val answer = answerBillQuestion(bill, question)
        _state.value = _state.value.copy(answer = answer, noAnswer = answer == null)
    }

    companion object {
        /**
         * Keyword Q&A over saved fields. Pure and JVM-testable; the screen
         * shows [R.string.bill_no_answer] when this returns null.
         */
        fun answerBillQuestion(bill: BillEntity, question: String): String? {
            val q = question.lowercase()
            fun has(vararg words: String): Boolean = words.any { q.contains(it) }
            return when {
                has("due", "date", "when", "deadline") ->
                    if (bill.dueDate.isNotBlank()) bill.dueDate else null
                has("amount", "much", "cost", "owe", "pay") ->
                    if (bill.amount > 0) "${bill.amount} ${bill.currency}".trim() else null
                has("provider", "who", "from", "company") ->
                    bill.provider.ifBlank { null }
                has("account", "reference", "number") ->
                    bill.accountReference
                has("period", "month", "billing") ->
                    bill.displayPeriod()
                has("summary", "what", "about", "action") ->
                    bill.summary.ifBlank { null }
                else -> bill.summary.ifBlank { null }
                    ?.split(Regex("(?<=[.!?])\\s+"))
                    ?.maxByOrNull { sentence ->
                        q.split(Regex("[^a-z0-9]+")).count { it.length > 3 && sentence.lowercase().contains(it) }
                    }?.takeIf { sentence ->
                        q.split(Regex("[^a-z0-9]+")).any { it.length > 3 && sentence.lowercase().contains(it) }
                    }
            }
        }
    }
}

data class ComparisonUiState(
    val loading: Boolean = true,
    val current: BillEntity? = null,
    val previous: BillEntity? = null,
    val recommendation: String? = null,
    val reasons: List<String> = emptyList(),
    val offline: Boolean = false,
    val signInRequired: Boolean = false,
    val errorRes: Int? = null,
)

@HiltViewModel
class ComparisonViewModel @Inject constructor(
    private val bills: BillRepository,
    private val spark: SparkClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ComparisonUiState())
    val state: StateFlow<ComparisonUiState> = _state.asStateFlow()

    fun load(billId: Long) {
        viewModelScope.launch {
            val current = bills.getById(billId)
            if (current == null) {
                _state.value = ComparisonUiState(loading = false, errorRes = R.string.error_not_found)
                return@launch
            }
            val previous = bills.previousFor(current.provider, current.id)
            if (previous == null) {
                _state.value = ComparisonUiState(loading = false, current = current)
                return@launch
            }
            val result = runCatching {
                spark.compareBills(
                    currentJson = current.toString(),
                    previousJson = previous.toString(),
                )
            }
            val ai = result.getOrNull()
            val signIn = result.exceptionOrNull() is SessionExpiredException
            _state.value = ComparisonUiState(
                loading = false,
                current = current,
                previous = previous,
                recommendation = ai?.recommendation?.ifBlank { null },
                reasons = ai?.possible_reasons ?: emptyList(),
                offline = ai == null && !signIn,
                signInRequired = signIn,
            )
        }
    }
}
