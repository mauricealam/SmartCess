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

private val LightScheme = lightColorScheme(
    primary = SmartCessLightTokens.Primary,
    surface = SmartCessLightTokens.Surface,
    background = SmartCessLightTokens.Background,
    error = SmartCessLightTokens.Error,
)

private val DarkScheme = darkColorScheme(
    primary = SmartCessDarkTokens.Primary,
    surface = SmartCessDarkTokens.Surface,
    background = SmartCessDarkTokens.Background,
    error = SmartCessDarkTokens.Error,
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

/** Extra spec tokens beyond the Material3 palette (status + focus highlight). */
@Immutable
data class SmartCessColors(
    val success: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val focus: androidx.compose.ui.graphics.Color,
)

private val LocalSmartCessColors = staticCompositionLocalOf {
    SmartCessColors(
        success = SmartCessLightTokens.Success,
        warning = SmartCessLightTokens.Warning,
        focus = SmartCessLightTokens.Focus,
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
        )
    } else {
        SmartCessColors(
            success = SmartCessLightTokens.Success,
            warning = SmartCessLightTokens.Warning,
            focus = SmartCessLightTokens.Focus,
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
