package com.smartcess.app.ui.format

import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale

/** Locale-aware formatting. All user-visible labels come from strings.xml at call sites. */
object SmartCessFormat {
    fun money(amount: Double, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currencyCode.uppercase(locale)) }
        return format.format(amount)
    }

    /**
     * Display date, always day-first ("3 Sep 2026") on every screen, matching
     * the spoken form. Month names follow [locale]; only the order is fixed.
     */
    fun isoDate(iso: String, locale: Locale = Locale.getDefault()): String {
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }

    /**
     * Spoken date ("31 December 2026"). US English like Talk's speech so the
     * TTS voice pronounces month names reliably; falls back to [iso].
     */
    fun spokenDate(iso: String): String {
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US))
    }

    fun instantDate(millis: Long, locale: Locale = Locale.getDefault()): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }

    fun instantTime(millis: Long, locale: Locale = Locale.getDefault()): String {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    }

    fun instantDateTime(millis: Long, locale: Locale = Locale.getDefault()): String {
        return instantDate(millis, locale) + ", " + instantTime(millis, locale)
    }

    /**
     * Humanizes a stored schedule rule ("DAILY 21:00" -> "Daily at 9:00 PM").
     * Unparseable rules pass through unchanged so manual entries never blank.
     */
    fun routineSchedule(rule: String, locale: Locale = Locale.getDefault()): String {
        val tokens = rule.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return rule
        val time = runCatching { LocalTime.parse(tokens.last()) }.getOrNull() ?: return rule
        val timeText = time.format(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
        )
        return when (tokens.first().uppercase(locale)) {
            "DAILY" -> "Daily at $timeText"
            "WEEKLY" -> {
                val day = tokens.getOrNull(1)?.let { shortDay(it, locale) }
                if (day != null) "Weekly on $day at $timeText" else "Weekly at $timeText"
            }
            "MONTHLY" -> "Monthly at $timeText"
            "YEARLY", "ANNUAL" -> "Yearly at $timeText"
            else -> rule
        }
    }

    private fun shortDay(token: String, locale: Locale): String? {
        val day = when (token.uppercase(locale)) {
            "MON" -> DayOfWeek.MONDAY
            "TUE", "TUES" -> DayOfWeek.TUESDAY
            "WED" -> DayOfWeek.WEDNESDAY
            "THU", "THUR", "THURS" -> DayOfWeek.THURSDAY
            "FRI" -> DayOfWeek.FRIDAY
            "SAT" -> DayOfWeek.SATURDAY
            "SUN" -> DayOfWeek.SUNDAY
            else -> return null
        }
        return day.getDisplayName(TextStyle.FULL, locale)
    }
}
