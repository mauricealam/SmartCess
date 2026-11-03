package com.smartcess.app.features.bills

import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.data.repository.ReminderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Archiving or discarding a bill must cancel its linked reminders, or the
 * user keeps getting reminded about a bill they put away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillResultViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bills: BillRepository
    private lateinit var reminders: ReminderRepository
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bills = mockk()
        reminders = mockk()
        scheduler = mockk(relaxed = true)
        coEvery { bills.getById(any()) } returns bill()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BillResultViewModel(
        bills, reminders, scheduler,
        mockk<PreferencesRepository>(), mockk<Speaker>(),
    )

    private fun bill() = BillEntity(
        id = 11, provider = "City Electric", amount = 45.0, currency = "USD",
        dueDate = "2026-12-01", confidence = 0.9, summary = "Bill.",
    )

    private fun billReminder() = ReminderEntity(
        id = 5, kind = ReminderEntity.KIND_BILL, title = "City Electric",
        triggerAtMillis = System.currentTimeMillis() + 86_400_000L,
    )

    @Test
    fun confirmArchive_cancelsLinkedReminders() = runTest(testDispatcher) {
        coEvery { reminders.scheduledFor(any(), any()) } returns listOf(billReminder())
        coEvery { bills.archive(any()) } returns Unit
        val vm = viewModel()
        vm.load(11L)
        advanceUntilIdle()

        var done = false
        vm.confirmArchive { done = true }
        advanceUntilIdle()

        assertTrue(done)
        coVerify { reminders.scheduledFor(ReminderEntity.KIND_BILL, 11L) }
        coVerify { scheduler.cancel(5L) }
        coVerify { bills.archive(11L) }
    }

    @Test
    fun confirmDiscard_cancelsLinkedReminders() = runTest(testDispatcher) {
        coEvery { reminders.scheduledFor(any(), any()) } returns listOf(billReminder())
        coEvery { bills.delete(any()) } returns Unit
        val vm = viewModel()
        vm.load(11L)
        advanceUntilIdle()

        var done = false
        vm.confirmDiscard { done = true }
        advanceUntilIdle()

        assertTrue(done)
        coVerify { scheduler.cancel(5L) }
        coVerify { bills.delete(11L) }
    }
}
