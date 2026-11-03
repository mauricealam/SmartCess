package com.smartcess.app.features.briefing

import com.smartcess.app.core.ai.BriefingResult
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.repository.BriefingRepository
import com.smartcess.app.data.repository.TodayData
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The briefing list must never wait on the AI summary: saved records render
 * first, the AI text streams in after (cold backends take 15s+, which users
 * read as a broken "See all" button).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var briefing: BriefingRepository
    private lateinit var spark: SparkClient
    private lateinit var speaker: Speaker

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        briefing = mockk()
        spark = mockk()
        speaker = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun todayWithOneBill() = TodayData(
        overdueBills = listOf(
            BillEntity(
                id = 11, provider = "City Electric", amount = 45.0,
                currency = "USD", dueDate = "2026-12-01", confidence = 0.9,
                summary = "Monthly electric bill.",
            ),
        ),
        billsDueSoon = emptyList(), appointments = emptyList(),
        urgentEmails = emptyList(), documentsWithDeadlines = emptyList(),
        routinesDue = emptyList(),
    )

    @Test
    fun savedRecordsRenderBeforeAiSummaryArrives() = runTest(testDispatcher) {
        coEvery { briefing.getTodayData() } returns todayWithOneBill()
        val gate = CompletableDeferred<BriefingResult>()
        coEvery { spark.dailyBriefing(any()) } coAnswers { gate.await() }

        val vm = BriefingViewModel(briefing, spark, speaker)
        advanceUntilIdle()

        // AI still pending, yet the list is already visible.
        val early = vm.state.value
        assertFalse(early.loading)
        assertEquals(1, early.overdueBills.size)
        assertEquals("City Electric", early.overdueBills.first().provider)
        assertNull(early.aiBriefing)

        gate.complete(BriefingResult(briefing = "AI summary."))
        advanceUntilIdle()

        val late = vm.state.value
        assertEquals("AI summary.", late.aiBriefing)
        assertEquals(1, late.overdueBills.size)
    }

    @Test
    fun aiFailureKeepsSavedRecordsVisible() = runTest(testDispatcher) {
        coEvery { briefing.getTodayData() } returns todayWithOneBill()
        val gate = CompletableDeferred<BriefingResult>()
        coEvery { spark.dailyBriefing(any()) } coAnswers { gate.await() }

        val vm = BriefingViewModel(briefing, spark, speaker)
        advanceUntilIdle()

        gate.completeExceptionally(OfflineModeException())
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(1, state.overdueBills.size)
        assertNull(state.aiBriefing)
        assertTrue(state.offline)
    }
}
