package com.smartcess.app.features.appointments

import android.content.Context
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.repository.AppointmentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting an appointment must also remove its system-calendar event, or a
 * ghost event survives in the user's calendar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appointments: AppointmentRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appointments = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppointmentDetailViewModel(
        appointments, mockk<ReminderScheduler>(relaxed = true), mockk<Context>(),
    )

    private fun appt(eventId: Long?) = AppointmentEntity(
        id = 21, title = "Dentist", startMillis = 1L, calendarEventId = eventId,
    )

    @Test
    fun delete_withCalendarEvent_removesItThenDeletesRow() = runTest(testDispatcher) {
        coEvery { appointments.getById(any()) } returns appt(eventId = 42L)
        coEvery { appointments.delete(any()) } returns Unit
        val vm = viewModel()
        val removed = mutableListOf<Long>()
        vm.deleteCalendarEvent = { removed.add(it) }
        vm.load(21L)
        advanceUntilIdle()

        var done = false
        vm.delete { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertEquals(listOf(42L), removed)
        coVerify { appointments.delete(21L) }
    }

    @Test
    fun delete_withoutCalendarEvent_justDeletesRow() = runTest(testDispatcher) {
        coEvery { appointments.getById(any()) } returns appt(eventId = null)
        coEvery { appointments.delete(any()) } returns Unit
        val vm = viewModel()
        var hookCalls = 0
        vm.deleteCalendarEvent = { hookCalls++ }
        vm.load(21L)
        advanceUntilIdle()

        vm.delete {}
        advanceUntilIdle()

        assertEquals(0, hookCalls)
        coVerify { appointments.delete(21L) }
    }
}
