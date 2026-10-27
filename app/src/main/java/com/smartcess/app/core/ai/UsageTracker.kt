package com.smartcess.app.core.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Per-feature usage stats for cost control. Metadata only, no text or images. */
data class UsageStats(val calls: Int = 0, val failures: Int = 0, val totalLatencyMillis: Long = 0)

@Singleton
class UsageTracker @Inject constructor() {
    private val stats = ConcurrentHashMap<String, UsageStats>()
    private val _snapshot = MutableStateFlow<Map<String, UsageStats>>(emptyMap())
    val snapshot: StateFlow<Map<String, UsageStats>> = _snapshot

    fun record(feature: String, latencyMillis: Long, success: Boolean) {
        stats.compute(feature) { _, prev ->
            val base = prev ?: UsageStats()
            base.copy(
                calls = base.calls + 1,
                failures = base.failures + if (success) 0 else 1,
                totalLatencyMillis = base.totalLatencyMillis + latencyMillis,
            )
        }
        _snapshot.value = HashMap(stats)
    }

    fun snapshotNow(): Map<String, UsageStats> = HashMap(stats)
    fun reset() {
        stats.clear()
        _snapshot.value = emptyMap()
    }
}
