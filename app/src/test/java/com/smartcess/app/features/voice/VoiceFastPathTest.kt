package com.smartcess.app.features.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Deterministic fast path: crisp commands route + parse locally (no AI),
 * fuzzy text returns null (normal Spark path). Clock fixed to Sunday,
 * 2026-11-01 10:00 UTC for stable expectations.
 */
class VoiceFastPathTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-01T10:00:00Z").toEpochMilli()

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun parse(intent: String, vararg turns: String): FastSlots? =
        VoiceFastPath.parseCreate(intent, turns.toList(), now, zone)

    // Routing

    @Test
    fun route_commands_hit() {
        assertEquals("add_appointment", VoiceFastPath.route("Add appointment TEST VOICE Dentist tomorrow at 9 AM"))
        assertEquals("add_routine", VoiceFastPath.route("add routine vitamins every day at 8am"))
        assertEquals("create_reminder", VoiceFastPath.route("remind me to call mom tomorrow at 9"))
        assertEquals("cancel_reminder", VoiceFastPath.route("cancel my call mom reminder"))
        assertEquals("cancel_reminder", VoiceFastPath.route("stop reminding me"))
        assertEquals("archive_bill", VoiceFastPath.route("archive my electric bill"))
        assertEquals("ask_bill_question", VoiceFastPath.route("how much is my electric bill"))
        assertEquals("ask_bill_question", VoiceFastPath.route("when is my bill due"))
        assertEquals("daily_briefing", VoiceFastPath.route("brief me"))
        assertEquals("daily_briefing", VoiceFastPath.route("what needs my attention"))
        assertEquals("daily_briefing", VoiceFastPath.route("what do I have today"))
    }

    @Test
    fun route_fuzzyOrNegated_miss() {
        assertNull(VoiceFastPath.route("hello"))
        assertNull(VoiceFastPath.route("my appointment tomorrow")) // no command verb
        assertNull(VoiceFastPath.route("don't remind me tomorrow"))
        assertNull(VoiceFastPath.route("never remind me again"))
        assertNull(VoiceFastPath.route("my bills"))
        assertNull(VoiceFastPath.route("dentist tomorrow"))
    }

    @Test
    fun route_m3Commands_hit() {
        assertEquals("email_briefing", VoiceFastPath.route("check my email"))
        assertEquals("email_briefing", VoiceFastPath.route("brief me on my email"))
        assertEquals("email_briefing", VoiceFastPath.route("read my email"))
        assertEquals("read_document", VoiceFastPath.route("read my document"))
        assertEquals("read_document", VoiceFastPath.route("summarize my document"))
        assertEquals("read_document", VoiceFastPath.route("what does my lease say"))
        assertEquals("read_document", VoiceFastPath.route("show my papers"))
        assertEquals("scan_bill", VoiceFastPath.route("scan this"))
        assertEquals("scan_bill", VoiceFastPath.route("scan my bill"))
        assertEquals("guide_test", VoiceFastPath.route("guidance test"))
        assertEquals("guide_test", VoiceFastPath.route("test the camera"))
        assertEquals("guide_test", VoiceFastPath.route("camera test"))
        assertEquals("complete_routine", VoiceFastPath.route("mark vitamins done"))
        assertEquals("complete_routine", VoiceFastPath.route("complete my medication routine"))
    }

    @Test
    fun route_m3Guards_preferSpecific() {
        // "complete a new routine" creates; bill questions beat doc reads.
        assertEquals("add_routine", VoiceFastPath.route("complete a new routine"))
        assertEquals("ask_bill_question", VoiceFastPath.route("what does my electric bill say"))
        assertEquals("ask_bill_question", VoiceFastPath.route("how much is my bill"))
        assertNull(VoiceFastPath.route("don't mark vitamins done"))
        assertNull(VoiceFastPath.route("summarize my bill")) // fuzzy: AI decides
        assertNull(VoiceFastPath.route("read my bill")) // fuzzy: AI decides
    }

    // Datetimes

    @Test
    fun parse_relativeDayAndTime() {
        val slots = parse("add_appointment", "add appointment dentist tomorrow at 9am")!!
        assertEquals("dentist", slots.title)
        assertEquals(millis(2026, 11, 2, 9, 0), slots.triggerMillis)
    }

    @Test
    fun parse_approvedReminderScript() {
        val slots = parse("create_reminder", "Remind me TEST VOICE Reminder tomorrow at 9 AM")!!
        assertEquals("TEST VOICE Reminder", slots.title)
        assertEquals(millis(2026, 11, 2, 9, 0), slots.triggerMillis)
    }

    @Test
    fun parse_weekdayAndMonthDates() {
        assertEquals(
            millis(2026, 11, 6, 14, 0),
            parse("add_appointment", "add appointment dentist friday at 2pm")!!.triggerMillis,
        )
        assertEquals(
            millis(2026, 12, 5, 10, 0),
            parse("add_appointment", "add appointment dentist december 5 at 10")!!.triggerMillis,
        )
        assertEquals(
            millis(2026, 12, 5, 10, 0),
            parse("add_appointment", "add appointment dentist 5 dec at 10")!!.triggerMillis,
        )
    }

    @Test
    fun parse_bareTime_rollsToNearestFuture() {
        // 10:00 now: bare 9 goes to tonight's twin, bare 11 stays this morning.
        assertEquals(
            millis(2026, 11, 1, 21, 0),
            parse("create_reminder", "remind me at 9")!!.triggerMillis,
        )
        assertEquals(
            millis(2026, 11, 1, 11, 0),
            parse("create_reminder", "remind me at 11")!!.triggerMillis,
        )
    }

    @Test
    fun parse_timeWords() {
        assertEquals(
            millis(2026, 11, 1, 20, 0),
            parse("create_reminder", "remind me at eight o clock")!!.triggerMillis,
        )
        assertEquals(
            millis(2026, 11, 1, 14, 30),
            parse("create_reminder", "remind me at half past 2")!!.triggerMillis,
        )
        assertEquals(
            millis(2026, 11, 1, 12, 0),
            parse("create_reminder", "remind me at noon")!!.triggerMillis,
        )
    }

    @Test
    fun parse_offsetResolvesAbsolutely() {
        assertEquals(
            millis(2026, 11, 1, 12, 0),
            parse("create_reminder", "remind me in 2 hours")!!.triggerMillis,
        )
    }

    @Test
    fun parse_explicitlyPast_returnsNullForAi() {
        assertNull(parse("add_appointment", "add appointment dentist yesterday at 9"))
        assertNull(parse("add_appointment", "add appointment dentist last friday at 9"))
    }

    @Test
    fun parse_pillCount_neverBecomesTime() {
        val slots = parse("create_reminder", "remind me take 2 pills")!!
        assertNull(slots.triggerMillis)
        assertEquals("take 2 pills", slots.title)
    }

    // Titles

    @Test
    fun parse_titles_stripCommandsKeepCase() {
        assertEquals(
            "Book club",
            parse("add_appointment", "add appointment Book club friday")!!.title,
        )
        assertEquals(
            "call mom",
            parse("create_reminder", "remind me to call mom tomorrow at 9")!!.title,
        )
        assertEquals(
            "medication",
            parse("add_routine", "add my medication routine")!!.title,
        )
        assertEquals(
            "dentist",
            parse("add_appointment", "add an appointment with dentist")!!.title,
        )
    }

    @Test
    fun parse_bareCommand_asksWithoutAi() {
        val slots = parse("create_reminder", "remind me")!!
        assertNull(slots.title)
        assertNull(slots.triggerMillis)
    }

    @Test
    fun parse_multiTurn_mergesSlots() {
        val slots = parse("add_appointment", "add appointment dentist", "tomorrow at 9")!!
        assertEquals("dentist", slots.title)
        assertEquals(millis(2026, 11, 2, 9, 0), slots.triggerMillis)
    }

    // Routines

    @Test
    fun parse_routine_dailyTimeAndKind() {
        val slots = parse("add_routine", "add routine vitamins every day at 8am")!!
        assertEquals("vitamins", slots.title)
        assertEquals("DAILY 08:00", slots.routineRule)
        assertEquals("MEDICATION", slots.kind)
    }

    @Test
    fun parse_routine_nonDaily_sentinelBlocks() {
        val slots = parse("add_routine", "add routine stretch every Monday at 8am")!!
        assertEquals("WEEKLY", slots.routineRule)
    }

    @Test
    fun parse_routine_noTime_asks() {
        val slots = parse("add_routine", "add my medication routine")!!
        assertEquals("medication", slots.title)
        assertNull(slots.triggerMillis)
        assertEquals("DAILY", slots.routineRule)
    }
}
