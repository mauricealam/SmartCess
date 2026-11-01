package com.smartcess.app.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reminder extractor must resolve relative dates into an exact local
 * timestamp, default a bare time to its nearest future occurrence, and leave
 * the trigger empty (so the app asks) when no usable time was stated.
 */
class ReminderPromptTest {

    @Test
    fun reminderPrompt_pinsExactTriggerFormat() {
        val instruction = SparkPrompts.reminder("remind me tomorrow at 9").instruction
        assertTrue(instruction.contains("YYYY-MM-DD HH:mm"))
    }

    @Test
    fun reminderPrompt_bareCommandLeavesTitleEmpty() {
        val instruction = SparkPrompts.reminder("remind me").instruction
        assertTrue(instruction.contains("leaves title"))
        assertTrue(instruction.contains("never use the command itself as the title"))
    }

    @Test
    fun reminderPrompt_bareTimeDefaults_dateWithoutTimeAsks() {
        val instruction = SparkPrompts.reminder("remind me at 9").instruction
        assertTrue(instruction.contains("nearest future occurrence"))
        assertTrue(instruction.contains("leave trigger_iso empty"))
    }
}
