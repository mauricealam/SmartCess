package com.smartcess.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Spec color tokens (light). Background AND Surface are pure white: one
 * continuous white field. Separation comes from the 1dp [CardOutline] edge
 * plus 12dp cards, never from a tonal gray fill alone.
 */
object SmartCessLightTokens {
    val Primary = Color(0xFF0B3D91)
    val Surface = Color(0xFFFFFFFF)
    val Background = Color(0xFFFFFFFF)
    /** Ink for primary text on white (~17:1). */
    val Ink = Color(0xFF101418)
    /** Floor for secondary text on white (~10.5:1); never gray-400. */
    val SecondaryText = Color(0xFF3C4043)
    /** Success words on white (~6.5:1); status keeps text+icon, never color-alone. */
    val Success = Color(0xFF146C2E)
    val Warning = Color(0xFFB26A00)
    val Error = Color(0xFFB3261E)
    val Focus = Color(0xFFFFD54F)
    /** 1dp hairline edge for cards/banners/rows on the white field. */
    val CardOutline = Color(0x1F1A1D21)
}

/**
 * Ops console tokens. Single theme: deep teal with white ink (~6.4:1,
 * locked by ColorContrastTest).
 */
object SmartCessOpsTokens {
    val DayAccent = Color(0xFF006B5E)
    val OnDayAccent = Color(0xFFFFFFFF)
}
