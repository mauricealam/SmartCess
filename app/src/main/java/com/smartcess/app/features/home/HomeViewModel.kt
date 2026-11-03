package com.smartcess.app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.session.BackendHealthMonitor
import com.smartcess.app.core.session.HealthState
import com.smartcess.app.core.session.SessionManager
import com.smartcess.app.data.repository.AppointmentRepository
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.BriefingRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.RoutineRepository
import com.smartcess.app.data.repository.TodayData
import com.smartcess.app.ui.format.SmartCessFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Entity kind behind a Home Today row: drives tap navigation and delete. */
enum class TodayItemKind {
    BILL, APPOINTMENT, EMAIL, ROUTINE, DOCUMENT,
}

/** One urgent row for the Home Today card. Text resolves from strings.xml at the call site. */
data class TodayPreviewItem(
    val textRes: Int,
    val args: List<String> = emptyList(),
    val kind: TodayItemKind,
    val id: Long,
) {
    /** Display name for delete prompts: first arg (provider/title/subject). */
    val displayName: String get() = args.firstOrNull().orEmpty()
}

data class HomeUiState(
    val loading: Boolean = true,
    /** Max 3 urgent items, per spec progressive disclosure. */
    val items: List<TodayPreviewItem> = emptyList(),
    val extraCount: Int = 0,
    /** True when the briefing read failed: render the error branch, never calm empty. */
    val loadFailed: Boolean = false,
    /** True when signed out: AI features degraded (matches sibling screens). */
    val aiOffline: Boolean = false,
)

/**
 * Pure preview builder: flattens [TodayData] into ranked rows, max 3 plus an
 * overflow count. Kept side-effect-free so unit tests can pin the mapping.
 */
fun buildHomePreview(data: TodayData): HomeUiState {
    val all = buildList {
        data.overdueBills.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_overdue, listOf(it.provider),
                    TodayItemKind.BILL, it.id,
                ),
            )
        }
        data.billsDueSoon.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_due_soon,
                    listOf(it.provider, SmartCessFormat.isoDate(it.dueDate)),
                    TodayItemKind.BILL, it.id,
                ),
            )
        }
        data.appointments.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_appointment,
                    listOf(it.title, SmartCessFormat.instantDateTime(it.startMillis)),
                    TodayItemKind.APPOINTMENT, it.id,
                ),
            )
        }
        data.urgentEmails.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_email, listOf(it.subject, it.sender),
                    TodayItemKind.EMAIL, it.id,
                ),
            )
        }
        data.routinesDue.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_routine, listOf(it.title),
                    TodayItemKind.ROUTINE, it.id,
                ),
            )
        }
        data.documentsWithDeadlines.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_document, listOf(it.title),
                    TodayItemKind.DOCUMENT, it.id,
                ),
            )
        }
    }
    return HomeUiState(
        loading = false,
        items = all.take(3),
        extraCount = (all.size - 3).coerceAtLeast(0),
    )
}

/** Home greeting by local hour (5-12 morning, 12-18 afternoon, 18-22 evening, else night). */
fun greetingResFor(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.home_greeting_morning
    in 12..17 -> R.string.home_greeting_afternoon
    in 18..21 -> R.string.home_greeting_evening
    else -> R.string.home_greeting_night
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val briefing: BriefingRepository,
    private val sessions: SessionManager,
    private val monitor: BackendHealthMonitor,
    private val bills: BillRepository,
    private val appointments: AppointmentRepository,
    private val routines: RoutineRepository,
    private val documents: DocumentRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Backend warm-up state for the "Preparing SmartCess…" banner. */
    val health: StateFlow<HealthState> = monitor.state
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        // A stale DOWN banner recovers without an app restart: re-probe once.
        if (monitor.state.value == HealthState.DOWN) monitor.retry()
        // Guard concurrent refreshes (double-tap retry): one load at a time.
        // Tracked by job, not by the loading flag — the initial state already
        // reports loading, so a flag guard would swallow the first load.
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.value = HomeUiState(loading = true)
            val data = runCatching { briefing.getTodayData() }.getOrNull()
            val offline = !sessions.isSignedIn()
            _state.value = if (data == null) {
                HomeUiState(loading = false, loadFailed = true, aiOffline = offline)
            } else {
                buildHomePreview(data).copy(aiOffline = offline)
            }
        }
    }

    /** Deletes one Today row by kind, then reloads. Demo emails have no delete. */
    fun deleteItem(item: TodayPreviewItem) {
        viewModelScope.launch {
            when (item.kind) {
                TodayItemKind.BILL -> bills.delete(item.id)
                TodayItemKind.APPOINTMENT -> appointments.delete(item.id)
                TodayItemKind.ROUTINE -> routines.delete(item.id)
                TodayItemKind.DOCUMENT -> documents.delete(item.id)
                TodayItemKind.EMAIL -> return@launch
            }
            refresh()
        }
    }
}
