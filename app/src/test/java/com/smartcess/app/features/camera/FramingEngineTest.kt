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
    ) = FramingInput(hasText, coverage, offsetX, offsetY, skewDegrees, meanLuminance)

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
}
