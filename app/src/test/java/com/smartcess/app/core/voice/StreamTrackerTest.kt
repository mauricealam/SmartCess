package com.smartcess.app.core.voice

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rapid Talk restarts must not let the old stream's teardown kill the new
 * stream: per-stream state with identity-checked tracking.
 */
class StreamTrackerTest {

    @Test
    fun oldTeardown_cannotKillNewStream() {
        val tracker = StreamTracker()
        val old = tracker.begin()
        val fresh = tracker.begin()
        old.running.set(false)
        old.endStreamSent.set(true)
        assertTrue(fresh.running.get())
        assertSame(fresh, tracker.current())
    }

    @Test
    fun staleEnd_doesNotClearCurrent() {
        val tracker = StreamTracker()
        val old = tracker.begin()
        val fresh = tracker.begin()
        tracker.end(old)
        assertSame(fresh, tracker.current())
        tracker.end(fresh)
        assertNull(tracker.current())
    }

    @Test
    fun stopWithNoStream_isSafe() {
        val tracker = StreamTracker()
        assertNull(tracker.current())
    }
}
