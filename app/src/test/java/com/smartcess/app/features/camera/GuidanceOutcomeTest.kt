package com.smartcess.app.features.camera

import android.content.Context
import android.os.Vibrator
import com.smartcess.app.core.ai.Speaker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Speak-on-stable-change: a condition speaks once after four consecutive
 * requests; blips and repeats stay silent; permanent flip-flop still
 * speaks within a bounded number of requests.
 */
class GuidanceOutcomeTest {

    private fun player(speaker: Speaker): GuidancePlayer {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Vibrator::class.java) } returns null
        val gate = CameraSpeechGate(speaker)
        return GuidancePlayer(gate, ctx)
    }

    @Test
    fun firstCondition_speaksAfterFourFrames() {
        val guidance = player(mockk(relaxed = true))
        repeat(3) {
            assertEquals(
                GuidanceOutcome.LATCHED,
                guidance.guide(FramingCondition.TOO_FAR, "Closer."),
            )
        }
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
    }

    @Test
    fun repeat_latchesSilently() {
        val guidance = player(mockk(relaxed = true))
        repeat(4) { guidance.guide(FramingCondition.TOO_FAR, "Closer.") }
        assertEquals(
            GuidanceOutcome.LATCHED,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
        assertEquals(
            GuidanceOutcome.LATCHED,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
    }

    @Test
    fun singleFrameBlip_neverSpeaks() {
        val speaker = mockk<Speaker>(relaxed = true)
        val guidance = player(speaker)
        repeat(4) { guidance.guide(FramingCondition.TOO_FAR, "Closer.") } // stable: speaks
        guidance.guide(FramingCondition.OFF_LEFT, "Left.") // one-frame blip
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        verify(exactly = 1) { speaker.speak("Closer.", any()) }
        verify(exactly = 0) { speaker.speak("Left.", any()) }
    }

    @Test
    fun threeFrameBurst_neverSpeaks() {
        // Edge-mass flicker arrives in short bursts: three frames of a new
        // condition still stay silent.
        val speaker = mockk<Speaker>(relaxed = true)
        val guidance = player(speaker)
        repeat(4) { guidance.guide(FramingCondition.TOO_CLOSE, "Back.") }
        repeat(3) { guidance.guide(FramingCondition.EDGE_RIGHT, "Back and right.") }
        repeat(2) { guidance.guide(FramingCondition.TOO_CLOSE, "Back.") }
        verify(exactly = 1) { speaker.speak("Back.", any()) }
        verify(exactly = 0) { speaker.speak("Back and right.", any()) }
    }

    @Test
    fun stableChange_speaksAfterFourFrames() {
        val guidance = player(mockk(relaxed = true))
        repeat(4) { guidance.guide(FramingCondition.TOO_FAR, "Closer.") }
        repeat(3) {
            assertEquals(
                GuidanceOutcome.LATCHED,
                guidance.guide(FramingCondition.OFF_LEFT, "Left."),
            )
        }
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.OFF_LEFT, "Left."),
        )
    }

    @Test
    fun recoveryChain_speaksEachStepOnce() {
        val guidance = player(mockk(relaxed = true))
        repeat(4) { guidance.guide(FramingCondition.EDGE_LEFT, "Back and left.") }
        repeat(4) { guidance.guide(FramingCondition.OFF_LEFT, "Left.") }
        repeat(3) {
            assertEquals(
                GuidanceOutcome.LATCHED,
                guidance.guide(FramingCondition.READY, "Ready."),
            )
        }
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.READY, "Ready."),
        )
        assertEquals(
            GuidanceOutcome.LATCHED,
            guidance.guide(FramingCondition.READY, "Ready."),
        )
    }

    @Test
    fun permanentFlipFlop_speaksWithinBound() {
        val speaker = mockk<Speaker>(relaxed = true)
        val guidance = player(speaker)
        // Alternate forever: silence is bounded, never permanent.
        repeat(7) {
            guidance.guide(FramingCondition.TOO_FAR, "Closer.")
            guidance.guide(FramingCondition.OFF_LEFT, "Left.")
        }
        verify(exactly = 1) { speaker.speak(any(), any()) }
    }

    @Test
    fun reset_clearsLatch() {
        val guidance = player(mockk(relaxed = true))
        repeat(4) { guidance.guide(FramingCondition.TOO_FAR, "Closer.") }
        guidance.reset()
        repeat(3) {
            assertEquals(
                GuidanceOutcome.LATCHED,
                guidance.guide(FramingCondition.TOO_FAR, "Closer."),
            )
        }
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
    }

    @Test
    fun persistingCondition_neverRespeaks() {
        // Pure speak-once (hearing-fatigue rule): even dozens of repeats
        // stay silent; only a genuine change speaks.
        val speaker = mockk<Speaker>(relaxed = true)
        val guidance = player(speaker)
        repeat(4) { guidance.guide(FramingCondition.TOO_CLOSE, "Back.") }
        repeat(30) {
            assertEquals(
                GuidanceOutcome.LATCHED,
                guidance.guide(FramingCondition.TOO_CLOSE, "Back."),
            )
        }
        verify(exactly = 1) { speaker.speak("Back.", any()) }
    }
}
