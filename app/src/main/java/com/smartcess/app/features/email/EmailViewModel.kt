package com.smartcess.app.features.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.repository.EmailRepository
import com.smartcess.app.data.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class EmailUiState(
    val briefing: String? = null,
    val offline: Boolean = false,
    val messageRes: Int? = null,
    val errorRes: Int? = null,
)

@HiltViewModel
class EmailViewModel @Inject constructor(
    private val emails: EmailRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val spark: SparkClient,
    private val speaker: Speaker,
) : ViewModel() {

    val items: StateFlow<List<EmailItemEntity>> = emails.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(EmailUiState())
    val state: StateFlow<EmailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            emails.seedDemoInboxIfEmpty()
            refreshBriefing()
        }
    }

    fun refreshBriefing() {
        viewModelScope.launch {
            val current = items.value
            if (current.isEmpty()) return@launch
            val json = kotlinx.serialization.json.buildJsonArray {
                current.forEach {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("sender", JsonPrimitive(it.sender))
                            put("subject", JsonPrimitive(it.subject))
                            put("urgency", JsonPrimitive(it.urgency))
                        },
                    )
                }
            }.toString()
            val result = runCatching { spark.briefEmails(json) }
            val briefing = result.getOrNull()?.briefing?.ifBlank { null }
            _state.value = _state.value.copy(
                briefing = briefing,
                offline = result.exceptionOrNull() is OfflineModeException,
            )
        }
    }

    fun markRead(id: Long) {
        viewModelScope.launch { emails.markRead(id) }
    }

    /** Reads one email aloud. Spoken text is composed from strings.xml by the caller. */
    fun speak(spoken: String) = speaker.speak(spoken)

    fun stop() = speaker.stop()

    /** Creates a deadline reminder only after the caller confirms. */
    fun confirmDeadlineReminder(email: EmailItemEntity) {
        val deadline = email.deadline ?: return
        viewModelScope.launch {
            val triggerAt = runCatching {
                LocalDate.parse(deadline).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull() ?: return@launch
            if (triggerAt <= System.currentTimeMillis()) {
                _state.value = _state.value.copy(errorRes = R.string.error_generic)
                return@launch
            }
            val id = reminders.create(
                kind = ReminderEntity.KIND_GENERAL,
                title = email.subject,
                triggerAtMillis = triggerAt,
                referenceId = email.id,
                detail = email.sender,
            )
            scheduler.schedule(id)
            _state.value = _state.value.copy(messageRes = R.string.bill_reminder_set)
        }
    }
}
