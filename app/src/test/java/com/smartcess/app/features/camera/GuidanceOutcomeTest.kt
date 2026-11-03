package com.smartcess.app.features.camera

import android.content.Context
import android.os.Vibrator
import com.smartcess.app.core.ai.Speaker
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** guide() speaks on change only; repeats latch silently (fatigue rule). */
class GuidanceOutcomeTest {

    private fun player(speaker: Speaker): GuidancePlayer {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Vibrator::class.java) } returns null
        val gate = CameraSpeechGate(speaker)
        return GuidancePlayer(gate, ctx)
    }

    @Test
    fun firstGuide_speaks() {
        val guidance = player(mockk(relaxed = true))
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
    }

    @Test
    fun repeat_latchesSilently() {
        val guidance = player(mockk(relaxed = true))
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        assertEquals(
            GuidanceOutcome.LATCHED,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
        // Still latched no matter how long the condition persists.
        assertEquals(
            GuidanceOutcome.LATCHED,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
    }

    @Test
    fun changedCondition_speaksImmediately() {
        val guidance = player(mockk(relaxed = true))
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.OFF_LEFT, "Left."),
        )
    }

    @Test
    fun recoveryChain_speaksEachStepOnce() {
        val guidance = player(mockk(relaxed = true))
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.EDGE_LEFT, "Back and left."),
        )
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.OFF_LEFT, "Left."),
        )
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
    fun ready_afterLeaving_speaksAgain() {
        val guidance = player(mockk(relaxed = true))
        guidance.guide(FramingCondition.READY, "Ready.")
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.READY, "Ready."),
        )
    }

    @Test
    fun reset_clearsLatch() {
        val guidance = player(mockk(relaxed = true))
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        guidance.reset()
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.TOO_FAR, "Closer."),
        )
    }
}
