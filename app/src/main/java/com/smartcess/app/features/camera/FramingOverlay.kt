package com.smartcess.app.features.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcess.app.R
import com.smartcess.app.ui.theme.SmartCessTheme

/**
 * Viewfinder framing guide: corner brackets over the camera preview.
 * Warning color while framing, success color when READY (spec overlay).
 * Never the only signal: the spoken instruction + live-region text carry
 * the same state for TalkBack users (no color-only info).
 */
@Composable
fun FramingOverlay(
    condition: FramingCondition,
    modifier: Modifier = Modifier,
) {
    val extra = SmartCessTheme.extra
    val color = if (condition == FramingCondition.READY) extra.success else extra.warning
    val description = stringResource(R.string.camera_framing_desc)
    val density = LocalDensity.current
    val strokePx = with(density) { STROKE_WIDTH_DP.dp.toPx() }
    val bracketPx = with(density) { BRACKET_LENGTH_DP.dp.toPx() }
    val insetPx = with(density) { INSET_DP.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = description },
    ) {
        val left = insetPx
        val top = insetPx
        val right = size.width - insetPx
        val bottom = size.height - insetPx
        // Dim the area outside the guide so the target region stands out.
        drawRect(
            color = androidx.compose.ui.graphics.Color.Black,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            alpha = DIM_ALPHA,
        )
        val cap = StrokeCap.Round
        // Four corner brackets.
        drawLine(color = color, start = Offset(left, top + bracketPx), end = Offset(left, top), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(left, top), end = Offset(left + bracketPx, top), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(right - bracketPx, top), end = Offset(right, top), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(right, top), end = Offset(right, top + bracketPx), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(left, bottom - bracketPx), end = Offset(left, bottom), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(left, bottom), end = Offset(left + bracketPx, bottom), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(right, bottom - bracketPx), end = Offset(right, bottom), strokeWidth = strokePx, cap = cap)
        drawLine(color = color, start = Offset(right, bottom), end = Offset(right - bracketPx, bottom), strokeWidth = strokePx, cap = cap)
    }
}

private const val STROKE_WIDTH_DP = 6
private const val BRACKET_LENGTH_DP = 56
private const val INSET_DP = 32
private const val DIM_ALPHA = 0.25f
