package com.smartcess.app.features.camera

import androidx.annotation.StringRes
import com.smartcess.app.R

/**
 * Spoken + shown instruction per framing condition. The seven bill phrases
 * below are the exact contracted strings; framing stays on-device and these
 * are the only prompts the user hears while framing.
 */
object FramingPhrases {
    @StringRes
    fun stringRes(
        condition: FramingCondition,
        isDocument: Boolean = false,
    ): Int = when (condition) {
        FramingCondition.NO_TEXT ->
            if (isDocument) R.string.framing_no_text_doc else R.string.framing_no_text
        FramingCondition.TOO_FAR ->
            if (isDocument) R.string.framing_move_closer_doc else R.string.framing_move_closer
        FramingCondition.TOO_CLOSE -> R.string.framing_move_back
        FramingCondition.EDGE_CROPPED -> R.string.framing_edge_cropped
        FramingCondition.EDGE_LEFT -> R.string.framing_edge_left
        FramingCondition.EDGE_RIGHT -> R.string.framing_edge_right
        FramingCondition.EDGE_UP -> R.string.framing_edge_up
        FramingCondition.EDGE_DOWN -> R.string.framing_edge_down
        FramingCondition.OFF_LEFT -> R.string.framing_move_left
        FramingCondition.OFF_RIGHT -> R.string.framing_move_right
        FramingCondition.OFF_UP -> R.string.framing_move_up
        FramingCondition.OFF_DOWN -> R.string.framing_move_down
        FramingCondition.TILTED ->
            if (isDocument) R.string.framing_hold_straight_doc else R.string.framing_hold_straight
        FramingCondition.TOO_DARK -> R.string.framing_too_dark
        FramingCondition.READY -> R.string.framing_ready
    }
}
