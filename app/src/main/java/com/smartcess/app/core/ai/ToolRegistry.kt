package com.smartcess.app.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool definitions + JSON-schema formats. The schema formats drive structured
 * output today; the tool list is tested data for the agent roadmap (no caller
 * yet, so no executor/approval-gate wiring exists). Reads run without approval;
 * saves/reminders/calendar will need explicit confirmation when wired.
 * Banned actions can never run.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val requiresApproval: Boolean,
)

object ToolRegistry {
    /** Actions the app must never perform automatically (also blocked from tools). */
    val bannedActions = setOf("pay_bill", "send_email", "submit_form", "navigate", "make_call", "call")

    private fun params(vararg props: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (name, type) ->
                put(name, buildJsonObject { put("type", type) })
            }
        })
    }

    val readOnlyTools = listOf(
        ToolDefinition("get_today_data", "Read today's briefing snapshot.", params(), false),
        ToolDefinition("get_bill_by_provider", "Look up bills by provider.", params("provider" to "string"), false),
        ToolDefinition("get_bill_by_id", "Look up one bill by id.", params("id" to "integer"), false),
    )

    val approvalGatedTools = listOf(
        ToolDefinition("save_bill", "Save an extracted bill.", params("bill" to "object"), true),
        ToolDefinition("create_reminder", "Create a reminder.", params("reminder" to "object"), true),
        ToolDefinition("cancel_reminder", "Cancel a reminder.", params("id" to "integer"), true),
        ToolDefinition("archive_bill", "Archive a bill.", params("id" to "integer"), true),
        ToolDefinition("save_document_summary", "Save a document summary.", params("document" to "object"), true),
        ToolDefinition("create_appointment", "Create an appointment.", params("appointment" to "object"), true),
        ToolDefinition("create_routine", "Create a routine.", params("routine" to "object"), true),
        ToolDefinition("complete_routine", "Mark a routine complete.", params("id" to "integer"), true),
        ToolDefinition("ask_user_for_confirmation", "Ask the user to confirm a pending action.", params("question" to "string"), true),
    )

    val all: List<ToolDefinition> = readOnlyTools + approvalGatedTools

    fun find(name: String): ToolDefinition? = all.firstOrNull { it.name == name }

    fun isBanned(name: String): Boolean = name in bannedActions

    fun toWireFormat(): List<JsonObject> = all.map { tool ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            })
        }
    }

}
