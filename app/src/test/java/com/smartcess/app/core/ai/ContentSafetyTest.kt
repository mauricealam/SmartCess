package com.smartcess.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for content-safety rules (spec: no advice, digit masking, crisis response). */
class ContentSafetyTest {

    @Test
    fun longDigitRun_masksAllButLastFour() {
        val masked = ContentSafety.maskSensitiveNumbers("Account 1234567890 due")
        assertFalse(masked.contains("123456"))
        assertTrue(masked.contains("7890"))
    }

    @Test
    fun shortDigitRun_leftAlone() {
        assertEquals("Call 1234", ContentSafety.maskSensitiveNumbers("Call 1234"))
    }

    @Test
    fun medicalAdvice_returnsRefusal() {
        assertEquals(
            ContentSafety.MEDICAL_REFUSAL,
            ContentSafety.sanitizeForSpeech("You should stop taking your pill dose daily"),
        )
    }

    @Test
    fun crisisText_returnsSupportResponse() {
        assertEquals(
            ContentSafety.CRISIS_RESPONSE,
            ContentSafety.sanitizeForSpeech("I want to kill myself"),
        )
    }

    @Test
    fun legalAdvice_returnsRefusal() {
        assertEquals(
            ContentSafety.MEDICAL_REFUSAL,
            ContentSafety.sanitizeForSpeech("You should sue your landlord"),
        )
    }

    @Test
    fun financialAdvice_returnsRefusal() {
        assertEquals(
            ContentSafety.MEDICAL_REFUSAL,
            ContentSafety.sanitizeForSpeech("You should buy stock for a guaranteed return"),
        )
    }

    @Test
    fun plainText_onlyMasked() {
        assertEquals(
            "Bill total 87 ringgit",
            ContentSafety.sanitizeForSpeech("Bill total 87 ringgit"),
        )
    }
}
