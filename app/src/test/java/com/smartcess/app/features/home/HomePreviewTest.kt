package com.smartcess.app.features.home

import com.smartcess.app.R
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.repository.TodayData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Home preview mapping: ranking, max-3 cap, overflow count, empty. */
class HomePreviewTest {

    private fun bill(provider: String, dueDate: String = "2026-11-05") = BillEntity(
        provider = provider,
        amount = 100.0,
        currency = "MYR",
        dueDate = dueDate,
        confidence = 0.9,
        summary = "test",
    )

    private fun emptyData() = TodayData(
        overdueBills = emptyList(),
        billsDueSoon = emptyList(),
        appointments = emptyList(),
        urgentEmails = emptyList(),
        documentsWithDeadlines = emptyList(),
        routinesDue = emptyList(),
    )

    @Test
    fun emptyData_rendersEmptyNotFailed() {
        val state = buildHomePreview(emptyData())
        assertTrue(state.items.isEmpty())
        assertEquals(0, state.extraCount)
        assertFalse(state.loading)
        assertFalse(state.loadFailed)
    }

    @Test
    fun overdueBills_rankFirstWithProvider() {
        val data = emptyData().copy(overdueBills = listOf(bill("Tenaga"), bill("Air")))
        val state = buildHomePreview(data)
        assertEquals(2, state.items.size)
        assertEquals(0, state.extraCount)
        assertEquals(R.string.home_today_overdue, state.items[0].textRes)
        assertEquals(listOf("Tenaga"), state.items[0].args)
    }

    @Test
    fun fiveItems_capsAtThreeWithOverflow() {
        val data = emptyData().copy(
            overdueBills = listOf(bill("A"), bill("B"), bill("C"), bill("D"), bill("E")),
        )
        val state = buildHomePreview(data)
        assertEquals(3, state.items.size)
        assertEquals(2, state.extraCount)
        assertFalse(state.loadFailed)
    }

    @Test
    fun dueSoonBill_carriesProviderAndDate() {
        val data = emptyData().copy(billsDueSoon = listOf(bill("Tenaga")))
        val state = buildHomePreview(data)
        assertEquals(1, state.items.size)
        assertEquals(R.string.home_today_due_soon, state.items[0].textRes)
        assertEquals("Tenaga", state.items[0].args[0])
        assertFalse(state.items[0].args[1].isBlank())
    }
}
