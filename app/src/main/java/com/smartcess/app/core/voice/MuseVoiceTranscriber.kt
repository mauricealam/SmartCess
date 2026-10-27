package com.smartcess.app.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.smartcess.app.core.ai.RateLimitedException
import com.smartcess.app.core.config.AiConfig
import com.smartcess.app.core.config.ApiKeyProvider
import com.smartcess.app.core.serialization.SmartCessJson
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
import okio.ByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
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

/** Grace period after endStream for final transcripts before forcing close. */
private const val END_STREAM_GRACE_MILLIS = 10_000L

@Singleton
class MuseVoiceTranscriberImpl @Inject constructor(
    private val http: OkHttpClient,
    private val keys: ApiKeyProvider,
    private val config: AiConfig,
) : MuseVoiceTranscriber {

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var running = false

    override fun transcribe(): Flow<TranscriptEvent> = callbackFlow {
        val key = keys.getMetaApiKey()
        if (key.isNullOrBlank()) {
            trySend(TranscriptEvent.Error("No API key. Offline mode: please type instead."))
            close()
            return@callbackFlow
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
        recorder = record
        val sessionId = "stream-${UUID.randomUUID()}"
        val separator = if (config.sttUrl.contains("?")) "&" else "?"
        val request = Request.Builder()
            .url("${config.sttUrl}${separator}sessionId=$sessionId")
            .build()
        running = true
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Handshake must be the first frame (within 10s per the docs).
                webSocket.send(handshakeFrame(key, config.sttModel))
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
        val pump = Thread {
            val buf = ByteArray(bufferSize)
            while (running) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    socket?.send(ByteString.of(*buf.copyOf(read)))
                } else if (read < 0) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
        awaitClose {
            running = false
            try { pump.join(500) } catch (_: InterruptedException) { }
            runCatching { record.stop() }
            record.release()
            // Final cleanup: end input if stop() was never called, then close.
            runCatching { socket?.send("""{"type":"endStream"}""") }
            socket?.close(1000, "done")
            socket = null
            recorder = null
        }
    }

    override fun stop() {
        running = false
        val ws = socket
        // End input but keep reading: the server sends final transcripts,
        // then closes with 1000 (onClosed ends the flow).
        runCatching { ws?.send("""{"type":"endStream"}""") }
        // Grace close if the server never closes the session itself.
        Thread {
            try {
                Thread.sleep(END_STREAM_GRACE_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (socket === ws) ws?.close(1000, "endstream-timeout")
        }.apply { isDaemon = true; start() }
    }
}
