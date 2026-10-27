package com.smartcess.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE2E6EC),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8FB),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE6EAF1),
    surfaceContainerHighest = Color(0xFFDFE4EC),
    background = SmartCessLightTokens.Background, // #F6F8FB
    onBackground = Color(0xFF191C20),
    error = SmartCessLightTokens.Error, // #B3261E
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFF8AB4F8),
    surfaceTint = SmartCessLightTokens.Primary,
)

internal val DarkScheme = darkColorScheme(
    primary = SmartCessDarkTokens.Primary, // #8AB4F8
    onPrimary = Color(0xFF062B5C),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFA0BFF0),
    onSecondary = Color(0xFF062B5C),
    secondaryContainer = Color(0xFF24407C),
    onSecondaryContainer = Color(0xFFDCE6F5),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF4A2F00),
    tertiaryContainer = Color(0xFF6B4A00),
    onTertiaryContainer = Color(0xFFFFE1A8),
    surface = SmartCessDarkTokens.Surface, // #121212
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF24262B),
    surfaceContainerHighest = Color(0xFF2F3136),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE3E2E6),
    error = SmartCessDarkTokens.Error, // #F2B8B5
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF43474E),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF0B3D91),
    surfaceTint = SmartCessDarkTokens.Primary,
)

/** Maximum-contrast overrides for the high-contrast preference. */
private val LightHighContrastScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    error = Color(0xFF7F1D1D),
    onError = Color.White,
)

private val DarkHighContrastScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    surface = Color.Black,
    onSurface = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    error = Color(0xFFFFDAD6),
    onError = Color.Black,
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

@Composable
fun SmartCessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    largeText: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        highContrast && darkTheme -> DarkHighContrastScheme
        highContrast -> LightHighContrastScheme
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val typography = if (largeText) SmartCessTypography.scaled(LARGE_TEXT_FACTOR) else SmartCessTypography
    val extra = if (darkTheme) {
        SmartCessColors(
            success = SmartCessDarkTokens.Success,
            warning = SmartCessDarkTokens.Warning,
            focus = SmartCessDarkTokens.Focus,
            ops = SmartCessOpsTokens.NightAccent,
            onOps = SmartCessOpsTokens.OnNightAccent,
        )
    } else {
        SmartCessColors(
            success = SmartCessLightTokens.Success,
            warning = SmartCessLightTokens.Warning,
            focus = SmartCessLightTokens.Focus,
            ops = SmartCessOpsTokens.DayAccent,
            onOps = SmartCessOpsTokens.OnDayAccent,
        )
    }
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
