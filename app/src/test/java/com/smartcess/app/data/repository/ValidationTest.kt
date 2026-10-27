package com.smartcess.app.data.repository

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for bill validation rules (spec: validation, dates, currency, 0.70 gate). */
class ValidationTest {

    private val today = LocalDate.parse("2026-10-27")

    @Test
    fun validBill_hasNoErrors() {
        val result = validateBill(
            provider = "Tenaga",
            amount = 123.45,
            currency = "MYR",
            dueDate = "2026-11-15",
            requiredAction = "pay_before_due_date",
            confidence = 0.95,
            today = today,
        )
        assertFalse(result.hasErrors)
        assertFalse(result.needsReview)
    }

    @Test
    fun blankProvider_isError() {
        val result = validateBill("", 10.0, "MYR", "2026-11-15", "review_bill", 0.9, today)
        assertTrue(result.hasErrors)
    }

    @Test
    fun zeroAmount_isError() {
        val result = validateBill("Tenaga", 0.0, "MYR", "2026-11-15", "review_bill", 0.9, today)
        assertTrue(result.hasErrors)
    }

    @Test
    fun badCurrency_isError() {
        val result = validateBill("Tenaga", 10.0, "Ringgit", "2026-11-15", "review_bill", 0.9, today)
        assertTrue(result.hasErrors)
    }

    @Test
    fun pastDueDate_isWarningNotError() {
        val result = validateBill("Tenaga", 10.0, "MYR", "2026-10-01", "review_bill", 0.9, today)
        assertFalse(result.hasErrors)
        assertTrue(result.needsReview)
    }

    @Test
    fun invalidDueDate_isError() {
        val result = validateBill("Tenaga", 10.0, "MYR", "not-a-date", "review_bill", 0.9, today)
        assertTrue(result.hasErrors)
    }

    @Test
    fun lowConfidence_needsReview() {
        val result = validateBill("Tenaga", 10.0, "MYR", "2026-11-15", "review_bill", 0.5, today)
        assertFalse(result.hasErrors)
        assertTrue(result.needsReview)
    }

    @Test
    fun normalizeRequiredAction_defaultsToReview() {
        assertTrue(normalizeRequiredAction("pay_bill_now") == "review_bill")
        assertTrue(normalizeRequiredAction("pay_before_due_date") == "pay_before_due_date")
    }

    @Test
    fun blankDocumentTitle_defaultsToUntitled() {
        assertTrue(normalizeDocumentTitle("  ") == "Untitled document")
        assertTrue(normalizeDocumentTitle("Lease") == "Lease")
    }

    @Test
    fun emptySummary_isDocumentError() {
        val result = validateDocument("", "2026-11-01", 0.9)
        assertTrue(result.hasErrors)
    }

    @Test
    fun invalidDeadline_isDocumentWarning() {
        val result = validateDocument("ok", "not-a-date", 0.9)
        assertFalse(result.hasErrors)
        assertTrue(result.needsReview)
    }

    @Test
    fun validAppointment_hasNoErrors() {
        val result = validateAppointment("Clinic", 1767225600000L, 1767229200000L, 0.9, 1L)
        assertFalse(result.hasErrors)
    }

    @Test
    fun badAppointmentTimes_areErrors() {
        assertTrue(validateAppointment("", 10L, null, 0.9, 1L).hasErrors)
        assertTrue(validateAppointment("T", 10L, 5L, 0.9, 1L).hasErrors)
    }

    @Test
    fun pastAppointment_isWarning() {
        val result = validateAppointment("T", 10L, null, 0.9, 100L)
        assertFalse(result.hasErrors)
        assertTrue(result.needsReview)
    }

    @Test
    fun routineRules_requireTitleAndSchedule() {
        assertTrue(validateRoutine("", "DAILY").hasErrors)
        assertTrue(validateRoutine("T", "").hasErrors)
        assertFalse(validateRoutine("T", "DAILY").hasErrors)
    }

    @Test
    fun normalizeRoutineKind_defaultsToOther() {
        assertTrue(normalizeRoutineKind("medication") == "MEDICATION")
        assertTrue(normalizeRoutineKind("gym") == "OTHER")
    }
}
