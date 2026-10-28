package com.smartcess.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** M7: token usage is accumulated per feature (was parsed but never consumed). */
class UsageTrackerTest {

    @Test
    fun record_accumulatesTokens() {
        val tracker = UsageTracker()
        tracker.record("bill_extract", 100, true, promptTokens = 18, completionTokens = 186)
        tracker.record("bill_extract", 50, false, promptTokens = 10, completionTokens = 5)
        val stats = tracker.snapshotNow()["bill_extract"] ?: error("missing stats")
        assertEquals(2, stats.calls)
        assertEquals(1, stats.failures)
        assertEquals(150L, stats.totalLatencyMillis)
        assertEquals(28, stats.promptTokens)
        assertEquals(191, stats.completionTokens)
    }

    @Test
    fun record_defaultsToZeroTokens() {
        val tracker = UsageTracker()
        tracker.record("intent", 10, true)
        val stats = tracker.snapshotNow()["intent"] ?: error("missing stats")
        assertEquals(0, stats.promptTokens)
        assertEquals(0, stats.completionTokens)
    }
}
