package com.smartcess.app.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Travel-nudge re-derive math: reboot must restore future nudges and must
 * never resurrect one whose appointment already started. (WorkManager
 * enqueueing itself is untestable on JVM unit tests; that half is
 * code-inspected only.)
 */
class TravelTriggerTest {

    @Test
    fun travelTriggerAt_future_returnsStartMinusLead() {
        val now = 1_000_000_000_000L
        val start = now + 3_600_000L
        assertEquals(start - 30 * 60_000L, ReminderScheduler.travelTriggerAt(start, 30, now))
    }

    @Test
    fun travelTriggerAt_nullLead_returnsNull() {
        val now = 1_000_000_000_000L
        assertNull(ReminderScheduler.travelTriggerAt(now + 3_600_000L, null, now))
    }

    @Test
    fun travelTriggerAt_pastTrigger_returnsNull() {
        val now = 1_000_000_000_000L
        // Nudge time already passed: do not resurrect after reboot.
        assertNull(ReminderScheduler.travelTriggerAt(now + 600_000L, 30, now))
    }

    @Test
    fun travelTriggerAt_appointmentPast_returnsNull() {
        val now = 1_000_000_000_000L
        assertNull(ReminderScheduler.travelTriggerAt(now - 600_000L, 30, now))
    }
}
