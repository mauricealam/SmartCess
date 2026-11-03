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
 * Speak-on-change only (hearing-fatigue rule): each condition speaks once
 * per continuous occurrence — speech and haptics both fire on the change,
 * repeats stay silent. A new condition (including recovery steps like
 * EDGE_LEFT -> OFF_LEFT -> READY) always speaks immediately; the speaker
 * uses QUEUE_FLUSH, so a new instruction replaces the previous one
 * instead of queueing. All speech routes through the [CameraSpeechGate]
 * so guidance can never trigger voice capture.
 */
@Singleton
class GuidancePlayer @Inject constructor(
    private val gate: CameraSpeechGate,
    @ApplicationContext context: Context,
) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private var lastCondition: FramingCondition? = null

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
        if (condition == lastCondition) return GuidanceOutcome.LATCHED
        lastCondition = condition
        if (condition == FramingCondition.READY) {
            pulse(READY_PULSE_MS)
        } else {
            pulseWave(patternFor(condition))
        }
        gate.speak(instruction)
        return GuidanceOutcome.SPOKEN
    }

    fun stop() {
        gate.stop()
    }

    /** New camera session: forget the latch so guidance speaks again. */
    fun reset() {
        lastCondition = null
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
