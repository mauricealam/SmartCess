package com.smartcess.app.features.voice

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Local slots parsed from command-shaped speech. Null fields mean "ask the user". */
data class FastSlots(
    val title: String?,
    val triggerMillis: Long?,
    /** Routine rule incl. the M1 daily-only sentinel ("WEEKLY" blocks with guidance). */
    val routineRule: String?,
    val kind: String?,
)

/**
 * Deterministic fast path in front of the AI: crisp commands ("remind me …",
 * "add appointment …", "brief me") route and parse on-device in milliseconds
 * instead of paying for classify + extract round trips. Anything fuzzy
 * returns null and flows through the normal Spark path, so turns only ever
 * get faster — never dumber. Pure and JVM-testable throughout.
 */
object VoiceFastPath {

    // ---- intent routing (command-shaped text only; fuzzy text -> null) ----

    private val CREATE_VERBS = "(add|book|schedule|create|make|set|new|start)"
    private val NEGATED_START = Regex("^(dont|do not|never)\\b")

    fun route(text: String): String? {
        val norm = " " + text.lowercase(Locale.US)
            .replace("’", "").replace("'", "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ") + " "
        val trimmed = norm.trim()
        if (NEGATED_START.containsMatchIn(trimmed)) return null
        fun has(pattern: String): Boolean = Regex(pattern).containsMatchIn(norm)
        val mentionsBill = has("\\bbills?\\b")
        val mentionsRemind = has("\\bremind\\w*\\b")
        // Destructive/specific first: they share words with the broad ones.
        if (has("\\barchiv\\w*\\b") && mentionsBill) return VoiceActionIntents.ARCHIVE_BILL
        if (has("\\b(cancel|cancelled|canceling|cancelling|stop|delete|remove|clear)\\b") && mentionsRemind) {
            return VoiceActionIntents.CANCEL_REMINDER
        }
        // "complete a new routine" creates; everything else marks done.
        if (
            Regex("^(mark|complete|completed|finish|finished)\\b").containsMatchIn(trimmed) &&
            !has("\\b(new|add|create)\\b")
        ) {
            return VoiceActionIntents.COMPLETE_ROUTINE
        }
        if (
            has("\\bremind me\\b") || trimmed.startsWith("reminder ") ||
            (has("\\b(add|create|set|make|new)\\b") && has("\\breminders?\\b"))
        ) {
            return VoiceActionIntents.CREATE_REMINDER
        }
        if (has("\\b$CREATE_VERBS\\b") && has("\\bappointment\\b")) {
            return VoiceActionIntents.ADD_APPOINTMENT
        }
        if (has("\\b$CREATE_VERBS\\b") && has("\\broutine\\b")) {
            return VoiceActionIntents.ADD_ROUTINE
        }
        if (
            has("\\bhow much\\b") ||
            (mentionsBill && has("\\b(what|when|how|much|due|amount|owe|pay|account|provider|summary)\\b")) ||
            (has("\\bdue\\b") && has("\\b(what|when|is|are)\\b"))
        ) {
            return VoiceActionIntents.ASK_BILL_QUESTION
        }
        // "scan this" captures (bill or paper land on the same camera flow).
        if (has("\\bscan\\b")) return VoiceActionIntents.SCAN_BILL
        val mentionsDoc = has("\\b(documents?|docs?|papers?|letters?|forms?|files?|scans?|receipts?)\\b")
        if (
            ((has("\\bread\\b") || has("\\bopen\\b") || has("\\bshow\\b")) && mentionsDoc) ||
            (mentionsDoc && has("\\b(summary|summarize|summarise|what)\\b")) ||
            (has("\\bwhat does\\b") && has("\\bsay\\b") && !mentionsBill)
        ) {
            return VoiceActionIntents.READ_DOCUMENT
        }
        if (
            has("\\b(emails?|inbox|mail)\\b") &&
            (has("\\b(brief|briefing|read|check|unread|new|summary)\\b") || has("\\bwhat\\b"))
        ) {
            return VoiceActionIntents.EMAIL_BRIEFING
        }
        if (
            has("\\bbrief(ing| me)?\\b") || has("what needs my attention") ||
            has("\\bwhat'?s on\\b") || has("\\bmy day\\b") || has("\\bagenda\\b") ||
            has("\\bwhat do i have\\b")
        ) {
            return VoiceActionIntents.DAILY_BRIEFING
        }
        return null
    }

    // ---- slot parsing (datetimes, titles, routine kinds) ----

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12,
    )
    private val TIME_WORDS = NUMBER_WORDS.keys.joinToString("|")
    private val MONTHS = Month.values().associateBy { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase() }
    private val MONTH_SHORT = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH,
        "apr" to Month.APRIL, "jun" to Month.JUNE, "jul" to Month.JULY,
        "aug" to Month.AUGUST, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )

    private data class TimeHit(val hour: Int, val minute: Int, val range: IntRange, val meridiem: String?)

    private fun findTime(norm: String): TimeHit? {
        fun match(regex: Regex, convert: (MatchResult) -> TimeHit?): TimeHit? {
            val m = regex.find(norm) ?: return null
            return convert(m)
        }
        // 2:30pm / 14:30 (colon times are unambiguous).
        match(Regex("\\b(\\d{1,2}):(\\d{2})\\s*(am|pm|a ?m|p ?m)?\\b")) { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (minute > 59 || hour > 23) return@match null
            val raw = m.groupValues[3].filter { it == 'a' || it == 'p' }
            val meridiem = if (raw.isEmpty()) null else raw + "m"
            TimeHit(hour, minute, m.range, meridiem)
        }?.let { return it }
        // 9am / 9 AM / eight am.
        match(Regex("\\b(\\d{1,2}|$TIME_WORDS)\\s*(am|pm|a ?m|p ?m)\\b")) { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@match null
            if (hour > 23) return@match null
            TimeHit(hour, 0, m.range, m.groupValues[2].filter { it == 'a' || it == 'p' } + "m")
        }?.let { return it }
        // half past two (before o'clock: "half past two o'clock" is 2:30).
        match(Regex("\\bhalf past (\\d{1,2}|$TIME_WORDS)\\b")) { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@match null
            TimeHit(hour, 30, m.range, null)
        }?.let { return it }
        // eight o'clock / eight o clock (STT spacing varies).
        match(Regex("\\b($TIME_WORDS)\\s*o\\s*'?clock\\b")) { m ->
            TimeHit(NUMBER_WORDS[m.groupValues[1]]!!, 0, m.range, null)
        }?.let { return it }
        // 12 noon / noon / 12 midnight / midnight.
        match(Regex("\\b12\\s+noon\\b")) { m -> TimeHit(12, 0, m.range, "pm") }?.let { return it }
        match(Regex("\\bnoon\\b")) { m -> TimeHit(12, 0, m.range, "pm") }?.let { return it }
        match(Regex("\\b12\\s+midnight\\b")) { m -> TimeHit(0, 0, m.range, "am") }?.let { return it }
        match(Regex("\\bmidnight\\b")) { m -> TimeHit(0, 0, m.range, "am") }?.let { return it }
        // 0800 / 930 (HHMM runs).
        match(Regex("\\b(\\d{3,4})\\b")) { m ->
            val digits = m.groupValues[1]
            if (digits.length == 3 && digits[0] == '0') return@match null
            val split = digits.length - 2
            val hour = digits.substring(0, split).toInt()
            val minute = digits.substring(split).toInt()
            if (hour > 23 || minute > 59) return@match null
            TimeHit(hour, minute, m.range, null)
        }?.let { return it }
        // Bare hour: only with "at", a daypart, or as the whole utterance —
        // otherwise pill counts ("take 2 pills") become 2 o'clock.
        match(Regex("\\bat\\s+(\\d{1,2}|$TIME_WORDS)\\b")) { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@match null
            if (hour > 23) return@match null
            TimeHit(hour, 0, m.range, null)
        }?.let { return it }
        match(Regex("\\b(\\d{1,2}|$TIME_WORDS)\\s+(in the\\s+)?(morning|afternoon|evening|night)\\b")) { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@match null
            if (hour > 23) return@match null
            TimeHit(hour, 0, m.range, if (m.groupValues[3] == "morning") "am" else "pm")
        }?.let { return it }
        if (norm.trim().matches(Regex("(\\d{1,2}|$TIME_WORDS)"))) {
            val bare = norm.trim()
            val hour = bare.toIntOrNull() ?: NUMBER_WORDS[bare]!!
            return TimeHit(hour, 0, 0 until norm.length, null)
        }
        return null
    }

    private sealed interface DateHit {
        val range: IntRange
    }
    private data class DayOffset(val days: Int, override val range: IntRange) : DateHit
    private data class Weekday(val day: DayOfWeek, val plusWeeks: Int, override val range: IntRange) : DateHit
    private data class MonthDay(val month: Month, val day: Int, override val range: IntRange) : DateHit
    private data class IsoDate(val date: LocalDate, override val range: IntRange) : DateHit
    private data class Past(val reason: String, override val range: IntRange) : DateHit

    private fun findDate(norm: String): DateHit? {
        fun match(regex: Regex, convert: (MatchResult) -> DateHit?): DateHit? {
            val m = regex.find(norm) ?: return null
            return convert(m)
        }
        match(Regex("\\bday after tomorrow\\b")) { m -> DayOffset(2, m.range) }?.let { return it }
        match(Regex("\\btomorrow\\b")) { m -> DayOffset(1, m.range) }?.let { return it }
        match(Regex("\\btonight\\b")) { m -> DayOffset(0, m.range) }?.let { return it }
        match(Regex("\\btoday\\b")) { m -> DayOffset(0, m.range) }?.let { return it }
        match(Regex("\\byesterday\\b")) { m -> Past("yesterday", m.range) }?.let { return it }
        match(Regex("\\bnext week\\b")) { m -> DayOffset(7, m.range) }?.let { return it }
        match(Regex("\\bin (\\d{1,3})\\s+(days?|weeks?)\\b")) { m ->
            val n = m.groupValues[1].toInt()
            DayOffset(if (m.groupValues[2].startsWith("day")) n else n * 7, m.range)
        }?.let { return it }
        // Weekdays, with next/this/last modifiers.
        val days = DayOfWeek.values().joinToString("|") { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase() }
        match(Regex("\\b(next|this|last)\\s+($days)\\b")) { m ->
            val day = DayOfWeek.valueOf(m.groupValues[2].uppercase())
            when (m.groupValues[1]) {
                "last" -> Past("last ${m.groupValues[2]}", m.range)
                "next" -> Weekday(day, 1, m.range)
                else -> Weekday(day, 0, m.range)
            }
        }?.let { return it }
        match(Regex("\\b($days)\\b")) { m ->
            Weekday(DayOfWeek.valueOf(m.groupValues[1].uppercase()), 0, m.range)
        }?.let { return it }
        // December 5 / Dec 5 / 5 December / 5th of December.
        val monthNames = (MONTHS.keys + MONTH_SHORT.keys).joinToString("|")
        fun monthOf(name: String): Month = MONTHS[name] ?: MONTH_SHORT[name]!!
        match(Regex("\\b($monthNames)\\s+(\\d{1,2})(st|nd|rd|th)?\\b")) { m ->
            MonthDay(monthOf(m.groupValues[1]), m.groupValues[2].toInt(), m.range)
        }?.let { return it }
        match(Regex("\\b(\\d{1,2})(st|nd|rd|th)?\\s+(of\\s+)?($monthNames)\\b")) { m ->
            MonthDay(monthOf(m.groupValues[4]), m.groupValues[1].toInt(), m.range)
        }?.let { return it }
        match(Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")) { m ->
            val date = runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull() ?: return@match null
            IsoDate(date, m.range)
        }?.let { return it }
        return null
    }

    private fun findOffsetMinutes(norm: String): Pair<Long, IntRange>? {
        val m = Regex("\\bin (\\d{1,3})\\s+(minutes?|hours?)\\b").find(norm) ?: return null
        val n = m.groupValues[1].toInt()
        val minutes = if (m.groupValues[2].startsWith("minute")) n.toLong() else n * 60L
        return minutes to m.range
    }

    private fun daypartHint(norm: String): String? = when {
        Regex("\\bmorning\\b").containsMatchIn(norm) -> "am"
        Regex("\\b(afternoon|evening|night|tonight)\\b").containsMatchIn(norm) -> "pm"
        else -> null
    }

    private fun resolveHour(hour: Int, meridiem: String?): Int {
        if (hour > 12) return hour
        return when (meridiem) {
            "am" -> if (hour == 12) 0 else hour
            "pm" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
    }

    /**
     * Parses create-intent slots from this task's turns (never older history).
     * Null fields mean "ask"; a null return means "too fuzzy/past — let the
     * AI try".
     */
    fun parseCreate(
        intent: String,
        turns: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): FastSlots? {
        val joined = turns.joinToString("\n")
        // Match on lowercase (length-preserving for this input), strip spans
        // from the original so the title keeps its casing.
        val norm = joined.lowercase(Locale.US)
        val consumed = mutableListOf<IntRange>()

        // Relative offsets resolve absolutely ("in 2 hours").
        findOffsetMinutes(norm)?.let { (minutes, range) ->
            consumed.add(range)
            val at = Instant.ofEpochMilli(nowMillis).atZone(zone)
                .plusMinutes(minutes).withSecond(0).withNano(0)
            return slotsFor(intent, joined, consumed, at.toInstant().toEpochMilli(), zone)
        }

        val time = findTime(norm)
        val date = findDate(norm)
        if (date is Past) return null // explicitly past: AI keeps today's behavior
        time?.let { consumed.add(it.range) }
        date?.let { consumed.add(it.range) }

        val trigger = combine(date, time, norm, nowMillis, zone)
            ?: return slotsFor(intent, joined, consumed, null, zone)
        // Explicitly-dated past: reminders re-ask via presentReminder; the AI
        // keeps today's behavior for appointments.
        if (trigger.second) {
            if (intent == VoiceActionIntents.CREATE_REMINDER) {
                return slotsFor(intent, joined, consumed, trigger.first, zone)
            }
            return null
        }
        return slotsFor(intent, joined, consumed, trigger.first, zone)
    }

    /**
     * Combines date + time into millis. Returns null when no usable time, or
     * (millis, past=true) when an explicitly stated date+time is in the past.
     */
    private fun combine(
        date: DateHit?,
        time: TimeHit?,
        norm: String,
        nowMillis: Long,
        zone: ZoneId,
    ): Pair<Long, Boolean>? {
        if (time == null) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val meridiem = time.meridiem ?: daypartHint(norm)
        val hour = resolveHour(time.hour, meridiem)
        val minute = time.minute
        if (hour > 23 || minute > 59) return null
        val today = now.toLocalDate()
        val target: LocalDate = when (date) {
            null -> today
            is DayOffset -> today.plusDays(date.days.toLong())
            is Weekday -> {
                var d = today
                while (d.dayOfWeek != date.day) d = d.plusDays(1)
                d.plusWeeks(date.plusWeeks.toLong())
            }
            is MonthDay -> {
                var d = runCatching { LocalDate.of(today.year, date.month, date.day) }.getOrNull()
                    ?: return null
                if (d.isBefore(today)) d = d.plusYears(1)
                d
            }
            is IsoDate -> date.date
            is Past -> return null // unreachable (caller filters), stay safe
        }
        var at = target.atTime(hour, minute).atZone(zone)
        if (date == null && !at.toInstant().isAfter(now.toInstant())) {
            // Bare time already passed today: nearest future occurrence —
            // tonight's 12-hour twin, else tomorrow.
            val twin = if (time.meridiem == null && time.hour in 1..11) {
                target.atTime(time.hour + 12, minute).atZone(zone)
            } else null
            at = if (twin != null && twin.toInstant().isAfter(now.toInstant())) twin
            else target.plusDays(1).atTime(hour, minute).atZone(zone)
        }
        if (date is Weekday && !at.toInstant().isAfter(now.toInstant())) {
            at = at.plusWeeks(1)
        }
        val millis = at.toInstant().toEpochMilli()
        val past = millis <= nowMillis
        if (past && date == null) return null // defensive; roll above prevents this
        return millis to past
    }

    // Title stripping: a title sandwiched between the verb and the noun
    // ("add my medication routine") is taken directly; otherwise command
    // phrases (article-tolerant) and nouns strip anywhere, fillers anywhere,
    // glue words at the edges. Single verbs are never stripped ("Book club"
    // survives) — the gap pattern covers split commands instead.
    private val GAP_NOUNS = mapOf(
        VoiceActionIntents.ADD_APPOINTMENT to "appointment",
        VoiceActionIntents.ADD_ROUTINE to "routine",
    )
    private val GAP_VERBS = "(add|book|schedule|create|make|set|new|start)"
    private val COMMAND_PHRASES = mapOf(
        VoiceActionIntents.ADD_APPOINTMENT to emptyList(),
        VoiceActionIntents.CREATE_REMINDER to listOf("remind me to", "remind me", "reminder to"),
        VoiceActionIntents.ADD_ROUTINE to listOf(
            "every day", "everyday", "daily",
            "every morning", "every afternoon", "every evening", "every night",
        ),
    )
    // Standalone nouns strip at the start only: "reminder call mom" cleans up,
    // but "TEST VOICE Reminder" keeps its name.
    private val LEAD_NOUNS = setOf("appointment", "appointments", "reminder", "reminders", "routine", "routines")
    private val VERB_NOUN =
        "\\b(add|book|schedule|create|make|set|new|start)\\s+((an?|the)\\s+)?(appointment|reminder|routine)\\b"
    private val FILLERS = setOf("please", "my", "the", "a", "an", "about")
    private val LEAD_GLUE = setOf("with", "for", "to")
    private val TRAIL_GLUE = setOf("on", "at", "for", "with", "to", "about", "and")
    private val JUNK_SINGLE = setOf(
        "add", "book", "schedule", "create", "make", "set", "new", "start",
        "appointment", "reminder", "reminders", "routine", "routines",
    ) + FILLERS

    private fun stripTitle(intent: String, original: String, consumed: List<IntRange>): String? {
        var text = original
        consumed.sortedByDescending { it.first }.forEach { range ->
            if (range.first in text.indices && range.last < text.length) {
                text = text.removeRange(range)
            }
        }
        // "add my medication routine" / "schedule dentist appointment".
        GAP_NOUNS[intent]?.let { noun ->
            val gap = Regex("(?i)^\\s*$GAP_VERBS\\s+(.+?)\\s+$noun\\s*\$").find(dropEdgeFillers(text))
            if (gap != null) {
                // Group 1 is the verb; group 2 is the sandwiched title.
                return cleanWords(gap.groupValues[2])?.takeUnless { single ->
                    single.lowercase(Locale.US) in JUNK_SINGLE &&
                        !single.contains(" ")
                }
            }
        }
        var stripped = " $text "
        stripped = stripped.replace(Regex("(?i)$VERB_NOUN"), " ")
        val phrases = ((COMMAND_PHRASES[intent] ?: emptyList()) + FILLERS).sortedByDescending { it.length }
        phrases.forEach { phrase ->
            stripped = stripped.replace(Regex("(?i)\\b${Regex.escape(phrase)}\\b"), " ")
        }
        val cleaned = cleanWords(stripped) ?: return null
        // A lone command word is not a title ("add" -> ask instead).
        if (!cleaned.contains(" ") && cleaned.lowercase(Locale.US) in JUNK_SINGLE) return null
        return cleaned
    }

    /** Drops politeness fillers at the edges ("please add … routine please"). */
    private fun dropEdgeFillers(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words[0].lowercase(Locale.US) in FILLERS) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase(Locale.US) in FILLERS) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ")
    }

    private fun cleanWords(text: String): String? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .filterNot { it.lowercase(Locale.US) in FILLERS }.toMutableList()
        while (
            words.isNotEmpty() &&
            (words[0].lowercase(Locale.US) in LEAD_GLUE || words[0].lowercase(Locale.US) in LEAD_NOUNS)
        ) {
            words.removeAt(0)
        }
        while (words.isNotEmpty() && words.last().lowercase(Locale.US) in TRAIL_GLUE) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ").trim('.', ',', '!', '?', ';', ':', '-').ifBlank { null }
    }

    private fun routineKind(norm: String): String = when {
        Regex("\\b(medicat\\w*|medicine|pills?|vitamins?|tablets?|insulin|doses?|drugs?)\\b")
            .containsMatchIn(norm) -> "MEDICATION"
        Regex("\\b(deliver\\w*|parcels?|packages?)\\b").containsMatchIn(norm) -> "DELIVERY"
        Regex("\\brecurr\\w*\\b").containsMatchIn(norm) -> "RECURRING"
        else -> "OTHER"
    }

    private fun routineNonDaily(norm: String): Boolean {
        if (Regex("\\b(weekly|monthly|yearly|custom|weekends?)\\b").containsMatchIn(norm)) return true
        val days = DayOfWeek.values().joinToString("|") {
            it.getDisplayName(TextStyle.FULL, Locale.US).lowercase()
        }
        if (Regex("\\b($days)\\b").containsMatchIn(norm)) return true
        val monthNames = (MONTHS.keys + MONTH_SHORT.keys).joinToString("|")
        if (Regex("\\b($monthNames)\\b").containsMatchIn(norm)) return true
        return false
    }

    private fun slotsFor(
        intent: String,
        joined: String,
        consumed: List<IntRange>,
        triggerMillis: Long?,
        zone: ZoneId,
    ): FastSlots {
        val norm = joined.lowercase(Locale.US)
        val title = stripTitle(intent, joined, consumed)
        if (intent == VoiceActionIntents.ADD_ROUTINE) {
            // today/tomorrow read as "daily starting now" (same as the AI);
            // explicit weekdays/months are genuinely non-daily -> sentinel.
            val rule = when {
                routineNonDaily(norm) -> "WEEKLY"
                triggerMillis != null -> {
                    val at = Instant.ofEpochMilli(triggerMillis).atZone(zone)
                    "DAILY %02d:%02d".format(at.hour, at.minute)
                }
                else -> "DAILY"
            }
            return FastSlots(title, triggerMillis, rule, routineKind(norm))
        }
        return FastSlots(title, triggerMillis, null, null)
    }

    /** Shared formatter for fast-path extraction payloads. */
    fun formatTrigger(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US))
}
