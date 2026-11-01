package com.smartcess.app.features.appointments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Manual form and voice extraction share [AppointmentsViewModel.parseDateTime],
 * so every format the extractor may emit must parse — including ISO-style
 * replies with seconds (the field is named start_iso).
 */
class AppointmentDateParseTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun parsesPromptPinnedFormat() {
        assertEquals(
            millis(2026, 11, 2, 9, 0),
            AppointmentsViewModel.parseDateTime("2026-11-02 09:00"),
        )
    }

    @Test
    fun parsesIsoWithSeconds() {
        assertEquals(
            millis(2026, 11, 2, 9, 0),
            AppointmentsViewModel.parseDateTime("2026-11-02T09:00:00"),
        )
        assertEquals(
            millis(2026, 11, 2, 9, 0),
            AppointmentsViewModel.parseDateTime("2026-11-02 09:00:00"),
        )
    }

    @Test
    fun parsesInstantWithZone() {
        assertNotNull(AppointmentsViewModel.parseDateTime("2026-11-02T01:00:00Z"))
    }

    @Test
    fun rejectsBlankAndGarbage() {
        assertNull(AppointmentsViewModel.parseDateTime(""))
        assertNull(AppointmentsViewModel.parseDateTime("   "))
        assertNull(AppointmentsViewModel.parseDateTime("tomorrow at 9am"))
        assertNull(AppointmentsViewModel.parseDateTime("next Friday"))
    }
}
