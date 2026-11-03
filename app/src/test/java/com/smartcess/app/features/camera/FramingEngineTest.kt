package com.smartcess.app.features.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM unit tests for the pure framing thresholds (spec test plan). */
class FramingEngineTest {

    private fun input(
        hasText: Boolean = true,
        coverage: Float = 0.5f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        skewDegrees: Float = 0f,
        meanLuminance: Float = 0.6f,
        minEdgeMargin: Float = 1f,
    ) = FramingInput(hasText, coverage, offsetX, offsetY, skewDegrees, meanLuminance, minEdgeMargin)

    @Test
    fun noText_whenNoBlocks() {
        assertEquals(FramingCondition.NO_TEXT, FramingEngine.evaluate(input(hasText = false)))
    }

    @Test
    fun tooFar_whenCoverageBelowMinimum() {
        assertEquals(
            FramingCondition.TOO_FAR,
            FramingEngine.evaluate(input(coverage = 0.05f)),
        )
    }

    @Test
    fun tooClose_whenCoverageAboveMaximum() {
        assertEquals(
            FramingCondition.TOO_CLOSE,
            FramingEngine.evaluate(input(coverage = 0.97f)),
        )
    }

    @Test
    fun offLeft_whenBillAppearsLeftOfCenter() {
        assertEquals(
            FramingCondition.OFF_LEFT,
            FramingEngine.evaluate(input(offsetX = -0.25f)),
        )
    }

    @Test
    fun offRight_whenBillAppearsRightOfCenter() {
        assertEquals(
            FramingCondition.OFF_RIGHT,
            FramingEngine.evaluate(input(offsetX = 0.25f)),
        )
    }

    @Test
    fun offUp_whenBillAppearsAboveCenter() {
        assertEquals(
            FramingCondition.OFF_UP,
            FramingEngine.evaluate(input(offsetY = -0.25f)),
        )
    }

    @Test
    fun offDown_whenBillAppearsBelowCenter() {
        assertEquals(
            FramingCondition.OFF_DOWN,
            FramingEngine.evaluate(input(offsetY = 0.25f)),
        )
    }

    @Test
    fun worseAxisWins_whenOffCenterOnBothAxes() {
        assertEquals(
            FramingCondition.OFF_RIGHT,
            FramingEngine.evaluate(input(offsetX = 0.30f, offsetY = 0.15f)),
        )
        assertEquals(
            FramingCondition.OFF_UP,
            FramingEngine.evaluate(input(offsetX = 0.15f, offsetY = -0.30f)),
        )
    }

    @Test
    fun tilted_whenSkewExceedsMaximum() {
        assertEquals(
            FramingCondition.TILTED,
            FramingEngine.evaluate(input(skewDegrees = 20f)),
        )
    }

    @Test
    fun tooDark_whenLuminanceBelowMinimum() {
        assertEquals(
            FramingCondition.TOO_DARK,
            FramingEngine.evaluate(input(meanLuminance = 0.05f)),
        )
    }

    @Test
    fun ready_whenCenteredUprightAndLit() {
        assertEquals(FramingCondition.READY, FramingEngine.evaluate(input()))
    }

    @Test
    fun priority_noText_beatsAll() {
        assertEquals(
            FramingCondition.NO_TEXT,
            FramingEngine.evaluate(
                input(hasText = false, coverage = 0.01f, offsetX = 0.4f, skewDegrees = 30f),
            ),
        )
    }

    @Test
    fun priority_distance_beatsOffCenter() {
        assertEquals(
            FramingCondition.TOO_FAR,
            FramingEngine.evaluate(input(coverage = 0.05f, offsetX = 0.4f)),
        )
    }

    @Test
    fun priority_offCenter_beatsTiltAndDarkness() {
        assertEquals(
            FramingCondition.OFF_LEFT,
            FramingEngine.evaluate(
                input(offsetX = -0.3f, skewDegrees = 30f, meanLuminance = 0.05f),
            ),
        )
    }

    @Test
    fun priority_tilt_beatsDarkness() {
        assertEquals(
            FramingCondition.TILTED,
            FramingEngine.evaluate(input(skewDegrees = 30f, meanLuminance = 0.05f)),
        )
    }

    @Test
    fun toleranceBoundary_centeredJustInside() {
        assertEquals(
            FramingCondition.READY,
            FramingEngine.evaluate(input(offsetX = 0.11f, offsetY = -0.11f)),
        )
    }

    @Test
    fun edgeCropped_whenTextTouchesFrameEdge() {
        assertEquals(
            FramingCondition.EDGE_CROPPED,
            FramingEngine.evaluate(input(minEdgeMargin = 0.0f)),
        )
    }

    @Test
    fun priority_overflow_beatsEdgeMargin() {
        assertEquals(
            FramingCondition.TOO_CLOSE,
            FramingEngine.evaluate(input(coverage = 0.97f, minEdgeMargin = 0.0f)),
        )
    }

    @Test
    fun priority_edgeMargin_beatsOffCenter() {
        // Edge still wins, but now carries the off-center axis with it.
        assertEquals(
            FramingCondition.EDGE_RIGHT,
            FramingEngine.evaluate(input(offsetX = 0.3f, minEdgeMargin = 0.01f)),
        )
    }

    @Test
    fun edgeCompound_eachDirection() {
        assertEquals(
            FramingCondition.EDGE_LEFT,
            FramingEngine.evaluate(input(offsetX = -0.3f, minEdgeMargin = 0.0f)),
        )
        assertEquals(
            FramingCondition.EDGE_RIGHT,
            FramingEngine.evaluate(input(offsetX = 0.3f, minEdgeMargin = 0.0f)),
        )
        assertEquals(
            FramingCondition.EDGE_UP,
            FramingEngine.evaluate(input(offsetY = -0.3f, minEdgeMargin = 0.0f)),
        )
        assertEquals(
            FramingCondition.EDGE_DOWN,
            FramingEngine.evaluate(input(offsetY = 0.3f, minEdgeMargin = 0.0f)),
        )
    }

    @Test
    fun edgeCompound_dominantAxisWins() {
        // Same worse-axis-first rule as plain OFF_*.
        assertEquals(
            FramingCondition.EDGE_LEFT,
            FramingEngine.evaluate(input(offsetX = -0.4f, offsetY = 0.2f, minEdgeMargin = 0.0f)),
        )
        assertEquals(
            FramingCondition.EDGE_DOWN,
            FramingEngine.evaluate(input(offsetX = -0.2f, offsetY = 0.4f, minEdgeMargin = 0.0f)),
        )
    }

    @Test
    fun edgeCompound_centeredStaysPlain() {
        assertEquals(
            FramingCondition.EDGE_CROPPED,
            FramingEngine.evaluate(input(offsetX = 0.05f, offsetY = -0.05f, minEdgeMargin = 0.0f)),
        )
    }

    @Test
    fun recovery_edgeToDirectionToReady() {
        // Backing off clears the margin: compound -> plain direction...
        assertEquals(
            FramingCondition.EDGE_LEFT,
            FramingEngine.evaluate(input(offsetX = -0.3f, minEdgeMargin = 0.0f)),
        )
        assertEquals(
            FramingCondition.OFF_LEFT,
            FramingEngine.evaluate(input(offsetX = -0.3f, minEdgeMargin = 0.2f)),
        )
        // ...then centering reaches READY.
        assertEquals(
            FramingCondition.READY,
            FramingEngine.evaluate(input(offsetX = -0.05f, minEdgeMargin = 0.2f)),
        )
    }

    @Test
    fun hysteresis_firstFrame_unbanded() {
        assertEquals(
            FramingCondition.OFF_RIGHT,
            FramingEngine.update(input(offsetX = 0.3f), null),
        )
    }

    @Test
    fun hysteresis_coverageDither_holdsFar() {
        val far = FramingCondition.TOO_FAR
        // 0.16 passes entry (0.15) but not the 0.18 exit: holds.
        assertEquals(
            far,
            FramingEngine.update(input(coverage = 0.16f), far),
        )
        assertEquals(
            FramingCondition.READY,
            FramingEngine.update(input(coverage = 0.19f), far),
        )
    }

    @Test
    fun hysteresis_closeDither_holdsClose() {
        val close = FramingCondition.TOO_CLOSE
        assertEquals(
            close,
            FramingEngine.update(input(coverage = 0.91f), close),
        )
        assertEquals(
            FramingCondition.READY,
            FramingEngine.update(input(coverage = 0.88f), close),
        )
    }

    @Test
    fun hysteresis_marginDither_holdsEdge() {
        val edge = FramingCondition.EDGE_LEFT
        // 0.025 passes entry (0.02) but not the 0.03 exit: holds compound.
        assertEquals(
            edge,
            FramingEngine.update(input(offsetX = -0.3f, minEdgeMargin = 0.025f), edge),
        )
        assertEquals(
            FramingCondition.OFF_LEFT,
            FramingEngine.update(input(offsetX = -0.3f, minEdgeMargin = 0.05f), edge),
        )
    }

    @Test
    fun hysteresis_offsetDither_holdsOff() {
        val off = FramingCondition.OFF_LEFT
        // 0.11 passes entry (0.12) but not the 0.10 exit: holds.
        assertEquals(
            off,
            FramingEngine.update(input(offsetX = -0.11f), off),
        )
        assertEquals(
            FramingCondition.READY,
            FramingEngine.update(input(offsetX = -0.09f), off),
        )
    }

    @Test
    fun hysteresis_equalRank_switchesImmediately() {
        // Fast pan across center: same-rank problems never wait.
        assertEquals(
            FramingCondition.OFF_RIGHT,
            FramingEngine.update(input(offsetX = 0.3f), FramingCondition.OFF_LEFT),
        )
    }

    @Test
    fun hysteresis_worseRank_switchesImmediately() {
        assertEquals(
            FramingCondition.EDGE_LEFT,
            FramingEngine.update(
                input(offsetX = -0.3f, minEdgeMargin = 0.0f),
                FramingCondition.OFF_LEFT,
            ),
        )
        assertEquals(
            FramingCondition.TOO_FAR,
            FramingEngine.update(input(coverage = 0.05f), FramingCondition.OFF_RIGHT),
        )
    }

    @Test
    fun hysteresis_readyLeavesImmediately() {
        assertEquals(
            FramingCondition.OFF_RIGHT,
            FramingEngine.update(input(offsetX = 0.3f), FramingCondition.READY),
        )
    }

    @Test
    fun ready_whenMarginClear() {
        assertEquals(
            FramingCondition.READY,
            FramingEngine.evaluate(input(minEdgeMargin = 0.2f)),
        )
    }
}
