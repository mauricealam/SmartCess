package com.smartcess.app.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Backend reachability for the "Preparing SmartCess…" state. */
enum class HealthState { UNKNOWN, WAKING, READY, DOWN }

/**
 * Warms the backend at launch (Render free sleeps without traffic; first
 * wake takes up to ~60s). Retries on a fixed schedule, then reports DOWN.
 */
@Singleton
class BackendHealthMonitor @Inject constructor(
    @Named("backendPlain") private val api: BackendAuthApi,
) {
    /** Overridden by tests for fast schedules; production uses [DEFAULT_DELAYS]. */
    var delaysMillis: List<Long> = DEFAULT_DELAYS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(HealthState.UNKNOWN)
    val state: StateFlow<HealthState> = _state
    private var job: Job? = null

    fun warmup() {
        if (_state.value == HealthState.READY) return
        job?.cancel()
        job = scope.launch {
            _state.value = HealthState.WAKING
            for (delayMillis in delaysMillis) {
                delay(delayMillis)
                if (runCatching { api.health() }.isSuccess) {
                    _state.value = HealthState.READY
                    return@launch
                }
            }
            _state.value = HealthState.DOWN
        }
    }

    fun retry() {
        _state.value = HealthState.UNKNOWN
        warmup()
    }

    companion object {
        /** Attempts at ~0s, ~5s, ~15s, ~35s, ~65s (each attempt may itself block). */
        val DEFAULT_DELAYS = listOf(0L, 5_000L, 10_000L, 20_000L, 30_000L)
    }
}
