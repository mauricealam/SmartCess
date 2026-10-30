package com.smartcess.app.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

// Chat-completions style wire format with response_format + tool calling.

@Serializable
data class SparkMessage(
    val role: String,
    val content: JsonElement,
    val tool_calls: List<SparkToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
)

@Serializable
data class SparkToolCall(
    val id: String = "",
    val type: String = "function",
    val function: SparkFunctionCall = SparkFunctionCall(),
)

@Serializable
data class SparkFunctionCall(
    val name: String = "",
    val arguments: String = "{}",
)

@Serializable
data class SparkRequest(
    val model: String,
    val messages: List<SparkMessage>,
    val response_format: JsonObject? = null,
    val tools: List<JsonObject>? = null,
    val tool_choice: String? = null,
    // 4096: Spark reasons (~1k tokens) inside the same budget as the
    // constrained output; 1024 starves schema calls into length cutoffs.
    val max_tokens: Int = 4096,
    val temperature: Double = 0.2,
)

@Serializable
data class SparkChoice(
    val message: SparkMessage,
    val finish_reason: String? = null,
)

@Serializable
data class SparkUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
)

@Serializable
data class SparkResponse(
    val choices: List<SparkChoice> = emptyList(),
    val usage: SparkUsage? = null,
)

interface SparkApi {
    @POST("chat/completions")
    suspend fun complete(@Body request: SparkRequest): SparkResponse
}
