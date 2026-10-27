package com.smartcess.app.features.camera

/**
 * Pure framing rules for guided scanning. No Android dependencies so the
 * thresholds are unit-testable on the JVM (spec: framing-threshold tests).
 *
 * Priority (spec: no-text > far/close > off-center > tilt > lighting):
 * NO_TEXT -> TOO_FAR / TOO_CLOSE -> OFF_* -> TILTED -> TOO_DARK -> READY.
 */
enum class FramingCondition {
    NO_TEXT,
    TOO_FAR,
    TOO_CLOSE,
    OFF_LEFT,
    OFF_RIGHT,
    OFF_UP,
    OFF_DOWN,
    TILTED,
    TOO_DARK,
    READY,
}

/**
 * One analyzed frame, in normalized units.
 *
 * @param hasText ML Kit found at least one text block.
 * @param coverage union text area as a fraction of the frame area (0..1).
 * @param offsetX union-box center x minus frame center, in frame widths.
 *   Negative means the bill appears left of center (move phone left).
 * @param offsetY union-box center y minus frame center, in frame heights.
 *   Negative means the bill appears above center (move phone up).
 * @param skewDegrees absolute tilt of the largest block's top edge.
 * @param meanLuminance mean frame luma (0..1) from the Y plane.
 */
data class FramingInput(
    val hasText: Boolean,
    val coverage: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val skewDegrees: Float = 0f,
    val meanLuminance: Float = 1f,
)

object FramingEngine {
    /** Below this text coverage the bill is too small in frame. */
    const val MIN_COVERAGE = 0.15f

    /** Above this coverage the bill overflows the frame. */
    const val MAX_COVERAGE = 0.92f

    /** Center offset (fraction of frame edge) tolerated before re-centering. */
    const val CENTER_TOLERANCE = 0.12f

    /** Tilt tolerated before asking the user to straighten the phone. */
    const val MAX_SKEW_DEGREES = 12f

    /** Below this mean luma the scene is too dark to scan. */
    const val MIN_LUMINANCE = 0.18f

    fun evaluate(input: FramingInput): FramingCondition {
        if (!input.hasText) return FramingCondition.NO_TEXT
        if (input.coverage < MIN_COVERAGE) return FramingCondition.TOO_FAR
        if (input.coverage > MAX_COVERAGE) return FramingCondition.TOO_CLOSE
        val offX = kotlin.math.abs(input.offsetX) > CENTER_TOLERANCE
        val offY = kotlin.math.abs(input.offsetY) > CENTER_TOLERANCE
        if (offX || offY) {
            // Fix the worse axis first so guidance stays one instruction at a time.
            return if (kotlin.math.abs(input.offsetX) >= kotlin.math.abs(input.offsetY)) {
                if (input.offsetX < 0) FramingCondition.OFF_LEFT else FramingCondition.OFF_RIGHT
            } else {
                if (input.offsetY < 0) FramingCondition.OFF_UP else FramingCondition.OFF_DOWN
            }
        }
        if (input.skewDegrees > MAX_SKEW_DEGREES) return FramingCondition.TILTED
        if (input.meanLuminance < MIN_LUMINANCE) return FramingCondition.TOO_DARK
        return FramingCondition.READY
    }
}
