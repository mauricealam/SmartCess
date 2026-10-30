package com.smartcess.app.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The upload container must be a real RIFF/WAVE file Meta accepts as-is. */
class WavWriterTest {

    private fun ascii(bytes: ByteArray, from: Int, len: Int) =
        bytes.copyOfRange(from, from + len).toString(Charsets.US_ASCII)

    @Test
    fun header_hasRiffWaveMagicAndPcmFormat() {
        val header = WavWriter.header(dataBytes = 3200, sampleRate = 16_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
        val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, view.getShort(20).toInt()) // PCM
        assertEquals(1, view.getShort(22).toInt()) // mono
        assertEquals(16_000, view.getInt(24))
        assertEquals(16, view.getShort(34).toInt()) // 16-bit
    }

    @Test
    fun header_sizesMatchPayload() {
        val header = WavWriter.header(dataBytes = 3200, sampleRate = 16_000)
        val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 3200, view.getInt(4)) // RIFF chunk size
        assertEquals(3200, view.getInt(40)) // data chunk size
    }

    @Test
    fun finalize_patchesPlaceholderHeader() {
        val file = kotlin.io.path.createTempFile(suffix = ".wav").toFile()
        try {
            file.writeBytes(ByteArray(WavWriter.HEADER_SIZE) + ByteArray(64))
            WavWriter.finalize(file, dataBytes = 64, sampleRate = 24_000)
            val bytes = file.readBytes()
            assertEquals("RIFF", ascii(bytes, 0, 4))
            assertEquals("data", ascii(bytes, 36, 4))
            val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(24_000, view.getInt(24))
            assertEquals(64, view.getInt(40))
            assertTrue(file.length() == 44L + 64L)
        } finally {
            file.delete()
        }
    }
}
