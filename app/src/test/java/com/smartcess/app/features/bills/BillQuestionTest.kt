package com.smartcess.app.features.bills

import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.features.bills.BillResultViewModel.Companion.answerBillQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for on-device bill Q&A over saved fields (spec: local answers). */
class BillQuestionTest {

    private val bill = BillEntity(
        provider = "Tenaga",
        amount = 87.40,
        currency = "MYR",
        dueDate = "2026-11-15",
        accountReference = "ACC-1",
        periodStart = "2026-10-01",
        periodEnd = "2026-10-31",
        confidence = 0.9,
        summary = "October electricity bill.",
    )

    @Test
    fun dueQuestion_returnsDueDate() {
        assertEquals("2026-11-15", answerBillQuestion(bill, "When is it due?"))
    }

    @Test
    fun amountQuestion_returnsAmount() {
        assertEquals("87.4 MYR", answerBillQuestion(bill, "How much do I owe?"))
    }

    @Test
    fun providerQuestion_returnsProvider() {
        assertEquals("Tenaga", answerBillQuestion(bill, "Who is it from?"))
    }

    @Test
    fun missingAccount_returnsNull() {
        assertNull(answerBillQuestion(bill.copy(accountReference = null), "What is the account number?"))
    }

    @Test
    fun periodQuestion_returnsRange() {
        assertEquals("2026-10-01 – 2026-10-31", answerBillQuestion(bill, "Which billing period?"))
    }
}
