package com.smartcess.app.features.camera

/**
 * Pure framing rules for guided scanning. No Android dependencies so the
 * thresholds are unit-testable on the JVM (spec: framing-threshold tests).
 *
 * Priority (spec: no-text > far/close > off-center > tilt > lighting):
 * NO_TEXT -> TOO_FAR / TOO_CLOSE -> EDGE_* -> OFF_* -> TILTED -> TOO_DARK -> READY.
 *
 * EDGE_LEFT/RIGHT/UP/DOWN are compound states: detected text is near the
 * image boundary AND off-center on the dominant axis, so one instruction
 * carries both the back-off and the shift. Plain EDGE_CROPPED means the
 * text is near an edge while centered. "Edge" always means detected text
 * near the boundary — never proof about the physical page.
 */
enum class FramingCondition {
    NO_TEXT,
    TOO_FAR,
    TOO_CLOSE,
    EDGE_CROPPED,
    EDGE_LEFT,
    EDGE_RIGHT,
    EDGE_UP,
    EDGE_DOWN,
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
 * @param minEdgeMargin smallest distance from the text union box to any
 *   frame edge, as a fraction of the frame's smaller side (0..~0.5).
 */
data class FramingInput(
    val hasText: Boolean,
    val coverage: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val skewDegrees: Float = 0f,
    val meanLuminance: Float = 1f,
    val minEdgeMargin: Float = 1f,
)

/**
 * Temporary guidance-test diagnostics. Numbers and condition names only —
 * never OCR text, images, document content, or credentials. Populated
 * only in the capture-disabled diagnostic session; null otherwise.
 */
data class FramingDiag(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val rotation: Int = 0,
    val blocks: Int = 0,
    val coverage: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val margin: Float = 1f,
    val condition: FramingCondition = FramingCondition.NO_TEXT,
    /** Raw per-frame evaluation before hysteresis (proves dither capture). */
    val raw: FramingCondition = FramingCondition.NO_TEXT,
    val outcome: GuidanceOutcome = GuidanceOutcome.SPOKEN,
    val gateAllowed: Boolean = true,
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

    /**
     * Text union closer than this to any frame edge flags possible
     * cropping. Heuristic limits: it sees detected TEXT, not the physical
     * page — margins, photos, or undetected print near an edge are missed,
     * and it never proves the whole page is inside the frame.
     */
    const val MIN_EDGE_MARGIN = 0.02f

    /**
     * Hysteresis exit bands (entry thresholds above are unchanged): leaving
     * a condition requires clearing its band, so threshold noise cannot
     * chatter between states. A new or worse problem still switches
     * immediately — only recovery from a problem is banded.
     */
    const val COV_EXIT_BAND = 0.03f
    const val EDGE_EXIT_BAND = 0.01f
    const val CENTER_EXIT_TOLERANCE = 0.10f
    const val SKEW_EXIT_BAND = 2f
    const val LUMA_EXIT_BAND = 0.05f

    fun evaluate(input: FramingInput): FramingCondition {
        if (!input.hasText) return FramingCondition.NO_TEXT
        if (input.coverage < MIN_COVERAGE) return FramingCondition.TOO_FAR
        if (input.coverage > MAX_COVERAGE) return FramingCondition.TOO_CLOSE
        if (input.minEdgeMargin < MIN_EDGE_MARGIN) return edgeCondition(input)
        val off = offDirection(input)
        if (off != null) return off
        if (input.skewDegrees > MAX_SKEW_DEGREES) return FramingCondition.TILTED
        if (input.meanLuminance < MIN_LUMINANCE) return FramingCondition.TOO_DARK
        return FramingCondition.READY
    }

    /**
     * Edge proximity plus the dominant off-center axis (same axis rule as
     * [offDirection]), so a close off-center frame reports both facts.
     */
    private fun edgeCondition(input: FramingInput): FramingCondition {
        val offX = kotlin.math.abs(input.offsetX) > CENTER_TOLERANCE
        val offY = kotlin.math.abs(input.offsetY) > CENTER_TOLERANCE
        if (!offX && !offY) return FramingCondition.EDGE_CROPPED
        return if (kotlin.math.abs(input.offsetX) >= kotlin.math.abs(input.offsetY)) {
            if (input.offsetX < 0) FramingCondition.EDGE_LEFT else FramingCondition.EDGE_RIGHT
        } else {
            if (input.offsetY < 0) FramingCondition.EDGE_UP else FramingCondition.EDGE_DOWN
        }
    }

    /** Dominant off-center direction, or null when inside tolerance. */
    private fun offDirection(input: FramingInput): FramingCondition? {
        val offX = kotlin.math.abs(input.offsetX) > CENTER_TOLERANCE
        val offY = kotlin.math.abs(input.offsetY) > CENTER_TOLERANCE
        if (!offX && !offY) return null
        // Fix the worse axis first so guidance stays one instruction at a time.
        return if (kotlin.math.abs(input.offsetX) >= kotlin.math.abs(input.offsetY)) {
            if (input.offsetX < 0) FramingCondition.OFF_LEFT else FramingCondition.OFF_RIGHT
        } else {
            if (input.offsetY < 0) FramingCondition.OFF_UP else FramingCondition.OFF_DOWN
        }
    }

    /** Severity rank: lower reports first in [evaluate]. */
    private fun rank(condition: FramingCondition): Int = when (condition) {
        FramingCondition.NO_TEXT -> 0
        FramingCondition.TOO_FAR, FramingCondition.TOO_CLOSE -> 1
        FramingCondition.EDGE_CROPPED, FramingCondition.EDGE_LEFT,
        FramingCondition.EDGE_RIGHT, FramingCondition.EDGE_UP,
        FramingCondition.EDGE_DOWN,
        -> 2
        FramingCondition.OFF_LEFT, FramingCondition.OFF_RIGHT,
        FramingCondition.OFF_UP, FramingCondition.OFF_DOWN,
        -> 3
        FramingCondition.TILTED -> 4
        FramingCondition.TOO_DARK -> 5
        FramingCondition.READY -> 6
    }

    /**
     * Hysteresis stabilizer: [evaluate] decides entry instantly, but leaving
     * [current] toward a milder condition requires clearing the exit band.
     * A new or worse problem (rank at or above current) switches at once,
     * so fast pans and fresh problems are never swallowed. Null starts
     * unbanded.
     */
    fun update(input: FramingInput, current: FramingCondition?): FramingCondition {
        val raw = evaluate(input)
        if (current == null || rank(raw) <= rank(current)) return raw
        return if (exitConfirmed(input, current)) raw else current
    }

    private fun exitConfirmed(input: FramingInput, current: FramingCondition): Boolean =
        when (current) {
            FramingCondition.NO_TEXT -> input.hasText
            FramingCondition.TOO_FAR -> input.coverage >= MIN_COVERAGE + COV_EXIT_BAND
            FramingCondition.TOO_CLOSE -> input.coverage <= MAX_COVERAGE - COV_EXIT_BAND
            FramingCondition.EDGE_CROPPED, FramingCondition.EDGE_LEFT,
            FramingCondition.EDGE_RIGHT, FramingCondition.EDGE_UP,
            FramingCondition.EDGE_DOWN,
            -> input.minEdgeMargin >= MIN_EDGE_MARGIN + EDGE_EXIT_BAND
            FramingCondition.OFF_LEFT, FramingCondition.OFF_RIGHT,
            FramingCondition.OFF_UP, FramingCondition.OFF_DOWN,
            -> kotlin.math.abs(input.offsetX) <= CENTER_EXIT_TOLERANCE &&
                kotlin.math.abs(input.offsetY) <= CENTER_EXIT_TOLERANCE
            FramingCondition.TILTED -> input.skewDegrees <= MAX_SKEW_DEGREES - SKEW_EXIT_BAND
            FramingCondition.TOO_DARK -> input.meanLuminance >= MIN_LUMINANCE + LUMA_EXIT_BAND
            // READY never blocks here (nothing outranks it), but be permissive.
            FramingCondition.READY -> true
        }
}
