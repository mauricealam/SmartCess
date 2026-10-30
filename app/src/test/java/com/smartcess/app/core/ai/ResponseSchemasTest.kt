package com.smartcess.app.core.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each response_format schema mirrors its data class: same fields, sane required. */
class ResponseSchemasTest {

    private fun requiredOf(name: String): Set<String> =
        ResponseSchemas.forFeature(name)["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()

    private fun propsOf(name: String): Set<String> =
        ResponseSchemas.forFeature(name)["properties"]!!.jsonObject.keys

    private fun checkContract(name: String, required: Set<String>) {
        val schema = ResponseSchemas.forFeature(name)
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(required, requiredOf(name))
        assertTrue(propsOf(name).containsAll(required))
    }

    @Test
    fun intent_matchesContract() {
        checkContract(
            "intent",
            setOf("intent", "confidence", "missing_information"),
        )
        // Nullable clarifying_question stays optional.
        assertTrue(propsOf("intent").contains("clarifying_question"))
    }

    @Test
    fun bill_matchesContract() {
        checkContract(
            "bill",
            setOf(
                "provider", "amount", "currency", "due_date", "required_action",
                "confidence", "missing_fields", "summary",
            ),
        )
    }

    @Test
    fun comparison_matchesContract() {
        checkContract(
            "comparison",
            setOf(
                "amount_changed", "difference", "percentage_change",
                "possible_reasons", "recommendation", "confidence",
            ),
        )
    }

    @Test
    fun email_matchesContract() {
        checkContract("email", setOf("emails", "briefing"))
        val item = ResponseSchemas.forFeature("email")["properties"]!!
            .jsonObject["emails"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(
            setOf("id", "urgency", "summary"),
            item["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun document_matchesContract() {
        checkContract(
            "document",
            setOf("title", "type", "summary", "key_points", "confidence"),
        )
    }

    @Test
    fun appointment_matchesContract() {
        checkContract("appointment", setOf("title", "start_iso", "confidence"))
    }

    @Test
    fun routine_matchesContract() {
        checkContract(
            "routine",
            setOf("title", "kind", "schedule_rule", "confidence"),
        )
    }

    @Test
    fun briefing_matchesContract() {
        checkContract("briefing", setOf("greeting", "priorities", "briefing"))
        val item = ResponseSchemas.forFeature("briefing")["properties"]!!
            .jsonObject["priorities"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(
            setOf("type", "title", "detail", "urgency", "suggested_action"),
            item["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun unknownFeature_throws() {
        ResponseSchemas.forFeature("nope")
    }
}
