package com.smartcess.app.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routine extractor must never invent a time: "add my medication
 * routine" (no time said) must yield schedule_rule "DAILY" with no time, so
 * the app asks "what time?" instead of confirming an hour never stated.
 */
class RoutinePromptTest {

    @Test
    fun routinePrompt_forbidsInventedTime() {
        val instruction = SparkPrompts.routine("add my medication routine").instruction
        assertTrue(instruction.contains("\"DAILY\" with no time"))
        assertTrue(instruction.contains("never copy the"))
    }

    @Test
    fun routinePrompt_bareCommandLeavesTitleEmpty() {
        val instruction = SparkPrompts.routine("add routine").instruction
        assertTrue(instruction.contains("leaves title"))
        assertTrue(instruction.contains("never use the command itself as the title"))
    }

    @Test
    fun routinePrompt_keepsStatedTimeFormat() {
        val instruction = SparkPrompts.routine("vitamins every day at 8am").instruction
        assertTrue(instruction.contains("DAILY HH:MM"))
        assertTrue(instruction.contains("\"DAILY 08:00\""))
    }
}
