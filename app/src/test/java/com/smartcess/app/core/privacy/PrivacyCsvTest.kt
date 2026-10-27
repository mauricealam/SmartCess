package com.smartcess.app.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for CSV export escaping (spec: JSON or CSV export). */
class PrivacyCsvTest {

    @Test
    fun plainCell_unchanged() {
        assertEquals("Tenaga", PrivacyManager.csvCell("Tenaga"))
    }

    @Test
    fun commaCell_quoted() {
        assertEquals("\"Hello, world\"", PrivacyManager.csvCell("Hello, world"))
    }

    @Test
    fun quoteCell_doubled() {
        assertEquals("\"say \"\"hi\"\"\"", PrivacyManager.csvCell("say \"hi\""))
    }

    @Test
    fun newlineCell_quoted() {
        assertEquals("\"a\nb\"", PrivacyManager.csvCell("a\nb"))
    }
}
