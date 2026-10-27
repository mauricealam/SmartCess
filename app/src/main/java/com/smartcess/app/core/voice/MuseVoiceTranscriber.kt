package com.smartcess.app.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.smartcess.app.core.ai.RateLimitedException
import com.smartcess.app.core.config.AiConfig
import com.smartcess.app.core.config.ApiKeyProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Muse Voice Transcribe: realtime speech-to-text over WebSocket.
 * Audio: mono 16-bit PCM @ 24kHz. Mantra: Transcribe listens, Spark thinks.
 */
sealed interface TranscriptEvent {
    data class Partial(val text: String) : TranscriptEvent
    data class Final(val text: String) : TranscriptEvent
    data class Error(val message: String) : TranscriptEvent
}

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
        val request = Request.Builder()
            .url(config.sttUrl)
            .addHeader("Authorization", "Bearer $key")
            .build()
        running = true
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Minimal protocol: "final:..." vs partial chunk. Server format may refine this.
                if (text.startsWith("final:")) trySend(TranscriptEvent.Final(text.removePrefix("final:")))
                else trySend(TranscriptEvent.Partial(text))
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
            socket?.close(1000, "done")
            socket = null
            recorder = null
        }
    }

    override fun stop() {
        running = false
        socket?.close(1000, "stop")
    }
}
