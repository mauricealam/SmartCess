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
    /** Voice that must accumulate before trailing silence can end capture. */
    const val SPEECH_CONFIRM_MS = 300L
    /** Trailing silence after confirmed speech that ends capture by itself. */
    const val END_SILENCE_MS = 1_200L
    /** Cutoff when nothing was said at all; slow starters keep time to begin. */
    const val NO_SPEECH_TIMEOUT_MS = 6_000L
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
    /** True once capture ended itself on trailing silence (see [PttAudioSpec]). */
    val autoStopped: Boolean
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
    @Volatile private var autoStop = false
    override val isRecording: Boolean get() = synchronized(lock) { worker?.isAlive == true }
    override val autoStopped: Boolean get() = autoStop

    override fun start() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            stopRequested = false
            cancelled = false
            result = null
            failure = null
            autoStop = false
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
                // Speech-aware endpointing: capture ends on trailing silence
                // once real voice was heard — no fixed minimum wait, so short
                // commands submit ~3s sooner. Slow starters are protected by
                // the no-speech timeout instead. The WAV finalizes normally.
                val endpoint = SpeechEndpointDetector(
                    speechThreshold = PttAudioSpec.SILENCE_PEAK,
                    speechConfirmMs = PttAudioSpec.SPEECH_CONFIRM_MS,
                    endSilenceMs = PttAudioSpec.END_SILENCE_MS,
                    noSpeechTimeoutMs = PttAudioSpec.NO_SPEECH_TIMEOUT_MS,
                    maxListenMs = PttAudioSpec.MAX_DURATION_MS,
                )
                while (!stopRequested) {
                    val nowMs = System.currentTimeMillis()
                    val read = recorder.read(shorts, 0, shorts.size)
                    if (read < 0) break // OVERRUN/INVALID heading; end cleanly
                    if (read == 0) continue
                    val bytes = ByteArray(read * 2)
                    var chunkPeak = 0
                    for (i in 0 until read) {
                        val sample = shorts[i].toInt()
                        val abs = if (sample < 0) -sample else sample
                        if (abs > peak) peak = abs
                        if (abs > chunkPeak) chunkPeak = abs
                        bytes[i * 2] = (sample and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                    dataBytes += bytes.size
                    if (endpoint.onFrame(chunkPeak, nowMs)) {
                        autoStop = true
                        break
                    }
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
