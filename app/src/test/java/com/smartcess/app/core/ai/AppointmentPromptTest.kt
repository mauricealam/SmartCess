package com.smartcess.app.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appointment extractor must resolve relative dates ("tomorrow at 9am")
 * into an exact local timestamp — never empty them out (which made Talk ask
 * "what day and time?" for details the user already gave).
 */
class AppointmentPromptTest {

    @Test
    fun appointmentPrompt_pinsExactDateFormat() {
        val instruction = SparkPrompts.appointment("dentist tomorrow at 9am").instruction
        assertTrue(instruction.contains("YYYY-MM-DD HH:mm"))
    }

    @Test
    fun appointmentPrompt_bareCommandLeavesTitleEmpty() {
        val instruction = SparkPrompts.appointment("add appointment").instruction
        assertTrue(instruction.contains("leaves title"))
        assertTrue(instruction.contains("never use the command itself as the title"))
    }

    @Test
    fun appointmentPrompt_requiresRelativeDateResolution() {
        val instruction = SparkPrompts.appointment("dentist tomorrow at 9am").instruction
        assertTrue(instruction.contains("Today is"))
        assertTrue(instruction.contains("tomorrow"))
        assertTrue(instruction.contains("2026-11-02 09:00"))
        assertTrue(instruction.contains("not\ninventing") || instruction.contains("not inventing"))
    }
}
