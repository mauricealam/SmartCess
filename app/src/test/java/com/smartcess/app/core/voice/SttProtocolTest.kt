package com.smartcess.app.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Meta realtime STT protocol frames (dev.meta.ai/docs). */
class SttProtocolTest {

    @Test
    fun handshakeAck_withoutType_isIgnored() {
        assertNull(parseServerEvent("""{"sessionId":"stream-123"}"""))
    }

    @Test
    fun interimTranscript_returnsPartial() {
        val event = parseServerEvent(
            """{"type":"transcript","transcript":"hello","final":false}""",
        )
        assertTrue(event is TranscriptEvent.Partial)
        assertEquals("hello", (event as TranscriptEvent.Partial).text)
    }

    @Test
    fun finalTranscript_returnsFinal() {
        val event = parseServerEvent(
            """{"type":"transcript","transcript":"hello world","final":true}""",
        )
        assertTrue(event is TranscriptEvent.Final)
        assertEquals("hello world", (event as TranscriptEvent.Final).text)
    }

    @Test
    fun errorEvent_returnsGenericMessage() {
        val event = parseServerEvent("""{"type":"error","message":"quota hit upstream"}""")
        assertTrue(event is TranscriptEvent.Error)
        // Server error text is never surfaced verbatim.
        assertTrue(!(event as TranscriptEvent.Error).message.contains("quota"))
    }

    @Test
    fun malformedFrame_returnsNull() {
        assertNull(parseServerEvent("final:not json"))
        assertNull(parseServerEvent("""{"type":"transcript"}"""))
        assertNull(parseServerEvent(""))
    }

    @Test
    fun handshakeFrame_containsCredentialAndModel() {
        val frame = handshakeFrame("TEST_KEY", "muse-voice-transcribe-1.0")
        assertTrue(frame.contains("Bearer TEST_KEY"))
        assertTrue(frame.contains("PCM_24KHZ"))
        assertTrue(frame.contains("muse-voice-transcribe-1.0"))
        assertTrue(frame.contains("PUSH_TO_TALK"))
    }
}
