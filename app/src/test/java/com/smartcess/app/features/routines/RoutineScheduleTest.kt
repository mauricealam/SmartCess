package com.smartcess.app.features.routines

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for routine schedule parsing and daily due-time computation. */
class RoutineScheduleTest {

    @Test
    fun parseScheduleMinutes_readsDailyTime() {
        assertEquals(480, RoutinesViewModel.parseScheduleMinutes("DAILY 08:00"))
        assertEquals(0, RoutinesViewModel.parseScheduleMinutes("DAILY 00:00"))
        assertEquals(1439, RoutinesViewModel.parseScheduleMinutes("WEEKLY MON 23:59"))
    }

    @Test
    fun parseScheduleMinutes_rejectsGarbage() {
        assertNull(RoutinesViewModel.parseScheduleMinutes("DAILY"))
        assertNull(RoutinesViewModel.parseScheduleMinutes("DAILY 25:00"))
        assertNull(RoutinesViewModel.parseScheduleMinutes(""))
    }

    @Test
    fun nextDailyOccurrence_todayWhenFuture() {
        val zone = ZoneId.of("UTC")
        // 2026-10-27T10:00Z; 15:30 is still ahead today.
        val now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli()
        val next = RoutinesViewModel.nextDailyOccurrence(15, 30, now, zone)
        assertEquals(Instant.parse("2026-10-27T15:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun nextDailyOccurrence_tomorrowWhenPast() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli()
        val next = RoutinesViewModel.nextDailyOccurrence(8, 0, now, zone)
        assertEquals(Instant.parse("2026-10-28T08:00:00Z").toEpochMilli(), next)
        assertTrue(next > now)
    }
}
