package com.smartcess.app.core.voice

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE writer: mono 16-bit PCM at 16 or 24 kHz, exactly what
 * Meta's file-transcription endpoint accepts (dev.meta.ai/docs/speech-to-text).
 * Pure byte math (no Android APIs) so unit tests verify the container.
 */
object WavWriter {
    const val HEADER_SIZE = 44

    fun header(dataBytes: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        require(dataBytes >= 0)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    /** Overwrites the placeholder header with final sizes after recording. */
    fun finalize(file: java.io.File, dataBytes: Int, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(dataBytes, sampleRate))
        }
    }
}
