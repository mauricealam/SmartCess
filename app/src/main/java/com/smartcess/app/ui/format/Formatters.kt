package com.smartcess.app.ui.format

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Locale-aware formatting. All user-visible labels come from strings.xml at call sites. */
object SmartCessFormat {
    fun money(amount: Double, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currencyCode.uppercase(locale)) }
        return format.format(amount)
    }

    fun isoDate(iso: String, locale: Locale = Locale.getDefault()): String {
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }

    fun instantDate(millis: Long, locale: Locale = Locale.getDefault()): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }

    fun instantTime(millis: Long, locale: Locale = Locale.getDefault()): String {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    }

    fun instantDateTime(millis: Long, locale: Locale = Locale.getDefault()): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return zoned.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale),
        )
    }
}
