package com.smartcess.app.core.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/**
 * Offline fallback: when the Meta API fails, ML Kit OCR reads text on-device
 * and TTS speaks it. Drafts are always labelled [ContentSafety.OFFLINE_LABEL].
 */
interface OcrEngine {
    suspend fun recognizeText(image: InputImage): String
}

@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognizeText(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText.text) { _, _, _ -> } }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}

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

/** Heuristic offline bill draft from raw OCR text (amount + date patterns). */
object OfflineDrafts {
    private val amountPattern = Regex("(?:RM|MYR|\\$)?\\s?(\\d{1,6}(?:,\\d{3})*\\.\\d{2})")
    private val datePattern = Regex("(\\d{4}-\\d{2}-\\d{2})|(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})")

    fun billFromOcr(ocrText: String): BillExtraction {
        val amount = amountPattern.find(ocrText)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull() ?: -1.0
        val date = datePattern.find(ocrText)?.value ?: ""
        val missing = buildList {
            add("provider")
            if (amount <= 0) add("amount")
            if (date.isBlank()) add("due_date")
        }
        return BillExtraction(
            amount = amount,
            due_date = date,
            confidence = 0.0,
            missing_fields = missing,
            summary = "${ContentSafety.OFFLINE_LABEL} ${ocrText.take(300)}",
        )
    }
}
