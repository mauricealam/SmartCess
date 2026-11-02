package com.smartcess.app.features.camera

import android.content.Context
import android.os.Vibrator
import com.smartcess.app.core.ai.Speaker
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** guide() must report requested-vs-spoken for guidance-test diagnostics. */
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
    fun repeatWithinCooldown_suppressed() {
        val guidance = player(mockk(relaxed = true))
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        assertEquals(
            GuidanceOutcome.COOLDOWN_SUPPRESSED,
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
    fun ready_secondRequest_latched() {
        val guidance = player(mockk(relaxed = true))
        assertEquals(
            GuidanceOutcome.SPOKEN,
            guidance.guide(FramingCondition.READY, "Ready."),
        )
        assertEquals(
            GuidanceOutcome.READY_LATCHED,
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
    fun cooldownRemaining_zeroForNewCondition() {
        val guidance = player(mockk(relaxed = true))
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        assertEquals(0L, guidance.cooldownRemainingMs(FramingCondition.OFF_LEFT))
    }
}
