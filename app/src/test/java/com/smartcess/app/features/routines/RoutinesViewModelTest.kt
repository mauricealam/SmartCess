package com.smartcess.app.features.routines

import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.work.ReminderScheduler
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.data.repository.ReminderRepository
import com.smartcess.app.data.repository.RoutineRepository
import com.smartcess.app.data.repository.SaveOutcome
import com.smartcess.app.data.repository.ValidationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Manual routine save must refuse non-daily rules instead of silently
 * scheduling them daily; daily rules save and schedule normally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutinesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        routines: RoutineRepository,
        reminders: ReminderRepository = mockk(relaxed = true),
        scheduler: ReminderScheduler = mockk(relaxed = true),
        prefs: PreferencesRepository = mockk(),
    ) = RoutinesViewModel(routines, mockk<SparkClient>(), reminders, scheduler, prefs)

    @Test
    fun confirmSave_weeklyRule_showsErrorAndSavesNothing() = runTest(testDispatcher) {
        val routines = mockk<RoutineRepository>()
        every { routines.observeAll() } returns MutableStateFlow(emptyList<RoutineEntity>())
        val vm = viewModel(routines = routines)
        vm.onForm(RoutineFormState(title = "Gym", scheduleRule = "WEEKLY MON 09:00"))

        vm.confirmSave {}
        advanceUntilIdle()

        assertNotNull(vm.form.value.fieldErrorRes)
        coVerify(exactly = 0) { routines.save(any()) }
    }

    @Test
    fun confirmSave_dailyRule_savesAndSchedules() = runTest(testDispatcher) {
        val routines = mockk<RoutineRepository>()
        val prefs = mockk<PreferencesRepository>()
        every { routines.observeAll() } returns MutableStateFlow(emptyList<RoutineEntity>())
        coEvery { routines.save(any()) } returns
            SaveOutcome(7L, ValidationResult(emptyList()))
        coEvery { prefs.get() } returns UserPreferencesEntity()
        val vm = viewModel(routines = routines, prefs = prefs)
        vm.onForm(RoutineFormState(title = "Vitamins", scheduleRule = "DAILY 08:00"))

        var savedId = -1L
        vm.confirmSave { savedId = it }
        advanceUntilIdle()

        assertNull(vm.form.value.fieldErrorRes)
        assertEquals(7L, savedId)
        coVerify(exactly = 1) { routines.save(any()) }
    }
}
