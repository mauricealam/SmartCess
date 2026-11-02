package com.smartcess.app.features.camera

import com.smartcess.app.core.ai.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App speech must mute command recognition until playback completes plus
 * the audio-tail guard. The fake speaker completes manually; the clock is
 * manual too, so every window below is exact.
 */
class CameraSpeechGateTest {

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        private val pending = mutableListOf<() -> Unit>()
        var stops = 0
        override fun speak(text: String) {
            spoken.add(text)
        }
        override fun speak(text: String, onDone: () -> Unit) {
            spoken.add(text)
            pending.add(onDone)
        }
        override fun stop() {
            stops++
        }
        override fun setRate(rate: Float) {}
        fun completeLast() {
            pending.removeLastOrNull()?.invoke()
        }
        fun completeAll() {
            while (pending.isNotEmpty()) pending.removeAt(0).invoke()
        }
    }

    private var now = 1_000_000L
    private val speaker = FakeSpeaker()
    private val gate = CameraSpeechGate(speaker).also { it.clock = { now } }

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun startsAllowed() {
        assertTrue(gate.commandsAllowed)
    }

    @Test
    fun speaking_mutes_untilDonePlusTail() {
        var done = false
        gate.speak("say capture") { done = true }
        assertFalse(gate.commandsAllowed)
        speaker.completeLast()
        assertTrue(done)
        assertFalse(gate.commandsAllowed) // tail guard still holds
        advance(CameraSpeechGate.TAIL_GUARD_MS - 1)
        assertFalse(gate.commandsAllowed)
        advance(1)
        assertTrue(gate.commandsAllowed)
    }

    @Test
    fun stop_unmutes_afterTail() {
        gate.speak("say capture")
        assertFalse(gate.commandsAllowed)
        gate.stop()
        assertEquals(1, speaker.stops)
        assertFalse(gate.commandsAllowed)
        advance(CameraSpeechGate.TAIL_GUARD_MS)
        assertTrue(gate.commandsAllowed)
    }

    @Test
    fun replacedUtterance_cannotUnmuteReplacement() {
        gate.speak("move left")
        assertFalse(gate.commandsAllowed)
        gate.speak("move right") // QUEUE_FLUSH replaces the first
        speaker.completeAll() // stale completion, then the live one
        assertFalse(gate.commandsAllowed) // tail of the live utterance holds
        advance(CameraSpeechGate.TAIL_GUARD_MS)
        assertTrue(gate.commandsAllowed)
        assertEquals(listOf("move left", "move right"), speaker.spoken)
    }
}
