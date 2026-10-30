package com.smartcess.app.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/** Push-to-talk capture spec: real WAV the Meta endpoint accepts as-is. */
object PttAudioSpec {
    const val SAMPLE_RATE = 16_000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    /** 60 s × 32 KB/s ≈ 1.9 MB, under the 4 MB upload cap. */
    const val MAX_DURATION_MS = 60_000L
    /** 16-bit peaks under ~700 are room noise on phone mics. */
    const val SILENCE_PEAK = 700
}

data class RecordedAudio(val file: File, val peak: Int, val durationMs: Long)

class MicException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface TalkRecorder {
    /** Starts capture on a worker thread. Throws [MicException] without mic access. */
    fun start()
    /** Ends capture, finalizes the WAV, returns the recording. */
    fun stop(): RecordedAudio
    /** Ends capture and deletes the file. */
    fun cancel()
    val isRecording: Boolean
}

@Singleton
class TalkRecorderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : TalkRecorder {

    private val lock = Any()
    private var worker: Thread? = null
    private var record: AudioRecord? = null
    @Volatile private var stopRequested = false
    @Volatile private var result: RecordedAudio? = null
    @Volatile private var failure: MicException? = null
    @Volatile private var cancelled = false
    override val isRecording: Boolean get() = synchronized(lock) { worker?.isAlive == true }

    override fun start() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            stopRequested = false
            cancelled = false
            result = null
            failure = null
            val minBuffer = AudioRecord.getMinBufferSize(
                PttAudioSpec.SAMPLE_RATE, PttAudioSpec.CHANNEL, PttAudioSpec.ENCODING,
            ).coerceAtLeast(4096)
            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    PttAudioSpec.SAMPLE_RATE, PttAudioSpec.CHANNEL,
                    PttAudioSpec.ENCODING, minBuffer * 2,
                )
            } catch (e: SecurityException) {
                throw MicException("Microphone permission is needed to talk.", e)
            } catch (e: IllegalArgumentException) {
                throw MicException("Microphone unavailable.", e)
            }
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw MicException("Microphone unavailable.")
            }
            record = audioRecord
            val file = File(context.cacheDir, "talk-${UUID.randomUUID()}.wav")
            worker = thread(name = "talk-recorder", isDaemon = true) {
                captureLoop(audioRecord, file, minBuffer)
            }
        }
    }

    override fun stop(): RecordedAudio {
        val threadToJoin = synchronized(lock) {
            stopRequested = true
            worker
        } ?: throw MicException("Not recording.")
        threadToJoin.join(5_000)
        synchronized(lock) { worker = null }
        failure?.let { throw it }
        return result ?: throw MicException("Recording failed.")
    }

    override fun cancel() {
        val threadToJoin = synchronized(lock) {
            cancelled = true
            stopRequested = true
            worker
        } ?: return
        threadToJoin.join(5_000)
        synchronized(lock) { worker = null }
    }

    private fun captureLoop(recorder: AudioRecord, file: File, minBuffer: Int) {
        val startedMs = System.currentTimeMillis()
        var peak = 0
        var dataBytes = 0
        try {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                out.write(ByteArray(WavWriter.HEADER_SIZE)) // patched on stop
                recorder.startRecording()
                val shorts = ShortArray(minBuffer / 2)
                while (!stopRequested) {
                    if (System.currentTimeMillis() - startedMs >= PttAudioSpec.MAX_DURATION_MS) break
                    val read = recorder.read(shorts, 0, shorts.size)
                    if (read < 0) break // OVERRUN/INVALID heading; end cleanly
                    if (read == 0) continue
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val sample = shorts[i].toInt()
                        val abs = if (sample < 0) -sample else sample
                        if (abs > peak) peak = abs
                        bytes[i * 2] = (sample and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                    dataBytes += bytes.size
                }
            }
            WavWriter.finalize(file, dataBytes, PttAudioSpec.SAMPLE_RATE)
            if (!cancelled) {
                result = RecordedAudio(file, peak, System.currentTimeMillis() - startedMs)
            }
        } catch (e: SecurityException) {
            failure = MicException("Microphone permission is needed to talk.", e)
        } catch (e: Exception) {
            if (!cancelled) failure = MicException("Recording failed.", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            synchronized(lock) { record = null }
            if (cancelled || failure != null) runCatching { file.delete() }
        }
    }
}
