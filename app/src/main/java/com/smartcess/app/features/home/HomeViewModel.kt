package com.smartcess.app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.config.ApiKeyProvider
import com.smartcess.app.data.repository.BriefingRepository
import com.smartcess.app.data.repository.TodayData
import com.smartcess.app.ui.format.SmartCessFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One urgent row for the Home Today card. Text resolves from strings.xml at the call site. */
data class TodayPreviewItem(
    val textRes: Int,
    val args: List<String> = emptyList(),
)

data class HomeUiState(
    val loading: Boolean = true,
    /** Max 3 urgent items, per spec progressive disclosure. */
    val items: List<TodayPreviewItem> = emptyList(),
    val extraCount: Int = 0,
    /** True when the briefing read failed: render the error branch, never calm empty. */
    val loadFailed: Boolean = false,
    /** True when no Meta key is set: AI features degraded (matches sibling screens). */
    val aiOffline: Boolean = false,
)

/**
 * Pure preview builder: flattens [TodayData] into ranked rows, max 3 plus an
 * overflow count. Kept side-effect-free so unit tests can pin the mapping.
 */
fun buildHomePreview(data: TodayData): HomeUiState {
    val all = buildList {
        data.overdueBills.forEach {
            add(TodayPreviewItem(R.string.home_today_overdue, listOf(it.provider)))
        }
        data.billsDueSoon.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_due_soon,
                    listOf(it.provider, SmartCessFormat.isoDate(it.dueDate)),
                ),
            )
        }
        data.appointments.forEach {
            add(
                TodayPreviewItem(
                    R.string.home_today_appointment,
                    listOf(it.title, SmartCessFormat.instantDateTime(it.startMillis)),
                ),
            )
        }
        data.urgentEmails.forEach {
            add(TodayPreviewItem(R.string.home_today_email, listOf(it.subject)))
        }
        data.routinesDue.forEach {
            add(TodayPreviewItem(R.string.home_today_routine, listOf(it.title)))
        }
        data.documentsWithDeadlines.forEach {
            add(TodayPreviewItem(R.string.home_today_document, listOf(it.title)))
        }
    }
    return HomeUiState(
        loading = false,
        items = all.take(3),
        extraCount = (all.size - 3).coerceAtLeast(0),
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val briefing: BriefingRepository,
    private val apiKeys: ApiKeyProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        // Guard concurrent refreshes (double-tap retry): one load at a time.
        // Tracked by job, not by the loading flag — the initial state already
        // reports loading, so a flag guard would swallow the first load.
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.value = HomeUiState(loading = true)
            val data = runCatching { briefing.getTodayData() }.getOrNull()
            val offline = !apiKeys.hasMetaApiKey()
            _state.value = if (data == null) {
                HomeUiState(loading = false, loadFailed = true, aiOffline = offline)
            } else {
                buildHomePreview(data).copy(aiOffline = offline)
            }
        }
    }
}
