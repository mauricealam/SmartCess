package com.smartcess.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks WCAG AA (4.5:1) for every text/background pair the UI can render. */
class ColorContrastTest {

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(argb shr 16 and 0xFF) +
            0.7152 * channel(argb shr 8 and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun ratio(fg: Color, bg: Color): Double {
        val l1 = luminance(fg.toArgb())
        val l2 = luminance(bg.toArgb())
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun assertPair(name: String, scheme: ColorScheme, fg: Color, bg: Color) {
        assertTrue("$name ratio ${ratio(fg, bg)} below 4.5", ratio(fg, bg) >= 4.5)
    }

    private fun checkScheme(name: String, s: ColorScheme) {
        assertPair("$name onPrimary/primary", s, s.onPrimary, s.primary)
        assertPair("$name onPrimaryContainer/primaryContainer", s, s.onPrimaryContainer, s.primaryContainer)
        assertPair("$name onSecondaryContainer/secondaryContainer", s, s.onSecondaryContainer, s.secondaryContainer)
        assertPair("$name onTertiaryContainer/tertiaryContainer", s, s.onTertiaryContainer, s.tertiaryContainer)
        assertPair("$name onSurface/surface", s, s.onSurface, s.surface)
        assertPair("$name onBackground/background", s, s.onBackground, s.background)
        assertPair("$name onErrorContainer/errorContainer", s, s.onErrorContainer, s.errorContainer)
        // Secondary copy renders onSurfaceVariant on surface/background.
        assertPair("$name onSurfaceVariant/surface", s, s.onSurfaceVariant, s.surface)
        assertPair("$name onSurfaceVariant/background", s, s.onSurfaceVariant, s.background)
        // Text actions render primary on surface/background.
        assertPair("$name primary/surface", s, s.primary, s.surface)
        assertPair("$name primary/background", s, s.primary, s.background)
    }

    @Test
    fun lightScheme_meetsAA() = checkScheme("light", LightScheme)

    @Test
    fun lightHighContrastScheme_meetsAA() = checkScheme("light-hc", LightHighContrastScheme)

    @Test
    fun opsPairs_meetAA() {
        // Single theme: only the day Ops accent pair exists
        // (deep teal + white ink, ~6.4:1).
        val dayFg = SmartCessOpsTokens.OnDayAccent
        val dayBg = SmartCessOpsTokens.DayAccent
        assertTrue("day ops ratio ${ratio(dayFg, dayBg)} below 4.5", ratio(dayFg, dayBg) >= 4.5)
    }

    @Test
    fun lightGround_isPureWhite() {
        // One continuous white field: Background AND Surface are #FFFFFF.
        assertEquals(0xFFFFFFFF.toInt(), LightScheme.background.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), LightScheme.surface.toArgb())
    }

    @Test
    fun successWords_onWhite_meetsAA() {
        val fg = SmartCessLightTokens.Success
        val bg = LightScheme.surface
        assertTrue("success/white ratio ${ratio(fg, bg)} below 4.5", ratio(fg, bg) >= 4.5)
    }

    @Test
    fun heroAccent_onLightGround_meetsGraphicsAA() {
        // Day-teal hero accents/icons on the white field need 3:1 (graphics).
        val accent = SmartCessOpsTokens.DayAccent
        val bg = LightScheme.surface
        assertTrue("day accent/white ratio ${ratio(accent, bg)} below 3.0", ratio(accent, bg) >= 3.0)
    }
}
