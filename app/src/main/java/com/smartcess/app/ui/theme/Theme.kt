package com.smartcess.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Full brand schemes. Every slot is specified so no Material3 baseline
 * default can ever leak into the UI (the cause of the pastel unbranded look).
 * Text pairs are locked to WCAG AA by ColorContrastTest.
 */
/** Visible to tests so ColorContrastTest locks the AA ratios of the real schemes. */
internal val LightScheme = lightColorScheme(
    primary = SmartCessLightTokens.Primary, // #0B3D91
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF0B3D91),
    secondary = Color(0xFF3B5BA9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F5),
    onSecondaryContainer = Color(0xFF0B3D91),
    tertiary = Color(0xFF7A5200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE1A8),
    onTertiaryContainer = Color(0xFF4A3200),
    surface = SmartCessLightTokens.Surface, // #FFFFFF
    onSurface = SmartCessLightTokens.Ink, // #101418
    surfaceVariant = Color(0xFFE2E6EC),
    onSurfaceVariant = SmartCessLightTokens.SecondaryText, // #3C4043
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE6EAF1),
    surfaceContainerHighest = Color(0xFFDFE4EC),
    background = SmartCessLightTokens.Background, // #FFFFFF
    onBackground = SmartCessLightTokens.Ink, // #101418
    error = SmartCessLightTokens.Error, // #B3261E
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF74777F),
    outlineVariant = SmartCessLightTokens.CardOutline, // #1A1D21 at 12%
    scrim = Color.Black,
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFF8AB4F8),
    surfaceTint = SmartCessLightTokens.Primary,
)

/**
 * Maximum-contrast schemes for the high-contrast preference. Fully specified
 * like the standard schemes: a skeletal override would leak M3 baseline
 * defaults into exactly the mode that must guarantee contrast. Ops accents
 * stay on the Day/Night pairs (already AA-or-better on these grounds, locked
 * by opsPairs_meetAA); only the scheme roles go pure black/white.
 */
internal val LightHighContrastScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.Black,
    onPrimaryContainer = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.Black,
    onSecondaryContainer = Color.White,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color.Black,
    onTertiaryContainer = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    error = Color(0xFF7F1D1D),
    onError = Color.White,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color.White,
    outline = Color.Black,
    outlineVariant = Color.Black,
    scrim = Color.Black,
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    inversePrimary = Color.White,
    surfaceTint = Color.Black,
)

/** Extra tokens beyond the Material3 palette (status + focus + ops accent). */
@Immutable
data class SmartCessColors(
    val success: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val focus: androidx.compose.ui.graphics.Color,
    val ops: androidx.compose.ui.graphics.Color,
    val onOps: androidx.compose.ui.graphics.Color,
)

private val LocalSmartCessColors = staticCompositionLocalOf {
    SmartCessColors(
        success = SmartCessLightTokens.Success,
        warning = SmartCessLightTokens.Warning,
        focus = SmartCessLightTokens.Focus,
        ops = SmartCessOpsTokens.DayAccent,
        onOps = SmartCessOpsTokens.OnDayAccent,
    )
}

/** Accessor for the extra SmartCess tokens: `SmartCessTheme.extra.success`, etc. */
object SmartCessTheme {
    val extra: SmartCessColors
        @Composable
        get() = LocalSmartCessColors.current
}

/**
 * Single white theme: the app never follows the system dark mode.
 * [highContrast] stays as an explicit accessibility preference (light
 * high-contrast only); [largeText] scales type 1.3x.
 */
@Composable
fun SmartCessTheme(
    highContrast: Boolean = false,
    largeText: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (highContrast) LightHighContrastScheme else LightScheme
    val typography = if (largeText) SmartCessTypography.scaled(LARGE_TEXT_FACTOR) else SmartCessTypography
    val extra = SmartCessColors(
        success = SmartCessLightTokens.Success,
        warning = SmartCessLightTokens.Warning,
        focus = SmartCessLightTokens.Focus,
        ops = SmartCessOpsTokens.DayAccent,
        onOps = SmartCessOpsTokens.OnDayAccent,
    )
    CompositionLocalProvider(LocalSmartCessColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}

private const val LARGE_TEXT_FACTOR = 1.3f

private fun TextStyle.scaledBy(factor: Float): TextStyle {
    val size = if (fontSize.isSpecified) (fontSize.value * factor).sp else fontSize
    val height = if (lineHeight.isSpecified) (lineHeight.value * factor).sp else lineHeight
    return copy(fontSize = size, lineHeight = height)
}

/** Scales every type style for the large-text preference. */
private fun Typography.scaled(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaledBy(factor),
    displayMedium = displayMedium.scaledBy(factor),
    displaySmall = displaySmall.scaledBy(factor),
    headlineLarge = headlineLarge.scaledBy(factor),
    headlineMedium = headlineMedium.scaledBy(factor),
    headlineSmall = headlineSmall.scaledBy(factor),
    titleLarge = titleLarge.scaledBy(factor),
    titleMedium = titleMedium.scaledBy(factor),
    titleSmall = titleSmall.scaledBy(factor),
    bodyLarge = bodyLarge.scaledBy(factor),
    bodyMedium = bodyMedium.scaledBy(factor),
    bodySmall = bodySmall.scaledBy(factor),
    labelLarge = labelLarge.scaledBy(factor),
    labelMedium = labelMedium.scaledBy(factor),
    labelSmall = labelSmall.scaledBy(factor),
)
