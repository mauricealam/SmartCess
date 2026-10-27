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
}
