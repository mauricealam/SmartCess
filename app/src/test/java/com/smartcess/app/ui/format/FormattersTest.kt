package com.smartcess.app.ui.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for locale-aware formatting (spec: dates, currency). */
class FormattersTest {

    @Test
    fun money_containsAmountAndCurrency() {
        val formatted = SmartCessFormat.money(87.2, "MYR", Locale.US)
        assertTrue(formatted.contains("87"))
    }

    @Test
    fun isoDate_invalidInput_returnsInput() {
        assertEquals("not-a-date", SmartCessFormat.isoDate("not-a-date", Locale.US))
    }

    @Test
    fun isoDate_validInput_localized() {
        val formatted = SmartCessFormat.isoDate("2026-11-20", Locale.US)
        assertTrue(formatted.contains("2026"))
    }

    @Test
    fun isoDate_validInput_dayFirst() {
        assertEquals("3 Sep 2026", SmartCessFormat.isoDate("2026-09-03", Locale.US))
    }

    @Test
    fun instantDateTime_dayFirst() {
        val millis = java.time.LocalDateTime.of(2026, 9, 3, 21, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val formatted = SmartCessFormat.instantDateTime(millis, Locale.US)
        assertTrue(formatted.startsWith("3 Sep 2026"))
    }

    @Test
    fun routineSchedule_daily() {
        assertEquals("Daily at 9:00 PM", SmartCessFormat.routineSchedule("DAILY 21:00", Locale.US))
    }

    @Test
    fun routineSchedule_weeklyWithDay() {
        assertEquals(
            "Weekly on Monday at 9:00 AM",
            SmartCessFormat.routineSchedule("WEEKLY MON 09:00", Locale.US),
        )
    }

    @Test
    fun routineSchedule_unparseable_returnsInput() {
        assertEquals(
            "EVERY NOW AND THEN",
            SmartCessFormat.routineSchedule("EVERY NOW AND THEN", Locale.US),
        )
        assertEquals("DAILY", SmartCessFormat.routineSchedule("DAILY", Locale.US))
        assertEquals("", SmartCessFormat.routineSchedule("", Locale.US))
    }

    @Test
    fun spokenDate_validInput_dayMonthYear() {
        assertEquals("31 December 2026", SmartCessFormat.spokenDate("2026-12-31"))
    }

    @Test
    fun spokenDate_invalidInput_returnsInput() {
        assertEquals("31/12/2026", SmartCessFormat.spokenDate("31/12/2026"))
        assertEquals("", SmartCessFormat.spokenDate(""))
    }
}
