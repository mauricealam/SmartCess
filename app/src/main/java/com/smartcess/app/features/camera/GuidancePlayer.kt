package com.smartcess.app.features.camera

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a [GuidancePlayer.guide] request actually did. */
enum class GuidanceOutcome {
    SPOKEN,
    LATCHED,
}

/**
 * Speaks one framing instruction at a time with haptic feedback.
 *
 * Speak-on-stable-change only (hearing-fatigue rule): a condition speaks
 * once, and only after [STABLE_FRAMES] consecutive requests — single-frame
 * threshold blips (A -> B -> A) never speak, so oscillation cannot chatter.
 * Speech and haptics both fire on the stable change; repeats stay silent.
 * Recovery steps (EDGE_LEFT -> OFF_LEFT -> READY) speak as each stabilizes.
 * Failsafe: after [MAX_SILENT_REQUESTS] requests without speech (permanent
 * flip-flop), the current condition speaks anyway — silence must never be
 * unbounded for a blind user. Speak-once: a persisting condition never
 * re-speaks (hearing-fatigue rule); only a genuine change speaks. The
 * speaker uses QUEUE_FLUSH, so a new
 * instruction replaces the previous one instead of queueing. All speech
 * routes through the [CameraSpeechGate] so guidance can never trigger
 * voice capture.
 */
@Singleton
class GuidancePlayer @Inject constructor(
    private val gate: CameraSpeechGate,
    @ApplicationContext context: Context,
) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private var lastCondition: FramingCondition? = null
    private var pendingCondition: FramingCondition? = null
    private var pendingCount: Int = 0
    private var silentRequests: Int = 0

    /**
     * Spec haptic preference, refreshed per camera session. Speech guidance
     * always stays on; this gates vibration only.
     */
    @Volatile var hapticsEnabled: Boolean = true

    /**
     * Requests guidance for [condition]. Returns what actually happened so
     * callers can tell spoken guidance apart from a latched repeat; existing
     * callers may ignore the result.
     */
    fun guide(condition: FramingCondition, instruction: String): GuidanceOutcome {
        if (condition == lastCondition) {
            pendingCondition = null
            pendingCount = 0
            return GuidanceOutcome.LATCHED
        }
        if (condition == pendingCondition) {
            pendingCount++
        } else {
            pendingCondition = condition
            pendingCount = 1
        }
        silentRequests++
        if (pendingCount >= STABLE_FRAMES || silentRequests >= MAX_SILENT_REQUESTS) {
            speak(condition, instruction)
            return GuidanceOutcome.SPOKEN
        }
        return GuidanceOutcome.LATCHED
    }

    private fun speak(condition: FramingCondition, instruction: String) {
        lastCondition = condition
        pendingCondition = null
        pendingCount = 0
        silentRequests = 0
        if (condition == FramingCondition.READY) {
            pulse(READY_PULSE_MS)
        } else {
            pulseWave(patternFor(condition))
        }
        gate.speak(instruction)
    }

    fun stop() {
        gate.stop()
    }

    /** New camera session: forget the latch so guidance speaks again. */
    fun reset() {
        lastCondition = null
        pendingCondition = null
        pendingCount = 0
        silentRequests = 0
    }

    private fun pulse(millis: Long) {
        if (!hapticsEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        // minSdk 26: VibrationEffect is always available.
        vib.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun pulseWave(pattern: LongArray) {
        if (!hapticsEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        /**
         * Consecutive identical requests before a new condition speaks.
         * Four frames (~400 ms at the sampled rate): edge-mass geometry
         * flickers in shorter bursts, while genuine moves persist.
         */
        const val STABLE_FRAMES = 4

        /**
         * Silence bound: with no stable condition for this many requests,
         * speak the current one anyway rather than staying mute forever.
         */
        const val MAX_SILENT_REQUESTS = 8
        const val READY_PULSE_MS = 220L

        /**
         * Spec haptic cues per condition: single pulse for re-centering,
         * double for distance/tilt, triple for darkness (READY uses [READY_PULSE_MS]).
         */
        fun patternFor(condition: FramingCondition): LongArray = when (condition) {
            FramingCondition.TOO_FAR,
            FramingCondition.TOO_CLOSE,
            FramingCondition.EDGE_CROPPED,
            FramingCondition.EDGE_LEFT,
            FramingCondition.EDGE_RIGHT,
            FramingCondition.EDGE_UP,
            FramingCondition.EDGE_DOWN,
            FramingCondition.TILTED,
            -> longArrayOf(0, TICK_MS, GAP_MS, TICK_MS)
            FramingCondition.TOO_DARK -> longArrayOf(0, TICK_MS, GAP_MS, TICK_MS, GAP_MS, TICK_MS)
            else -> longArrayOf(0, TICK_MS)
        }

        private const val TICK_MS = 35L
        private const val GAP_MS = 80L
    }
}
