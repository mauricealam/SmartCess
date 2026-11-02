package com.smartcess.app.features.camera

import com.smartcess.app.core.ai.Speaker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single choke point for camera speech and command recognition.
 *
 * The app must never hear itself: every camera utterance (framing
 * guidance, prompts, confirmations) routes through [speak], which mutes
 * command dispatch until playback completes plus [TAIL_GUARD_MS] of
 * audio tail. Transcripts observed while muted are discarded by the
 * caller via [commandsAllowed] — including delayed results that arrive
 * after the speech that produced them. [stop] (interruption,
 * cancellation, navigation) also ends the mute after the tail, so a
 * stopped prompt cannot wedge listening shut or open.
 *
 * The clock is overridable for deterministic JVM tests.
 */
@Singleton
class CameraSpeechGate @Inject constructor(
    private val speaker: Speaker,
) {
    var clock: () -> Long = System::currentTimeMillis

    private var speaking = false
    private var mutedUntil = 0L
    private var gen = 0L

    /** True only when no app speech is active and the tail has elapsed. */
    val commandsAllowed: Boolean
        get() = synchronized(this) { !speaking && clock() >= mutedUntil }

    /**
     * Speaks, then runs [onDone] exactly once (done, error, stop, or
     * replaced — per the [Speaker] contract). Commands stay muted until
     * then plus the tail guard. A replaced utterance's completion never
     * unmutes its replacement.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val g = synchronized(this) {
            speaking = true
            mutedUntil = Long.MAX_VALUE
            ++gen
        }
        speaker.speak(text) {
            finish(g)
            onDone()
        }
    }

    /** Interrupts speech; listening resumes after the tail guard. */
    fun stop() {
        speaker.stop()
        synchronized(this) { finishLocked(gen) }
    }

    private fun finish(g: Long) {
        synchronized(this) { finishLocked(g) }
    }

    private fun finishLocked(g: Long) {
        if (g != gen) return
        speaking = false
        mutedUntil = clock() + TAIL_GUARD_MS
    }

    companion object {
        /** Audio-tail/echo guard after playback ends before listening resumes. */
        const val TAIL_GUARD_MS = 400L
    }
}
