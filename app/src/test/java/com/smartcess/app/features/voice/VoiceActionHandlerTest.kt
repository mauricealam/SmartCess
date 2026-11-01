package com.smartcess.app.features.voice

import android.content.Context
import com.smartcess.app.R
import com.smartcess.app.core.ai.AppointmentExtraction
import com.smartcess.app.core.ai.ReminderExtraction
import com.smartcess.app.core.ai.RoutineHelp
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.repository.AppointmentRepository
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.BriefingRepository
import com.smartcess.app.data.repository.IssueSeverity
import com.smartcess.app.data.repository.ReminderRepository
import com.smartcess.app.data.repository.RoutineRepository
import com.smartcess.app.data.repository.TodayData
import kotlinx.coroutines.flow.flowOf
import com.smartcess.app.data.repository.SaveOutcome
import com.smartcess.app.data.repository.ValidationIssue
import com.smartcess.app.data.repository.ValidationResult
import com.smartcess.app.core.work.ReminderScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Milestone 1 voice actions, fully mocked (no database, no network, no TTS):
 * extract -> ask missing -> read back -> save only on explicit "yes",
 * through the same repositories the manual screens use.
 */
class VoiceActionHandlerTest {

    private lateinit var spark: SparkClient
    private lateinit var appointments: AppointmentRepository
    private lateinit var routines: RoutineRepository
    private lateinit var reminders: ReminderRepository
    private lateinit var scheduler: ReminderScheduler
    private lateinit var briefing: BriefingRepository
    private lateinit var bills: BillRepository
    private lateinit var ctx: Context
    private lateinit var handler: VoiceActionHandler

    private val ok = ValidationResult(emptyList())

    private fun appt(
        title: String = "Dentist checkup",
        startIso: String = "2026-11-05T12:00:00Z",
        location: String? = "City Clinic",
    ) = AppointmentExtraction(title = title, start_iso = startIso, location = location)

    private fun routine(
        title: String = "Vitamins",
        kind: String = "MEDICATION",
        rule: String = "DAILY 08:00",
    ) = RoutineHelp(title = title, kind = kind, schedule_rule = rule)

    @Before
    fun setUp() {
        spark = mockk()
        appointments = mockk()
        routines = mockk()
        reminders = mockk()
        scheduler = mockk()
        briefing = mockk()
        bills = mockk()
        ctx = mockk()
        // Format calls echo their args (vararg may arrive as one array);
        // literal strings collapse to "literal".
        every { ctx.getString(any(), any()) } answers {
            val raw = invocation.args[1]
            val arg = (raw as? Array<*>)?.joinToString("|") ?: raw.toString()
            "fmt[$arg]"
        }
        every { ctx.getString(any()) } returns "literal"
        handler = VoiceActionHandler(
            spark, appointments, routines, reminders, scheduler, briefing, bills, ctx,
        )
    }

    private fun bill(
        id: Long = 1L,
        provider: String = "City Electric",
        amount: Double = 45.0,
        dueDate: String = "2026-12-01",
    ) = BillEntity(
        id = id, provider = provider, amount = amount, currency = "USD",
        dueDate = dueDate, confidence = 0.9, summary = "Monthly electric bill.",
    )

    private fun scheduledReminder(
        id: Long = 3L,
        title: String = "Call mom",
        triggerAt: Long = System.currentTimeMillis() + 86_400_000L,
    ) = ReminderEntity(
        id = id, kind = ReminderEntity.KIND_GENERAL, title = title,
        triggerAtMillis = triggerAt,
    )

    private fun emptyToday() = TodayData(
        overdueBills = emptyList(), billsDueSoon = emptyList(),
        appointments = emptyList(), urgentEmails = emptyList(),
        documentsWithDeadlines = emptyList(), routinesDue = emptyList(),
    )

    @Test
    fun startAppointment_complete_readsBackWithoutSaving() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        val turn = handler.start("add_appointment", "dentist tomorrow")
        assertTrue(turn.speak.contains("Dentist checkup"))
        assertTrue(turn.speak.contains("November"))
        assertTrue(turn.speak.contains("City Clinic"))
        assertFalse(turn.saved)
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { appointments.save(any()) }
    }

    @Test
    fun missingTime_askedThenConfirmed_savesOnce() = runTest {
        coEvery { spark.extractAppointment(any()) } returnsMany listOf(
            appt(startIso = ""),
            appt(),
        )
        val asked = handler.start("add_appointment", "dentist")
        assertEquals("literal", asked.speak) // ask-when question
        val readBack = handler.followUp("tomorrow noon")!!
        assertTrue(readBack.speak.contains("Dentist checkup"))
        val apptSlot = slot<AppointmentEntity>()
        coEvery { appointments.save(capture(apptSlot)) } returns SaveOutcome(7L, ok)
        val saved = handler.followUp("yes")!!
        assertTrue(saved.saved)
        assertTrue(saved.speak.contains("November"))
        coVerify(exactly = 1) { appointments.save(any()) }
        assertEquals("Dentist checkup", apptSlot.captured.title)
        assertEquals(Instant.parse("2026-11-05T12:00:00Z").toEpochMilli(), apptSlot.captured.startMillis)
        assertEquals("City Clinic", apptSlot.captured.location)
        assertFalse(handler.hasPending)
    }

    @Test
    fun no_discardsWithoutSaving_strayYesCannotSave() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        handler.start("add_appointment", "dentist tomorrow")
        val cancelled = handler.followUp("no")!!
        assertEquals("literal", cancelled.speak)
        assertFalse(cancelled.saved)
        assertFalse(handler.hasPending)
        coVerify(exactly = 0) { appointments.save(any()) }
        // A "yes" with nothing pending falls through (caller re-classifies).
        assertNull(handler.followUp("yes"))
        coVerify(exactly = 0) { appointments.save(any()) }
    }

    @Test
    fun edit_invalidatesReadBack_producesNewSummary() = runTest {
        coEvery { spark.extractAppointment(any()) } returnsMany listOf(
            appt(),
            appt(startIso = "2026-11-06T12:00:00Z"),
        )
        handler.start("add_appointment", "dentist Thursday")
        val edited = handler.followUp("make it Friday")!!
        assertTrue(edited.speak.contains("6 November") || edited.speak.contains("Friday"))
        assertFalse(edited.saved)
        val apptSlot = slot<AppointmentEntity>()
        coEvery { appointments.save(capture(apptSlot)) } returns SaveOutcome(7L, ok)
        handler.followUp("yes")
        assertEquals(
            Instant.parse("2026-11-06T12:00:00Z").toEpochMilli(),
            apptSlot.captured.startMillis,
        )
    }

    @Test
    fun doubleYes_savesOnce() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        coEvery { appointments.save(any()) } returns SaveOutcome(7L, ok)
        handler.start("add_appointment", "dentist tomorrow")
        assertTrue(handler.followUp("yes")!!.saved)
        // Pending was cleared before the save: the repeat finds nothing.
        assertNull(handler.followUp("yes"))
        coVerify(exactly = 1) { appointments.save(any()) }
    }

    @Test
    fun yesWithMissingSlots_reasksWithoutSaving() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt(startIso = "")
        handler.start("add_appointment", "dentist")
        val turn = handler.followUp("yes")!!
        assertEquals("literal", turn.speak) // still asking for the time
        assertFalse(turn.saved)
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { appointments.save(any()) }
    }

    @Test
    fun correctionStartingWithNo_treatedAsEdit() = runTest {
        coEvery { spark.extractAppointment(any()) } returnsMany listOf(appt(startIso = ""), appt())
        handler.start("add_appointment", "dentist")
        // "no, tomorrow at noon" corrects the time; it must not cancel.
        val turn = handler.followUp("no, tomorrow at noon")!!
        assertTrue(turn.speak.contains("Dentist checkup"))
        assertTrue(handler.hasPending)
        coVerify(exactly = 2) { spark.extractAppointment(any()) }
    }

    @Test
    fun ambiguousReply_repromptsWithoutSaving() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        handler.start("add_appointment", "dentist tomorrow")
        val turn = handler.followUp("huh?")!!
        assertTrue(turn.speak.contains("Dentist checkup"))
        coVerify(exactly = 0) { appointments.save(any()) }
    }

    @Test
    fun startRoutine_complete_savesAndSchedules() = runTest {
        coEvery { spark.routineHelp(any()) } returns routine()
        val readBack = handler.start("add_routine", "vitamins daily 8am")
        assertTrue(readBack.speak.contains("Vitamins"))
        assertTrue(readBack.speak.contains("every day"))
        val routineSlot = slot<RoutineEntity>()
        coEvery { routines.save(capture(routineSlot)) } returns SaveOutcome(7L, ok)
        coEvery {
            reminders.create(kind = any(), title = any(), triggerAtMillis = any(), referenceId = any())
        } returns 11L
        coEvery { scheduler.schedule(any()) } returns Unit
        val before = System.currentTimeMillis()
        val saved = handler.followUp("yes")!!
        assertTrue(saved.saved)
        coVerify(exactly = 1) { routines.save(any()) }
        assertEquals("Vitamins", routineSlot.captured.title)
        assertEquals("MEDICATION", routineSlot.captured.kind)
        assertEquals("DAILY 08:00", routineSlot.captured.scheduleRule)
        val due = routineSlot.captured.nextDueMillis!!
        assertTrue(due > before && due < before + 25 * 3600_000L)
        coVerify(exactly = 1) {
            reminders.create(
                kind = ReminderEntity.KIND_ROUTINE,
                title = "Vitamins",
                triggerAtMillis = due,
                referenceId = 7L,
            )
        }
        coVerify(exactly = 1) { scheduler.schedule(11L) }
    }

    @Test
    fun routineSchedulingFailure_reportsSeparatelyAfterSave() = runTest {
        coEvery { spark.routineHelp(any()) } returns routine()
        coEvery { routines.save(any()) } returns SaveOutcome(7L, ok)
        coEvery {
            reminders.create(kind = any(), title = any(), triggerAtMillis = any(), referenceId = any())
        } returns 11L
        coEvery { scheduler.schedule(any()) } throws RuntimeException("work denied")
        handler.start("add_routine", "vitamins daily 8am")
        val saved = handler.followUp("yes")!!
        assertTrue(saved.saved) // the local save succeeded…
        assertTrue(saved.speak.contains("literal")) // …and the warning is spoken too
        coVerify(exactly = 1) { routines.save(any()) }
    }

    @Test
    fun saveValidationFailure_speaksFailed() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        coEvery { appointments.save(any()) } returns SaveOutcome(
            null,
            ValidationResult(listOf(ValidationIssue("title", "Required", IssueSeverity.ERROR))),
        )
        handler.start("add_appointment", "dentist tomorrow")
        val failed = handler.followUp("yes")!!
        assertFalse(failed.saved)
        assertEquals("literal", failed.speak)
    }

    @Test
    fun extractionFailure_speaksGenericError() = runTest {
        coEvery { spark.extractAppointment(any()) } throws RuntimeException("down")
        val turn = handler.start("add_appointment", "dentist tomorrow")
        assertEquals("literal", turn.speak)
        assertFalse(handler.hasPending)
    }

    @Test
    fun weeklyRoutine_blockedWithExplanation() = runTest {
        coEvery { spark.routineHelp(any()) } returns routine(rule = "WEEKLY MON 08:00")
        val turn = handler.start("add_routine", "stretch every Monday 8am")
        verify { ctx.getString(R.string.voice_action_routine_daily_only) }
        assertFalse(turn.saved)
        assertFalse(handler.hasPending)
        coVerify(exactly = 0) { routines.save(any()) }
        // Nothing pending: a stray "yes" cannot save.
        assertNull(handler.followUp("yes"))
    }

    @Test
    fun bareTimeRule_allowedWithoutDailyClaim() = runTest {
        coEvery { spark.routineHelp(any()) } returns routine(rule = "08:00")
        val turn = handler.start("add_routine", "stretch at 8")
        assertTrue(turn.speak.contains("8:00 AM"))
        assertFalse(turn.speak.contains("every day"))
        assertTrue(handler.hasPending)
    }

    @Test
    fun inventedTime_dropped_asksWhenInstead() = runTest {
        // The extractor copied the prompt example's 08:00 though no time was
        // said: the guard drops it and the app asks, never confirms it.
        coEvery { spark.routineHelp(any()) } returns routine(rule = "DAILY 08:00")
        val turn = handler.start("add_routine", "add my medication routine")
        assertEquals("literal", turn.speak) // ask-when question
        assertFalse(turn.saved)
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { routines.save(any()) }
    }

    @Test
    fun statedHalfHour_accepted_readsBack() = runTest {
        coEvery { spark.routineHelp(any()) } returns routine(rule = "DAILY 14:30")
        val turn = handler.start("add_routine", "vitamins every day at 2:30pm")
        assertTrue(turn.speak.contains("2:30 PM"))
        assertTrue(handler.hasPending)
    }

    @Test
    fun spokenNumberWord_acceptedAsStatedTime() = runTest {
        coEvery { spark.routineHelp(any()) } returns routine(rule = "DAILY 08:00")
        val turn = handler.start("add_routine", "take vitamins at eight o clock")
        assertTrue(turn.speak.contains("8:00 AM"))
        assertTrue(handler.hasPending)
    }

    @Test
    fun timeGivenOnFollowUp_completesTask() = runTest {
        coEvery { spark.routineHelp(any()) } returnsMany listOf(
            routine(rule = "DAILY 08:00"),
            routine(rule = "DAILY 09:00"),
        )
        val asked = handler.start("add_routine", "add my medication routine")
        assertEquals("literal", asked.speak)
        val readBack = handler.followUp("nine am")!!
        assertTrue(readBack.speak.contains("9:00 AM"))
        assertTrue(handler.hasPending)
    }

    @Test
    fun discard_clearsPendingWithoutSaving() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        handler.start("add_appointment", "dentist tomorrow")
        handler.discard()
        assertFalse(handler.hasPending)
        assertNull(handler.followUp("yes"))
        coVerify(exactly = 0) { appointments.save(any()) }
    }

    // Milestone 2

    @Test
    fun briefingEmpty_speaksNothingToDo() = runTest {
        coEvery { briefing.getTodayData() } returns emptyToday()
        val turn = handler.start("daily_briefing", "what is on today")
        assertEquals("literal", turn.speak)
        assertFalse(turn.saved)
        assertFalse(handler.hasPending)
        coVerify(exactly = 0) { spark.dailyBriefing(any()) }
    }

    @Test
    fun briefingWithData_speaksNamedRecordsWithoutAi() = runTest {
        coEvery { briefing.getTodayData() } returns emptyToday().copy(
            overdueBills = listOf(bill()),
            appointments = listOf(
                AppointmentEntity(title = "Dentist", startMillis = System.currentTimeMillis() + 3_600_000L),
            ),
        )
        val turn = handler.start("daily_briefing", "brief me")
        assertTrue(turn.speak.startsWith("Good "))
        assertTrue(turn.speak.contains("City Electric"))
        assertTrue(turn.speak.contains("45 dollars"))
        assertTrue(turn.speak.contains("1 December 2026"))
        assertTrue(turn.speak.contains("Dentist"))
        assertFalse(handler.hasPending)
        coVerify(exactly = 0) { spark.dailyBriefing(any()) }
    }

    @Test
    fun briefing_capsLongLists() = runTest {
        val bills = (1..5).map { bill(id = it.toLong(), provider = "Provider $it") }
        coEvery { briefing.getTodayData() } returns emptyToday().copy(overdueBills = bills)
        val turn = handler.start("daily_briefing", "brief me")
        assertTrue(turn.speak.contains("Provider 3"))
        assertFalse(turn.speak.contains("Provider 4"))
        assertTrue(turn.speak.contains("and 2 more."))
    }

    @Test
    fun briefing_greeting_matchesTimeOfDay() {
        val data = emptyToday().copy(overdueBills = listOf(bill()))
        val zone = ZoneId.systemDefault()
        fun at(hour: Int) = LocalDateTime.now().withHour(hour).withMinute(0)
            .atZone(zone).toInstant().toEpochMilli()
        assertTrue(handler.composeBriefing(data, at(9)).startsWith("Good morning."))
        assertTrue(handler.composeBriefing(data, at(15)).startsWith("Good afternoon."))
        assertTrue(handler.composeBriefing(data, at(21)).startsWith("Good evening."))
    }

    @Test
    fun billQuestion_noBills_speaksNone() = runTest {
        every { bills.observeActive() } returns flowOf(emptyList())
        val turn = handler.start("ask_bill_question", "how much do I owe")
        assertEquals("literal", turn.speak)
        assertFalse(handler.hasPending)
    }

    @Test
    fun billQuestion_singleMatch_answersFromSavedFields() = runTest {
        every { bills.observeActive() } returns flowOf(listOf(bill()))
        val turn = handler.start("ask_bill_question", "how much is my electric bill")
        assertTrue(turn.speak.contains("City Electric"))
        assertTrue(turn.speak.contains("45.0 USD"))
        assertFalse(turn.saved)
        assertFalse(handler.hasPending)
    }

    @Test
    fun billQuestion_dueDate_speaksRealDate() = runTest {
        every { bills.observeActive() } returns flowOf(listOf(bill()))
        val turn = handler.start("ask_bill_question", "when is my electric bill due")
        assertTrue(turn.speak.contains("1 December 2026"))
        assertFalse(turn.speak.contains("2026-12-01"))
    }

    @Test
    fun billQuestion_multiMatch_asksThenAnswersSelection() = runTest {
        every { bills.observeActive() } returns flowOf(
            listOf(bill(id = 1L, provider = "City Electric"), bill(id = 2L, provider = "City Water")),
        )
        val asked = handler.start("ask_bill_question", "how much is the bill")
        assertTrue(asked.speak.contains("1. City Electric"))
        assertTrue(asked.speak.contains("2. City Water"))
        assertTrue(handler.hasPending)
        val answered = handler.followUp("2")!!
        assertTrue(answered.speak.contains("City Water"))
        assertFalse(handler.hasPending)
    }

    @Test
    fun billQuestion_followUp_reusesBillFromHistory() = runTest {
        // "When is it due?" names no provider; the prior turn's history
        // carries the bill, so the user never repeats it.
        every { bills.observeActive() } returns flowOf(
            listOf(bill(id = 1L, provider = "City Electric"), bill(id = 2L, provider = "City Water")),
        )
        val turn = handler.start(
            "ask_bill_question",
            "when is it due",
            "USER: how much is my electric bill",
        )
        assertTrue(turn.speak.contains("City Electric"))
        assertTrue(turn.speak.contains("1 December 2026"))
        assertFalse(handler.hasPending)
    }

    @Test
    fun billQuestion_bareYes_reasksWithoutAnswering() = runTest {
        every { bills.observeActive() } returns flowOf(
            listOf(bill(id = 1L, provider = "City Electric"), bill(id = 2L, provider = "City Water")),
        )
        handler.start("ask_bill_question", "how much is the bill")
        val reasked = handler.followUp("yes")!!
        assertTrue(reasked.speak.contains("1. City Electric"))
        assertTrue(handler.hasPending)
    }

    @Test
    fun reminder_complete_readsBackAndSaves() = runTest {
        // Fuzzy phrasing (no command verb) flows through AI extraction.
        coEvery { spark.extractReminder(any()) } returns
            ReminderExtraction(title = "Call mom", trigger_iso = "2030-05-06 09:00")
        val readBack = handler.start("create_reminder", "call mom reminder tomorrow at 9")
        assertTrue(readBack.speak.contains("Call mom"))
        assertTrue(readBack.speak.contains("May"))
        assertTrue(readBack.speak.contains("9:00 AM"))
        val kindSlot = slot<String>()
        coEvery {
            reminders.create(
                kind = capture(kindSlot), title = any(),
                triggerAtMillis = any(), referenceId = any(),
            )
        } returns 11L
        coEvery { scheduler.schedule(any()) } returns Unit
        val saved = handler.followUp("yes")!!
        assertTrue(saved.saved)
        assertEquals(ReminderEntity.KIND_GENERAL, kindSlot.captured)
        coVerify(exactly = 1) { scheduler.schedule(11L) }
        assertFalse(handler.hasPending)
    }

    @Test
    fun reminder_missingWhen_asksThenCompletes() = runTest {
        coEvery { spark.extractReminder(any()) } returnsMany listOf(
            ReminderExtraction(title = "Call mom", trigger_iso = ""),
            ReminderExtraction(title = "Call mom", trigger_iso = "2030-05-06 09:00"),
        )
        val asked = handler.start("create_reminder", "remind me to call mom")
        assertEquals("literal", asked.speak)
        val readBack = handler.followUp("tomorrow at 9")!!
        assertTrue(readBack.speak.contains("9:00 AM"))
    }

    @Test
    fun reminder_pastTrigger_reasks() = runTest {
        coEvery { spark.extractReminder(any()) } returns
            ReminderExtraction(title = "Call mom", trigger_iso = "2020-01-01 09:00")
        val turn = handler.start("create_reminder", "remind me yesterday at 9")
        verify { ctx.getString(R.string.voice_reminder_past) }
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { reminders.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun cancelReminder_none_speaksNone() = runTest {
        coEvery { reminders.scheduled() } returns emptyList()
        val turn = handler.start("cancel_reminder", "cancel my reminder")
        assertEquals("literal", turn.speak)
        assertFalse(handler.hasPending)
    }

    @Test
    fun cancelReminder_singleMatch_confirmsAndCancels() = runTest {
        coEvery { reminders.scheduled() } returns listOf(scheduledReminder())
        coEvery { scheduler.cancel(any()) } returns Unit
        val confirm = handler.start("cancel_reminder", "cancel my call mom reminder")
        assertTrue(confirm.speak.contains("Call mom"))
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { scheduler.cancel(any()) }
        val done = handler.followUp("yes")!!
        assertTrue(done.saved)
        assertEquals("literal", done.speak)
        coVerify(exactly = 1) { scheduler.cancel(3L) }
        assertFalse(handler.hasPending)
    }

    @Test
    fun cancelReminder_multiMatch_selectByOrdinal_thenYes() = runTest {
        coEvery { reminders.scheduled() } returns listOf(
            scheduledReminder(id = 3L, title = "Call mom"),
            scheduledReminder(id = 4L, title = "Pay rent"),
        )
        coEvery { scheduler.cancel(any()) } returns Unit
        val asked = handler.start("cancel_reminder", "cancel reminder")
        assertTrue(asked.speak.contains("1. Call mom"))
        assertTrue(asked.speak.contains("2. Pay rent"))
        val confirm = handler.followUp("the second one")!!
        assertTrue(confirm.speak.contains("Pay rent"))
        handler.followUp("yes")
        coVerify(exactly = 1) { scheduler.cancel(4L) }
        coVerify(exactly = 0) { scheduler.cancel(3L) }
    }

    @Test
    fun cancelReminder_no_keepsIt() = runTest {
        coEvery { reminders.scheduled() } returns listOf(scheduledReminder())
        val confirm = handler.start("cancel_reminder", "cancel my call mom reminder")
        assertTrue(confirm.speak.contains("Call mom"))
        val kept = handler.followUp("no")!!
        verify { ctx.getString(R.string.voice_keep) }
        assertFalse(kept.saved)
        assertFalse(handler.hasPending)
        coVerify(exactly = 0) { scheduler.cancel(any()) }
    }

    @Test
    fun archiveBill_singleMatch_confirmsAndArchives() = runTest {
        every { bills.observeActive() } returns flowOf(listOf(bill()))
        coEvery { bills.archive(any()) } returns Unit
        val confirm = handler.start("archive_bill", "archive my electric bill")
        assertTrue(confirm.speak.contains("City Electric"))
        coVerify(exactly = 0) { bills.archive(any()) }
        val done = handler.followUp("yes")!!
        assertTrue(done.saved)
        coVerify(exactly = 1) { bills.archive(1L) }
        assertFalse(handler.hasPending)
    }

    @Test
    fun fastPath_appointment_readsBackWithoutAi() = runTest {
        val turn = handler.start("add_appointment", "add appointment dentist tomorrow at 9")
        assertTrue(turn.speak.contains("dentist"))
        assertTrue(turn.speak.contains("9:00 AM"))
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { spark.extractAppointment(any()) }
    }

    @Test
    fun fastPath_followUp_completesWithoutAi() = runTest {
        val asked = handler.start("add_appointment", "add appointment dentist")
        assertEquals("literal", asked.speak) // ask-when, no AI call
        val readBack = handler.followUp("tomorrow at 9")!!
        assertTrue(readBack.speak.contains("dentist"))
        assertTrue(readBack.speak.contains("9:00 AM"))
        coVerify(exactly = 0) { spark.extractAppointment(any()) }
    }

    @Test
    fun fastPath_bareCommand_asksTitleWithoutAi() = runTest {
        val asked = handler.start("create_reminder", "remind me")
        assertEquals("literal", asked.speak)
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { spark.extractReminder(any()) }
    }

    @Test
    fun fastPath_routine_readsBackWithoutAi() = runTest {
        val turn = handler.start("add_routine", "add routine vitamins every day at 8am")
        assertTrue(turn.speak.contains("8:00 AM"))
        assertTrue(handler.hasPending)
        coVerify(exactly = 0) { spark.routineHelp(any()) }
    }

    @Test
    fun fastPath_pastAppointment_fallsBackToAi() = runTest {
        coEvery { spark.extractAppointment(any()) } returns appt()
        val turn = handler.start("add_appointment", "add appointment dentist yesterday at 9")
        assertTrue(turn.speak.contains("Dentist checkup"))
        coVerify(exactly = 1) { spark.extractAppointment(any()) }
    }

    @Test
    fun archiveBill_multiMatch_selectByName() = runTest {
        every { bills.observeActive() } returns flowOf(
            listOf(bill(id = 1L, provider = "City Electric"), bill(id = 2L, provider = "City Water")),
        )
        val asked = handler.start("archive_bill", "archive bill")
        assertTrue(asked.speak.contains("2. City Water"))
        val confirm = handler.followUp("water")!!
        assertTrue(confirm.speak.contains("City Water"))
        assertTrue(handler.hasPending)
    }
}
