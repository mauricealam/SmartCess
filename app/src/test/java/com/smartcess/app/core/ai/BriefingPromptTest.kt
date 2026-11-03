package com.smartcess.app.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The briefing input carries the local time so the spoken greeting matches
 * the time of day (never "good morning" at night), identically for the
 * briefing screen and Talk.
 */
class BriefingPromptTest {

    @Test
    fun briefingContext_carriesTimestampAndCounts() {
        val context = SparkPrompts.briefingContext(
            overdue = 1, dueSoon = 2, appointments = 0,
            urgentEmail = 0, documents = 0, routines = 3,
        )
        assertTrue(context.contains("now="))
        assertTrue(context.contains("overdue=1"))
        assertTrue(context.contains("dueSoon=2"))
        assertTrue(context.contains("routines=3"))
    }

    @Test
    fun briefingPrompt_groundsGreetingInLocalTime() {
        val instruction = SparkPrompts.briefing("now=Sunday, 2026-11-01 21:30,overdue=1").instruction
        assertTrue(instruction.contains("actual local time"))
        assertTrue(instruction.contains("never say morning at night"))
    }

    @Test
    fun briefingPrompt_requiresGrammarAndNoRepetition() {
        val instruction = SparkPrompts.briefing("now=Sunday, 2026-11-01 21:30,overdue=1").instruction
        assertTrue(instruction.contains("start by reviewing"))
        assertTrue(instruction.contains("State each fact once"))
    }
}
