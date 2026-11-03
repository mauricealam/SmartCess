package com.smartcess.app.features.camera

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What a [GuidancePlayer.guide] request actually did. */
enum class GuidanceOutcome {
    SPOKEN,
    COOLDOWN_SUPPRESSED,
    READY_LATCHED,
}

/**
 * Speaks one framing instruction at a time with haptic feedback.
 *
 * Anti-spam: a repeated condition re-speaks at most every [COOLDOWN_MS]
 * (spec: 3s); READY speaks once with a long pulse and latches until the
 * condition leaves READY. The speaker uses QUEUE_FLUSH, so a new
 * instruction always replaces the previous one instead of queueing.
 * All speech routes through the [CameraSpeechGate] so guidance can never
 * trigger voice capture.
 */
@Singleton
class GuidancePlayer @Inject constructor(
    private val gate: CameraSpeechGate,
    @ApplicationContext context: Context,
) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private var lastCondition: FramingCondition? = null
    private val lastSpokenAt = AtomicLong(0L)
    private var readyLatched = false

    /**
     * Spec haptic preference, refreshed per camera session. Speech guidance
     * always stays on; this gates vibration only.
     */
    @Volatile var hapticsEnabled: Boolean = true

    /**
     * Requests guidance for [condition]. Returns what actually happened so
     * the guidance-test diagnostics can show requested-vs-spoken; existing
     * callers may ignore the result.
     */
    fun guide(condition: FramingCondition, instruction: String): GuidanceOutcome {
        if (condition == FramingCondition.READY) {
            if (readyLatched) return GuidanceOutcome.READY_LATCHED
            readyLatched = true
            lastCondition = condition
            lastSpokenAt.set(System.currentTimeMillis())
            pulse(READY_PULSE_MS)
            gate.speak(instruction)
            return GuidanceOutcome.SPOKEN
        }
        readyLatched = false
        val now = System.currentTimeMillis()
        if (condition == lastCondition && now - lastSpokenAt.get() < COOLDOWN_MS) {
            return GuidanceOutcome.COOLDOWN_SUPPRESSED
        }
        lastCondition = condition
        lastSpokenAt.set(now)
        pulseWave(patternFor(condition))
        gate.speak(instruction)
        return GuidanceOutcome.SPOKEN
    }

    /** Repeat-cooldown remaining for [condition] (0 when it would speak now). */
    fun cooldownRemainingMs(condition: FramingCondition): Long {
        if (condition != lastCondition) return 0L
        return (COOLDOWN_MS - (System.currentTimeMillis() - lastSpokenAt.get())).coerceAtLeast(0L)
    }

    fun stop() {
        gate.stop()
    }

    /** New camera session: forget latch/cooldown so Ready can announce again. */
    fun reset() {
        readyLatched = false
        lastCondition = null
        lastSpokenAt.set(0L)
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
        const val COOLDOWN_MS = 3_000L
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
