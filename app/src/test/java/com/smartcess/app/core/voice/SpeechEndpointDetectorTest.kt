package com.smartcess.app.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-of-utterance detector: speech-then-silence, no-speech, and max caps. */
class SpeechEndpointDetectorTest {

    @Test
    fun speechThenSilence_endpoints() {
        val detector = SpeechEndpointDetector()
        assertFalse(detector.onFrame(100, 0))
        assertFalse(detector.onFrame(5000, 100))
        assertFalse(detector.onFrame(5000, 200))
        assertFalse(detector.onFrame(5000, 400))
        assertFalse(detector.onFrame(100, 500))
        assertFalse(detector.onFrame(100, 2400))
        assertTrue(detector.onFrame(100, 2500))
    }

    @Test
    fun quietRoom_timesOutAsNoSpeech() {
        val detector = SpeechEndpointDetector()
        assertFalse(detector.onFrame(100, 0))
        assertFalse(detector.onFrame(100, 100))
        assertFalse(detector.onFrame(100, 7999))
        assertTrue(detector.onFrame(100, 8000))
    }

    @Test
    fun pauseThenSpeech_resetsSilenceClock() {
        val detector = SpeechEndpointDetector()
        assertFalse(detector.onFrame(5000, 0))
        assertFalse(detector.onFrame(5000, 300))
        assertFalse(detector.onFrame(100, 400))
        assertFalse(detector.onFrame(100, 1900))
        assertFalse(detector.onFrame(5000, 1950))
        assertFalse(detector.onFrame(100, 2000))
        assertFalse(detector.onFrame(100, 3900))
        assertTrue(detector.onFrame(100, 4000))
    }

    @Test
    fun maxListen_capsLongSpeech() {
        val detector = SpeechEndpointDetector(maxListenMs = 1000)
        assertFalse(detector.onFrame(5000, 0))
        assertFalse(detector.onFrame(5000, 999))
        assertTrue(detector.onFrame(5000, 1000))
    }

    @Test
    fun endpoint_staysLatched() {
        val detector = SpeechEndpointDetector(maxListenMs = 100)
        assertFalse(detector.onFrame(100, 0))
        assertTrue(detector.onFrame(100, 100))
        assertTrue(detector.onFrame(5000, 200))
    }

    @Test
    fun pcmPeak_readsLittleEndianSamples() {
        assertEquals(0, pcmPeak(byteArrayOf(), 0))
        assertEquals(0, pcmPeak(byteArrayOf(0x00, 0x00), 2))
        assertEquals(32767, pcmPeak(byteArrayOf(0xFF.toByte(), 0x7F), 2))
        assertEquals(1, pcmPeak(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 2))
        assertEquals(32767, pcmPeak(byteArrayOf(0x00, 0x80.toByte()), 2))
        // Trailing odd byte is ignored; len bounds the scan.
        assertEquals(256, pcmPeak(byteArrayOf(0x00, 0x01, 0x7F), 2))
    }
}
