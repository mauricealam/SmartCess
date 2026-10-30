package com.smartcess.app.core.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Audible + haptic recording-start cue for blind Talk users. */
interface TalkCues {
    fun recordingStarted()
}

@Singleton
class TalkCuesImpl @Inject constructor(
    @ApplicationContext context: Context,
) : TalkCues {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private val tones: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull()
    }

    override fun recordingStarted() {
        runCatching { tones?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS) }
        val vib = vibrator ?: return
        runCatching {
            if (vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    companion object {
        private const val TONE_VOLUME = 80
        private const val TONE_MS = 120
        private const val PULSE_MS = 60L
    }
}
