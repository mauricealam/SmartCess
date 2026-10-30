package com.smartcess.app.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * JSON Schemas for Meta structured output (`response_format`), one per
 * feature. Each schema mirrors its [SparkSchemas] data class exactly:
 * `required` lists every non-nullable field (nullable fields stay optional
 * so missing keys fall back to defaults), and enums are used only where the
 * spec fixes the value set (intent, urgency, briefing type).
 */
object ResponseSchemas {

    private fun prop(type: String): JsonObject =
        buildJsonObject { put("type", JsonPrimitive(type)) }

    private fun enumProp(vararg values: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private fun strList(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
    }

    private fun objList(item: JsonObject): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", item)
    }

    private fun obj(vararg props: Pair<String, JsonObject>, required: List<String>): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { props.forEach { (key, value) -> put(key, value) } })
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }

    private val urgencies = arrayOf("low", "normal", "high", "urgent")

    private val intents = arrayOf(
        "daily_briefing",
        "scan_bill",
        "read_document",
        "email_briefing",
        "ask_bill_question",
        "create_reminder",
        "cancel_reminder",
        "archive_bill",
        "add_appointment",
        "add_routine",
        "general_question",
    )

    private val emailBrief = obj(
        "id" to prop("string"),
        "urgency" to enumProp(*urgencies),
        "summary" to prop("string"),
        "deadline" to prop("string"),
        required = listOf("id", "urgency", "summary"),
    )

    private val briefingPriority = obj(
        "type" to enumProp("email", "bill", "appointment", "routine", "info"),
        "title" to prop("string"),
        "detail" to prop("string"),
        "urgency" to enumProp(*urgencies),
        "suggested_action" to prop("string"),
        "reference_id" to prop("string"),
        required = listOf("type", "title", "detail", "urgency", "suggested_action"),
    )

    fun forFeature(name: String): JsonObject = when (name) {
        "intent" -> obj(
            "intent" to enumProp(*intents),
            "confidence" to prop("number"),
            "missing_information" to strList(),
            "clarifying_question" to prop("string"),
            required = listOf("intent", "confidence", "missing_information"),
        )
        "bill" -> obj(
            "provider" to prop("string"),
            "amount" to prop("number"),
            "currency" to prop("string"),
            "due_date" to prop("string"),
            "account_reference" to prop("string"),
            "billing_period_start" to prop("string"),
            "billing_period_end" to prop("string"),
            "required_action" to prop("string"),
            "confidence" to prop("number"),
            "missing_fields" to strList(),
            "summary" to prop("string"),
            required = listOf(
                "provider", "amount", "currency", "due_date", "required_action",
                "confidence", "missing_fields", "summary",
            ),
        )
        "comparison" -> obj(
            "amount_changed" to prop("boolean"),
            "difference" to prop("number"),
            "percentage_change" to prop("number"),
            "possible_reasons" to strList(),
            "recommendation" to prop("string"),
            "confidence" to prop("number"),
            required = listOf(
                "amount_changed", "difference", "percentage_change",
                "possible_reasons", "recommendation", "confidence",
            ),
        )
        "email" -> obj(
            "emails" to objList(emailBrief),
            "briefing" to prop("string"),
            required = listOf("emails", "briefing"),
        )
        "document" -> obj(
            "title" to prop("string"),
            "type" to prop("string"),
            "summary" to prop("string"),
            "key_points" to strList(),
            "deadline" to prop("string"),
            "amount" to prop("number"),
            "required_action" to prop("string"),
            "confidence" to prop("number"),
            required = listOf("title", "type", "summary", "key_points", "confidence"),
        )
        "appointment" -> obj(
            "title" to prop("string"),
            "provider" to prop("string"),
            "location" to prop("string"),
            "start_iso" to prop("string"),
            "end_iso" to prop("string"),
            "doctor" to prop("string"),
            "prep_notes" to prop("string"),
            "confidence" to prop("number"),
            required = listOf("title", "start_iso", "confidence"),
        )
        "routine" -> obj(
            "title" to prop("string"),
            "kind" to prop("string"),
            "schedule_rule" to prop("string"),
            "notes" to prop("string"),
            "confidence" to prop("number"),
            required = listOf("title", "kind", "schedule_rule", "confidence"),
        )
        "briefing" -> obj(
            "greeting" to prop("string"),
            "priorities" to objList(briefingPriority),
            "briefing" to prop("string"),
            required = listOf("greeting", "priorities", "briefing"),
        )
        else -> error("Unknown schema: $name")
    }
}
