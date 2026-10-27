package com.smartcess.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
        // Text actions render primary on surface/background.
        assertPair("$name primary/surface", s, s.primary, s.surface)
        assertPair("$name primary/background", s, s.primary, s.background)
    }

    @Test
    fun lightScheme_meetsAA() = checkScheme("light", LightScheme)

    @Test
    fun darkScheme_meetsAA() = checkScheme("dark", DarkScheme)
}
