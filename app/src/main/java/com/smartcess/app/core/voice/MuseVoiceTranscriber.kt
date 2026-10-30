package com.smartcess.app.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.smartcess.app.core.ai.RateLimitedException
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.config.AiConfig
import com.smartcess.app.core.config.ApiKeyProvider
import com.smartcess.app.core.serialization.SmartCessJson
import com.smartcess.app.core.session.SessionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * DEFERRED (not removed): realtime speech-to-text over WebSocket.
 * Talk currently uses push-to-talk HTTPS upload (TalkRecorder +
 * POST /api/voice/transcribe) because Meta 404s realtime serving from our
 * host's egress. Kept for the live-interim-transcription milestone.
 *
 * Muse Voice Transcribe: realtime speech-to-text over WebSocket.
 * Audio: mono 16-bit PCM @ 24kHz. Mantra: Transcribe listens, Spark thinks.
 *
 * Protocol (dev.meta.ai/docs/api-reference/voice/realtime): connect with a
 * sessionId query param, send a JSON handshake first (credential travels in
 * the handshake, not the HTTP Authorization header), stream raw PCM binary
 * frames, parse JSON transcript events, send {"type":"endStream"} to finish.
 */
sealed interface TranscriptEvent {
    data class Partial(val text: String) : TranscriptEvent
    data class Final(val text: String) : TranscriptEvent
    data class Error(val message: String) : TranscriptEvent
}

/**
 * Parses one server text frame. Returns null for the handshake acknowledgement
 * (no "type" field) and for unrecognized or malformed frames.
 */
internal fun parseServerEvent(text: String): TranscriptEvent? {
    val obj = runCatching {
        SmartCessJson.instance.parseToJsonElement(text).jsonObject
    }.getOrNull() ?: return null
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        null -> null // Handshake acknowledgement; no action needed.
        "transcript" -> {
            val transcript = obj["transcript"]?.jsonPrimitive?.contentOrNull ?: return null
            if (obj["final"]?.jsonPrimitive?.booleanOrNull == true) {
                TranscriptEvent.Final(transcript)
            } else {
                TranscriptEvent.Partial(transcript)
            }
        }
        // Server error text is not surfaced; keep the message generic and safe.
        "error" -> TranscriptEvent.Error("Speech recognition failed. Please type instead.")
        else -> null
    }
}

internal fun handshakeFrame(apiKey: String, model: String): String =
    buildJsonObject {
        putJsonObject("authorization") { put("accessToken", "Bearer $apiKey") }
        put("audioEncoding", "PCM_24KHZ")
        put("model", model)
        put("mode", "PUSH_TO_TALK")
    }.toString()

interface MuseVoiceTranscriber {
    /** Streams mic audio and emits transcript events until [stop] or flow close. */
    fun transcribe(): Flow<TranscriptEvent>
    fun stop()
}

object SttAudioSpec {
    const val SAMPLE_RATE = 24_000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
}

/**
 * Energy-based end-of-utterance detector. Fed the peak amplitude of each
 * mic buffer with a monotonic timestamp; returns true once the utterance
 * is over (speech followed by [endSilenceMs] of quiet), nothing was said
 * within [noSpeechTimeoutMs], or [maxListenMs] elapsed. Thresholds are
 * conservative on purpose: 16-bit peaks under ~700 are room noise on
 * phone mics, while conversational speech peaks in the thousands.
 */
internal class SpeechEndpointDetector(
    private val speechThreshold: Int = 700,
    private val speechConfirmMs: Long = 300,
    private val endSilenceMs: Long = 2000,
    private val noSpeechTimeoutMs: Long = 8000,
    private val maxListenMs: Long = 60000,
) {
    private var startMs = -1L
    private var lastMs = -1L
    private var speechMs = 0L
    private var silenceStartMs = -1L
    private var endpoint = false

    fun onFrame(peak: Int, nowMs: Long): Boolean {
        if (endpoint) return true
        if (startMs < 0) {
            startMs = nowMs
            lastMs = nowMs
        }
        val dt = (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        if (nowMs - startMs >= maxListenMs) {
            endpoint = true
            return true
        }
        if (peak >= speechThreshold) {
            speechMs += dt
            silenceStartMs = -1
        } else if (speechMs >= speechConfirmMs) {
            if (silenceStartMs < 0) silenceStartMs = nowMs
            if (nowMs - silenceStartMs >= endSilenceMs) {
                endpoint = true
                return true
            }
        } else if (nowMs - startMs >= noSpeechTimeoutMs) {
            endpoint = true
            return true
        }
        return false
    }
}

/** Per-stream lifecycle state. One instance per transcribe() call, so one
 *  stream's teardown can never flip another stream's flags on rapid restart. */
internal class StreamState {
    val running = AtomicBoolean(true)
    val endStreamSent = AtomicBoolean(false)

    @Volatile var socket: WebSocket? = null
}

/** Tracks the current live stream for stop(). Identity-checked: a stale
 *  teardown never clears or touches a newer stream. */
internal class StreamTracker {
    @Volatile private var current: StreamState? = null

    fun begin(): StreamState = StreamState().also { current = it }

    fun current(): StreamState? = current

    fun end(state: StreamState) {
        if (current === state) current = null
    }
}

/** Peak absolute sample of 16-bit little-endian PCM in [buf] (first [len] bytes). */
internal fun pcmPeak(buf: ByteArray, len: Int): Int {
    var peak = 0
    var i = 0
    while (i + 1 < len) {
        val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
        val abs = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
        if (abs > peak) peak = abs
        i += 2
    }
    return peak
}

/** Grace period after endStream for final transcripts before forcing close. */
private const val END_STREAM_GRACE_MILLIS = 10_000L
/** Backend relay close code when the session token is rejected. */
private const val BACKEND_AUTH_CLOSE = 4401

@Singleton
class MuseVoiceTranscriberImpl @Inject constructor(
    @Named("backendPlain") private val http: OkHttpClient,
    private val keys: ApiKeyProvider,
    private val config: AiConfig,
    private val sessions: SessionManager,
) : MuseVoiceTranscriber {

    private val streams = StreamTracker()

    override fun transcribe(): Flow<TranscriptEvent> = callbackFlow {
        // Production: backend relay with the session token. Developer override:
        // direct Meta socket when a dev key is stored (hidden dev screen).
        val directKey = keys.getStoredKey()
        val backendToken = if (directKey == null) {
            try {
                sessions.validAccessToken()
            } catch (e: SessionExpiredException) {
                trySend(TranscriptEvent.Error("Please sign in to use voice."))
                close()
                return@callbackFlow
            }
        } else {
            null
        }
        val bufferSize = AudioRecord.getMinBufferSize(
            SttAudioSpec.SAMPLE_RATE, SttAudioSpec.CHANNEL, SttAudioSpec.ENCODING,
        ).coerceAtLeast(4096)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SttAudioSpec.SAMPLE_RATE, SttAudioSpec.CHANNEL,
                SttAudioSpec.ENCODING, bufferSize * 2,
            )
        } catch (e: SecurityException) {
            trySend(TranscriptEvent.Error("Microphone permission is needed to listen."))
            close()
            return@callbackFlow
        }
        val state = streams.begin()
        val sessionId = "stream-${UUID.randomUUID()}"
        val useDirect = directKey != null
        val separator = if (config.sttUrl.contains("?")) "&" else "?"
        val url = if (useDirect) {
            "${config.sttUrl}${separator}sessionId=$sessionId"
        } else {
            "${config.backendBaseUrl}api/voice/realtime?sessionId=$sessionId"
        }
        val builder = Request.Builder().url(url)
        if (backendToken != null) {
            builder.addHeader("Authorization", "Bearer $backendToken")
        }
        state.socket = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Direct Meta path needs the credential handshake first
                // (within 10s per the docs); the backend relay needs none.
                if (useDirect) {
                    webSocket.send(handshakeFrame(directKey!!, config.sttModel))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerEvent(text)?.let { event ->
                    trySend(event)
                    if (event is TranscriptEvent.Error) close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Server closed (normally 1000 after endStream): end the flow,
                // which releases the recorder and socket in awaitClose.
                // 4401 = backend rejected the session mid-stream.
                if (code == BACKEND_AUTH_CLOSE) {
                    trySend(TranscriptEvent.Error("Session expired. Please sign in again."))
                }
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = if (response?.code == 429) {
                    RateLimitedException().message ?: "Speech service is busy."
                } else {
                    "Speech recognition failed. Please type instead."
                }
                trySend(TranscriptEvent.Error(message))
                close()
            }
        })
        record.startRecording()
        val endpoint = SpeechEndpointDetector()
        val pump = Thread {
            val buf = ByteArray(bufferSize)
            while (state.running.get()) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    state.socket?.send(buf.toByteString(0, read))
                    // Auto end-of-utterance: no manual Stop needed.
                    if (endpoint.onFrame(pcmPeak(buf, read), android.os.SystemClock.elapsedRealtime())) {
                        stop()
                    }
                } else if (read < 0) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
        awaitClose {
            state.running.set(false)
            try { pump.join(500) } catch (_: InterruptedException) { }
            runCatching { record.stop() }
            record.release()
            // Final cleanup: end input if stop() was never called, then close.
            runCatching { state.socket?.send("""{"type":"endStream"}""") }
            state.socket?.close(1000, "done")
            state.socket = null
            streams.end(state)
        }
    }

    override fun stop() {
        val state = streams.current() ?: return
        state.running.set(false)
        // endStream goes out once no matter how stop was reached
        // (manual Stop, auto endpoint, cancel) racing on threads.
        if (!state.endStreamSent.compareAndSet(false, true)) return
        val ws = state.socket
        // End input but keep reading: the server sends final transcripts,
        // then closes with 1000 (onClosed ends the flow).
        runCatching { ws?.send("""{"type":"endStream"}""") }
        // Grace close if the server never closes the session itself.
        // ws belongs to this stream only; a newer stream is never touched.
        Thread {
            try {
                Thread.sleep(END_STREAM_GRACE_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            ws?.close(1000, "endstream-timeout")
        }.apply { isDaemon = true; start() }
    }
}
