package com.smartcess.app.features.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.repository.BriefingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BriefingUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val signInRequired: Boolean = false,
    val aiBriefing: String? = null,
    val overdueBills: List<BillEntity> = emptyList(),
    val billsDueSoon: List<BillEntity> = emptyList(),
    val appointments: List<AppointmentEntity> = emptyList(),
    val urgentEmails: List<EmailItemEntity> = emptyList(),
    val documents: List<DocumentEntity> = emptyList(),
    val routines: List<RoutineEntity> = emptyList(),
    val errorRes: Int? = null,
) {
    val isEmpty: Boolean get() =
        overdueBills.isEmpty() && billsDueSoon.isEmpty() && appointments.isEmpty() &&
            urgentEmails.isEmpty() && documents.isEmpty() && routines.isEmpty()
}

@HiltViewModel
class BriefingViewModel @Inject constructor(
    private val briefing: BriefingRepository,
    private val spark: SparkClient,
    private val speaker: Speaker,
) : ViewModel() {

    private val _state = MutableStateFlow(BriefingUiState())
    val state: StateFlow<BriefingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = BriefingUiState(loading = true)
            val data = runCatching { briefing.getTodayData() }.getOrNull()
            if (data == null) {
                _state.value = BriefingUiState(loading = false, errorRes = R.string.error_generic)
                return@launch
            }
            var aiBriefing: String? = null
            var offline = false
            var signIn = false
            if (!data.isEmpty) {
                aiBriefing = runCatching {
                    spark.dailyBriefing(
                        "overdue=${data.overdueBills.size}," +
                            "dueSoon=${data.billsDueSoon.size}," +
                            "appointments=${data.appointments.size}," +
                            "urgentEmail=${data.urgentEmails.size}," +
                            "documents=${data.documentsWithDeadlines.size}," +
                            "routines=${data.routinesDue.size}",
                    ).briefing.ifBlank { null }
                }.getOrElse { e ->
                    if (e is OfflineModeException) offline = true
                    if (e is SessionExpiredException) signIn = true
                    null
                }
            }
            _state.value = BriefingUiState(
                loading = false,
                offline = offline,
                signInRequired = signIn,
                aiBriefing = aiBriefing,
                overdueBills = data.overdueBills,
                billsDueSoon = data.billsDueSoon,
                appointments = data.appointments,
                urgentEmails = data.urgentEmails,
                documents = data.documentsWithDeadlines,
                routines = data.routinesDue,
            )
        }
    }

    /** Reads the briefing aloud. Spoken text is composed from strings.xml by the caller. */
    fun speak(spoken: String) = speaker.speak(spoken)

    fun stop() = speaker.stop()
}
