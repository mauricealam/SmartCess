package com.smartcess.app.features.camera

import com.smartcess.app.features.camera.GuidancePlayer.Companion.patternFor
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Unit tests for spec haptic cues per framing condition. */
class GuidanceHapticsTest {

    @Test
    fun recenterConditions_singlePulse() {
        listOf(
            FramingCondition.NO_TEXT,
            FramingCondition.OFF_LEFT,
            FramingCondition.OFF_RIGHT,
            FramingCondition.OFF_UP,
            FramingCondition.OFF_DOWN,
        ).forEach {
            assertArrayEquals(longArrayOf(0, 35), patternFor(it))
        }
    }

    @Test
    fun distanceAndTilt_doublePulse() {
        listOf(FramingCondition.TOO_FAR, FramingCondition.TOO_CLOSE, FramingCondition.TILTED).forEach {
            assertArrayEquals(longArrayOf(0, 35, 80, 35), patternFor(it))
        }
    }

    @Test
    fun darkness_triplePulse() {
        assertArrayEquals(longArrayOf(0, 35, 80, 35, 80, 35), patternFor(FramingCondition.TOO_DARK))
    }
}
