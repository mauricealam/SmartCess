package com.smartcess.app.core.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App speech. Android platform TTS only — no model, no network.
 *
 * Retired: on-device text recognition and offline text drafts used a
 * non-Meta model, so they were removed for model compliance. Offline
 * capture now fails honestly with a rescan path instead of drafting.
 */
interface Speaker {
    fun speak(text: String)
    /** Speaks, then runs [onDone] exactly once (done, error, or replaced). */
    fun speak(text: String, onDone: () -> Unit) {
        speak(text)
    }
    fun stop()
    fun setRate(rate: Float)
}

@Singleton
class AndroidSpeaker @Inject constructor(
    @ApplicationContext context: Context,
) : Speaker {
    private var tts: TextToSpeech? = null
    private val utterLock = Any()
    private var utteranceSeq = 0L
    private var pendingId: String? = null
    private var pendingDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = completeIfCurrent(utteranceId)
            override fun onError(utteranceId: String?) = completeIfCurrent(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) = completeIfCurrent(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = completeIfCurrent(utteranceId)
        })
    }

    /** Runs the callback only for the live utterance; stale flush reports are ignored. */
    private fun completeIfCurrent(utteranceId: String?) {
        val done = synchronized(utterLock) {
            if (utteranceId != null && utteranceId == pendingId) {
                pendingId = null
                pendingDone.also { pendingDone = null }
            } else {
                null
            }
        }
        done?.invoke()
    }

    /** A flushed utterance never reports back; complete it so Speaking cannot hang. */
    private fun completeSuperseded() {
        val done = synchronized(utterLock) {
            pendingId = null
            pendingDone.also { pendingDone = null }
        }
        done?.invoke()
    }
    private var rate: Float = 1.0f

    override fun setRate(rate: Float) {
        this.rate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(this.rate)
    }

    override fun speak(text: String) {
        completeSuperseded()
        val id = synchronized(utterLock) { "smartcess-${++utteranceSeq}" }
        tts?.speak(ContentSafety.sanitizeForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun speak(text: String, onDone: () -> Unit) {
        completeSuperseded()
        val id = synchronized(utterLock) {
            "smartcess-${++utteranceSeq}".also {
                pendingId = it
                pendingDone = onDone
            }
        }
        tts?.speak(ContentSafety.sanitizeForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        tts?.stop()
        completeSuperseded()
    }
}

