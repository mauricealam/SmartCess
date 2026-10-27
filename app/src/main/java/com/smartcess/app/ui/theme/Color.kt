package com.smartcess.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Spec color tokens (light). */
object SmartCessLightTokens {
    val Primary = Color(0xFF0B3D91)
    val Surface = Color(0xFFFFFFFF)
    val Background = Color(0xFFF6F8FB)
    val Success = Color(0xFF1B7F3B)
    val Warning = Color(0xFFB26A00)
    val Error = Color(0xFFB3261E)
    val Focus = Color(0xFFFFD54F)
}

/** Spec color tokens (dark). */
object SmartCessDarkTokens {
    val Primary = Color(0xFF8AB4F8)
    val Surface = Color(0xFF121212)
    val Background = Color(0xFF000000)
    val Success = Color(0xFF7BD88F)
    val Warning = Color(0xFFFFB74D)
    val Error = Color(0xFFF2B8B5)
    val Focus = Color(0xFFFFD54F)
}

/**
 * Night Ops console tokens. The accent pair differs per mode: bright teal
 * with near-black ink at night, deep teal with white ink by day. Both hold
 * AA or better (locked by ColorContrastTest).
 */
object SmartCessOpsTokens {
    val NightAccent = Color(0xFF2DD4BF)
    val OnNightAccent = Color(0xFF06231F)
    val DayAccent = Color(0xFF006B5E)
    val OnDayAccent = Color(0xFFFFFFFF)
}
