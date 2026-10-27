package com.smartcess.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for offline bill drafts from on-device OCR (spec offline fallback). */
class OfflineDraftsTest {

    @Test
    fun amountAndDate_extracted() {
        val draft = OfflineDrafts.billFromOcr("Total RM 87.40 due 2026-11-15 thanks")
        assertEquals(87.40, draft.amount, 0.001)
        assertEquals("2026-11-15", draft.due_date)
    }

    @Test
    fun missingFields_listedWhenAbsent() {
        val draft = OfflineDrafts.billFromOcr("hello world")
        assertTrue(draft.missing_fields.contains("provider"))
        assertTrue(draft.missing_fields.contains("amount"))
        assertTrue(draft.missing_fields.contains("due_date"))
    }

    @Test
    fun draft_neverConfident() {
        val draft = OfflineDrafts.billFromOcr("Total 10.00 due 2026-11-15")
        assertEquals(0.0, draft.confidence, 0.0)
        assertTrue(draft.summary.startsWith(ContentSafety.OFFLINE_LABEL))
    }
}
