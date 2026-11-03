package com.smartcess.app.features.camera

import android.content.Context
import android.os.Vibrator
import com.smartcess.app.core.ai.Speaker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/** Session reset: a new camera session must announce Ready again. */
class GuidanceResetTest {

    private fun player(speaker: Speaker): GuidancePlayer {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Vibrator::class.java) } returns null
        val gate = CameraSpeechGate(speaker)
        return GuidancePlayer(gate, ctx)
    }

    @Test
    fun ready_latches_untilReset() {
        val speaker = mockk<Speaker>(relaxed = true)
        val guidance = player(speaker)
        guidance.guide(FramingCondition.READY, "Ready.")
        guidance.guide(FramingCondition.READY, "Ready.")
        verify(exactly = 1) { speaker.speak("Ready.", any()) }
        guidance.reset()
        guidance.guide(FramingCondition.READY, "Ready.")
        verify(exactly = 2) { speaker.speak("Ready.", any()) }
    }

    @Test
    fun reset_clearsLatch() {
        val speaker = mockk<Speaker>(relaxed = true)
        val guidance = player(speaker)
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        verify(exactly = 1) { speaker.speak("Closer.", any()) }
        guidance.reset()
        guidance.guide(FramingCondition.TOO_FAR, "Closer.")
        verify(exactly = 2) { speaker.speak("Closer.", any()) }
    }
}
