package com.smartcess.app.features.camera

/**
 * Pure framing rules for guided scanning. No Android dependencies so the
 * thresholds are unit-testable on the JVM (spec: framing-threshold tests).
 *
 * Priority (spec: no-text > far/close > off-center > tilt > lighting):
 * NO_TEXT -> TOO_FAR / TOO_CLOSE -> EDGE_* -> OFF_* -> TILTED -> TOO_DARK -> READY.
 *
 * EDGE_LEFT/RIGHT/UP/DOWN are compound states: detected content is near
 * the image boundary AND off-center on the dominant axis, so one instruction
 * carries both the back-off and the shift. Plain EDGE_CROPPED means the
 * content is near an edge while centered. "Edge" always means detected
 * content near the boundary — never proof about the physical page.
 *
 * The off-center signal has two sources. The bbox centroid works while the
 * page fits inside the frame; once the page overflows, the centroid
 * collapses to center and per-side touch asymmetry takes over (content
 * extends past the touched side). Overflow with single-side touch reports
 * the EDGE_* compound instead of a directionless TOO_CLOSE.
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
 * @param hasText Edge content found a subject to guide framing on.
 * @param coverage union text area as a fraction of the frame area (0..1).
 * @param offsetX union-box center x minus frame center, in frame widths.
 *   Negative means the bill appears left of center (move phone left).
 * @param offsetY union-box center y minus frame center, in frame heights.
 *   Negative means the bill appears above center (move phone up).
 * @param skewDegrees absolute tilt of the largest block's top edge.
 * @param meanLuminance mean frame luma (0..1) from the Y plane.
 * @param minEdgeMargin smallest distance from the content box to any
 *   frame edge, as a fraction of the frame's smaller side (0..~0.5).
 * @param marginLeft/marginTop/marginRight/marginBottom per-side content
 *   margins in upright screen space, same units as [minEdgeMargin].
 */
data class FramingInput(
    val hasText: Boolean,
    val coverage: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val skewDegrees: Float = 0f,
    val meanLuminance: Float = 1f,
    val minEdgeMargin: Float = 1f,
    val marginLeft: Float = 1f,
    val marginTop: Float = 1f,
    val marginRight: Float = 1f,
    val marginBottom: Float = 1f,
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
     * EMA weight of the newest frame in [smoothFraming]. Edge-mass geometry
     * jitters faster than real hand motion; 0.5 halves single-frame jumps
     * while tracking genuine pans within ~3 frames.
     */
    const val SMOOTHING_ALPHA = 0.5f

    /**
     * Content box closer than this to any frame edge flags possible
     * cropping. Heuristic limits: it sees detected EDGES, not the physical
     * page — blank margins near an edge are missed, and it never proves
     * the whole page is inside the frame.
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

    /**
     * Sticky READY: leaving READY for a milder-looking problem needs a
     * FIRM excursion, not a threshold graze — otherwise holding center
     * flaps "Ready ... move ... Ready" and confuses the user. Offsets must
     * clear tolerance plus this band; margins must dip below threshold
     * minus [READY_MARGIN_BAND]. Losing the page (NO_TEXT) always speaks
     * at once.
     */
    const val READY_EXIT_BAND = 0.04f
    const val READY_MARGIN_BAND = 0.01f

    fun evaluate(input: FramingInput): FramingCondition {
        if (!input.hasText) return FramingCondition.NO_TEXT
        if (input.coverage < MIN_COVERAGE) return FramingCondition.TOO_FAR
        if (input.coverage > MAX_COVERAGE) {
            return overflowCondition(input) ?: FramingCondition.TOO_CLOSE
        }
        if (input.minEdgeMargin < MIN_EDGE_MARGIN) return edgeCondition(input)
        val off = offDirection(input)
        if (off != null) return off
        if (input.skewDegrees > MAX_SKEW_DEGREES) return FramingCondition.TILTED
        if (input.meanLuminance < MIN_LUMINANCE) return FramingCondition.TOO_DARK
        return FramingCondition.READY
    }

    /**
     * One axis of the compound direction. [negative] selects LEFT/UP;
     * [magnitude] compares axes for the dominant-axis rule.
     */
    private data class AxisDir(val negative: Boolean, val magnitude: Float)

    /**
     * True when [touched] is at the frame boundary while [opposite] is
     * clear past the exit band, i.e. content extends past exactly one
     * side of this axis.
     */
    private fun singleSideTouch(touched: Float, opposite: Float): Boolean =
        touched < MIN_EDGE_MARGIN && opposite >= MIN_EDGE_MARGIN + EDGE_EXIT_BAND

    /** Touch-derived horizontal signal: toward the touched side, or null. */
    private fun touchHorizontal(input: FramingInput): AxisDir? {
        val touchL = singleSideTouch(input.marginLeft, input.marginRight)
        val touchR = singleSideTouch(input.marginRight, input.marginLeft)
        if (touchL == touchR) return null
        return AxisDir(
            negative = touchL,
            magnitude = kotlin.math.abs(input.marginRight - input.marginLeft),
        )
    }

    /** Touch-derived vertical signal: toward the touched side, or null. */
    private fun touchVertical(input: FramingInput): AxisDir? {
        val touchT = singleSideTouch(input.marginTop, input.marginBottom)
        val touchB = singleSideTouch(input.marginBottom, input.marginTop)
        if (touchT == touchB) return null
        return AxisDir(
            negative = touchT,
            magnitude = kotlin.math.abs(input.marginBottom - input.marginTop),
        )
    }

    /**
     * Edge proximity plus the dominant off-center axis (same axis rule as
     * [offDirection]), so a close off-center frame reports both facts. A
     * decisive centroid wins; touch asymmetry covers the overflow case
     * where the centroid collapses to center.
     */
    private fun edgeCondition(input: FramingInput): FramingCondition {
        val h = if (kotlin.math.abs(input.offsetX) > CENTER_TOLERANCE) {
            AxisDir(input.offsetX < 0, kotlin.math.abs(input.offsetX))
        } else {
            touchHorizontal(input)
        }
        val v = if (kotlin.math.abs(input.offsetY) > CENTER_TOLERANCE) {
            AxisDir(input.offsetY < 0, kotlin.math.abs(input.offsetY))
        } else {
            touchVertical(input)
        }
        return compound(h, v)
    }

    /**
     * Overflow variant: the centroid cannot be decisive past [MAX_COVERAGE]
     * (a 92%+ box is near-center by geometry), so only touch asymmetry
     * reports a compound; otherwise null keeps plain TOO_CLOSE.
     */
    private fun overflowCondition(input: FramingInput): FramingCondition? {
        val h = touchHorizontal(input)
        val v = touchVertical(input)
        if (h == null && v == null) return null
        return compound(h, v)
    }

    /** Dominant-axis compound, or plain EDGE_CROPPED when centered. */
    private fun compound(h: AxisDir?, v: AxisDir?): FramingCondition {
        if (h == null && v == null) return FramingCondition.EDGE_CROPPED
        if (v == null || (h != null && h.magnitude >= v.magnitude)) {
            return if (h!!.negative) FramingCondition.EDGE_LEFT else FramingCondition.EDGE_RIGHT
        }
        return if (v!!.negative) FramingCondition.EDGE_UP else FramingCondition.EDGE_DOWN
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
        if (current == null) return raw
        if (current == FramingCondition.READY && raw != FramingCondition.READY &&
            !readyExitConfirmed(input, raw)
        ) {
            return FramingCondition.READY
        }
        if (rank(raw) <= rank(current)) return raw
        return if (exitConfirmed(input, current)) raw else current
    }

    /**
     * True only when the excursion out of READY is firm: small wobbles
     * around the entry thresholds hold READY instead of flapping speech.
     */
    private fun readyExitConfirmed(input: FramingInput, raw: FramingCondition): Boolean =
        when (raw) {
            FramingCondition.READY -> true
            FramingCondition.NO_TEXT -> true
            FramingCondition.TOO_FAR -> input.coverage < MIN_COVERAGE - COV_EXIT_BAND
            FramingCondition.TOO_CLOSE -> input.coverage > MAX_COVERAGE + COV_EXIT_BAND
            FramingCondition.EDGE_CROPPED, FramingCondition.EDGE_LEFT,
            FramingCondition.EDGE_RIGHT, FramingCondition.EDGE_UP,
            FramingCondition.EDGE_DOWN,
            -> input.minEdgeMargin < MIN_EDGE_MARGIN - READY_MARGIN_BAND
            FramingCondition.OFF_LEFT, FramingCondition.OFF_RIGHT,
            -> kotlin.math.abs(input.offsetX) > CENTER_TOLERANCE + READY_EXIT_BAND
            FramingCondition.OFF_UP, FramingCondition.OFF_DOWN,
            -> kotlin.math.abs(input.offsetY) > CENTER_TOLERANCE + READY_EXIT_BAND
            FramingCondition.TILTED -> input.skewDegrees > MAX_SKEW_DEGREES + SKEW_EXIT_BAND
            FramingCondition.TOO_DARK -> input.meanLuminance < MIN_LUMINANCE - LUMA_EXIT_BAND
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

/**
 * Single-pole (exponential moving average) smoothing over the framing
 * stream. Callers feed the previous output back as [prev]; null seeds
 * instantly so a new session tracks from the first frame. Coverage,
 * offsets, and all five margins are smoothed; presence, tilt, and
 * luminance pass through untouched.
 */
fun smoothFraming(prev: FramingInput?, raw: FramingInput): FramingInput {
    if (prev == null) return raw
    val a = FramingEngine.SMOOTHING_ALPHA
    val b = 1f - a
    return raw.copy(
        coverage = a * raw.coverage + b * prev.coverage,
        offsetX = a * raw.offsetX + b * prev.offsetX,
        offsetY = a * raw.offsetY + b * prev.offsetY,
        minEdgeMargin = a * raw.minEdgeMargin + b * prev.minEdgeMargin,
        marginLeft = a * raw.marginLeft + b * prev.marginLeft,
        marginTop = a * raw.marginTop + b * prev.marginTop,
        marginRight = a * raw.marginRight + b * prev.marginRight,
        marginBottom = a * raw.marginBottom + b * prev.marginBottom,
    )
}
