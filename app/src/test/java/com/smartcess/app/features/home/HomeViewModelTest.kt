package com.smartcess.app.features.home

import com.smartcess.app.core.session.BackendHealthMonitor
import com.smartcess.app.core.session.HealthState
import com.smartcess.app.core.session.SessionManager
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.repository.AppointmentRepository
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.BriefingRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.RoutineRepository
import com.smartcess.app.data.repository.TodayData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
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
import org.junit.Before
import org.junit.Test

/**
 * Home Today mapping + per-kind delete routing.
 * Rows must carry tappable identity (kind + id); delete routes to exactly the
 * owning repository and refreshes the list; email rows are open-only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var briefing: BriefingRepository
    private lateinit var sessions: SessionManager
    private lateinit var monitor: BackendHealthMonitor
    private lateinit var bills: BillRepository
    private lateinit var appointments: AppointmentRepository
    private lateinit var routines: RoutineRepository
    private lateinit var documents: DocumentRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        briefing = mockk()
        sessions = mockk()
        monitor = mockk()
        bills = mockk()
        appointments = mockk()
        routines = mockk()
        documents = mockk()
        every { monitor.state } returns MutableStateFlow(HealthState.READY)
        every { sessions.isSignedIn() } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HomeViewModel(
        briefing, sessions, monitor, bills, appointments, routines, documents,
    )

    private fun emptyToday() = TodayData(
        overdueBills = emptyList(), billsDueSoon = emptyList(),
        appointments = emptyList(), urgentEmails = emptyList(),
        documentsWithDeadlines = emptyList(), routinesDue = emptyList(),
    )

    private fun bill(id: Long, provider: String, dueDate: String = "2026-12-01") =
        BillEntity(
            id = id, provider = provider, amount = 45.0, currency = "USD",
            dueDate = dueDate, confidence = 0.9, summary = "Bill.",
        )

    @Test
    fun previewAssignsKindAndRecordId() {
        val state = buildHomePreview(
            TodayData(
                overdueBills = listOf(bill(11, "City Electric")),
                billsDueSoon = emptyList(),
                appointments = listOf(
                    AppointmentEntity(id = 21, title = "Dentist", startMillis = 1L),
                ),
                urgentEmails = emptyList(),
                documentsWithDeadlines = emptyList(),
                routinesDue = listOf(
                    RoutineEntity(
                        id = 41, title = "Vitamins",
                        kind = RoutineEntity.KIND_MEDICATION, scheduleRule = "DAILY 08:00",
                    ),
                ),
            ),
        )

        val rows = state.items
        assertEquals(3, rows.size)
        assertEquals(0, state.extraCount)
        assertEquals(TodayItemKind.BILL, rows[0].kind)
        assertEquals(11L, rows[0].id)
        assertEquals(listOf("City Electric"), rows[0].args)
        assertEquals("City Electric", rows[0].displayName)
        assertEquals(TodayItemKind.APPOINTMENT, rows[1].kind)
        assertEquals(21L, rows[1].id)
        assertEquals("Dentist", rows[1].args.first())
        assertEquals(TodayItemKind.ROUTINE, rows[2].kind)
        assertEquals(41L, rows[2].id)
        assertEquals(listOf("Vitamins"), rows[2].args)
    }

    @Test
    fun previewEmailCarriesSubjectAndSender() {
        val state = buildHomePreview(
            emptyToday().copy(
                urgentEmails = listOf(
                    EmailItemEntity(
                        id = 31, sender = "Tenaga Nasional",
                        subject = "Your electricity bill is ready", body = "Body.",
                        receivedAt = 1L, urgency = "high",
                    ),
                ),
            ),
        )

        assertEquals(1, state.items.size)
        val row = state.items.first()
        assertEquals(TodayItemKind.EMAIL, row.kind)
        assertEquals(31L, row.id)
        assertEquals(listOf("Your electricity bill is ready", "Tenaga Nasional"), row.args)
    }

    @Test
    fun previewTruncatesAndCountsRemainder() {
        val state = buildHomePreview(
            TodayData(
                overdueBills = listOf(bill(11, "A"), bill(12, "B")),
                billsDueSoon = emptyList(),
                appointments = listOf(
                    AppointmentEntity(id = 21, title = "Dentist", startMillis = 1L),
                ),
                urgentEmails = listOf(
                    EmailItemEntity(
                        id = 31, sender = "S", subject = "Subj", body = "Body.",
                        receivedAt = 1L, urgency = "high",
                    ),
                ),
                documentsWithDeadlines = listOf(
                    DocumentEntity(
                        id = 51, title = "Lease", type = "contract",
                        summary = "Twelve month lease.", keyPoints = emptyList(),
                        deadline = "2026-12-31", confidence = 0.9,
                    ),
                ),
                routinesDue = emptyList(),
            ),
        )

        assertEquals(3, state.items.size)
        assertEquals(2, state.extraCount)
        // Mapping order is preserved for the visible rows.
        assertEquals(11L, state.items[0].id)
        assertEquals(12L, state.items[1].id)
        assertEquals(21L, state.items[2].id)
    }

    private fun item(kind: TodayItemKind, id: Long) =
        TodayPreviewItem(textRes = 0, args = listOf("Row"), kind = kind, id = id)

    @Test
    fun deleteBillRoutesToBillRepositoryAndRefreshes() = runTest(testDispatcher) {
        coEvery { briefing.getTodayData() } returns emptyToday()
        coEvery { bills.delete(any()) } returns Unit
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteItem(item(TodayItemKind.BILL, 7L))
        advanceUntilIdle()

        coVerify { bills.delete(7L) }
        coVerify(exactly = 2) { briefing.getTodayData() }
        confirmVerified(bills)
    }

    @Test
    fun deleteAppointmentRoutesToAppointmentRepositoryAndRefreshes() =
        runTest(testDispatcher) {
            coEvery { briefing.getTodayData() } returns emptyToday()
            coEvery { appointments.delete(any()) } returns Unit
            val vm = viewModel()
            advanceUntilIdle()

            vm.deleteItem(item(TodayItemKind.APPOINTMENT, 8L))
            advanceUntilIdle()

            coVerify { appointments.delete(8L) }
            coVerify(exactly = 2) { briefing.getTodayData() }
        }

    @Test
    fun deleteRoutineRoutesToRoutineRepositoryAndRefreshes() = runTest(testDispatcher) {
        coEvery { briefing.getTodayData() } returns emptyToday()
        coEvery { routines.delete(any()) } returns Unit
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteItem(item(TodayItemKind.ROUTINE, 9L))
        advanceUntilIdle()

        coVerify { routines.delete(9L) }
        coVerify(exactly = 2) { briefing.getTodayData() }
    }

    @Test
    fun deleteDocumentRoutesToDocumentRepositoryAndRefreshes() = runTest(testDispatcher) {
        coEvery { briefing.getTodayData() } returns emptyToday()
        coEvery { documents.delete(any()) } returns Unit
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteItem(item(TodayItemKind.DOCUMENT, 10L))
        advanceUntilIdle()

        coVerify { documents.delete(10L) }
        coVerify(exactly = 2) { briefing.getTodayData() }
    }

    @Test
    fun emailRowIsOpenOnlyAndDeletesNothing() = runTest(testDispatcher) {
        coEvery { briefing.getTodayData() } returns emptyToday()
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteItem(item(TodayItemKind.EMAIL, 31L))
        advanceUntilIdle()

        // No repository write and no refresh after the init load.
        coVerify(exactly = 1) { briefing.getTodayData() }
        confirmVerified(bills, appointments, routines, documents)
    }
}
