package com.smartcess.app.core.ai

import com.smartcess.app.core.config.AiConfig
import com.smartcess.app.core.serialization.SmartCessJson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Muse Spark client: intent, multimodal bill/doc understanding, extraction,
 * comparison, email briefing, appointment/routine handling, daily briefing,
 * and tool calling. Retries, rate limits, cache, fallback, and eval hooks included.
 * Errors are sanitized; images/text/keys are never logged.
 */
@Singleton
class SparkClient @Inject constructor(
    private val api: SparkApi,
    private val config: AiConfig,
    private val limiter: RateLimiter,
    private val retries: RetryPolicy,
    private val cache: ResponseCache,
    private val usage: UsageTracker,
    private val eval: AiEvalLogger,
) {
    private val json = SmartCessJson.instance
    private val route get() = ModelFallback.route(config.sparkModel, config.sparkFallbackModel)

    // Public API

    suspend fun classifyIntent(text: String): IntentResult =
        completeJson(
            feature = "intent",
            schemaName = "intent",
            prompt = SparkPrompts.intent(text),
            timeoutMillis = config.intentTimeoutMillis,
            parser = { raw -> json.decodeFromString(IntentResult.serializer(), raw) },
            fallback = { IntentResult(intent = "general_question", confidence = 0.0) },
        )

    suspend fun extractBill(imageBase64: String, ocrHint: String? = null): BillExtraction =
        completeJson(
            feature = "bill_extract",
            schemaName = "bill",
            prompt = SparkPrompts.BILL + (ocrHint?.let { "\nOCR hint: $it" } ?: ""),
            imageBase64 = imageBase64,
            timeoutMillis = config.extractionTimeoutMillis,
            parser = { raw -> json.decodeFromString(BillExtraction.serializer(), raw) },
            fallback = { BillExtraction(confidence = 0.0, missing_fields = listOf("all")) },
        )

    suspend fun compareBills(currentJson: String, previousJson: String): ComparisonResult =
        completeJson(
            feature = "bill_compare",
            schemaName = "comparison",
            prompt = SparkPrompts.compare(currentJson, previousJson),
            timeoutMillis = config.extractionTimeoutMillis,
            parser = { raw -> json.decodeFromString(ComparisonResult.serializer(), raw) },
            fallback = { ComparisonResult(confidence = 0.0) },
        )

    suspend fun briefEmails(emailsJson: String): EmailBriefing =
        completeJson(
            feature = "email_briefing",
            schemaName = "email",
            prompt = SparkPrompts.emailBriefing(emailsJson),
            timeoutMillis = config.briefingTimeoutMillis,
            parser = { raw -> json.decodeFromString(EmailBriefing.serializer(), raw) },
            fallback = { EmailBriefing() },
        )

    suspend fun summarizeDocument(text: String?, imageBase64: String?): DocumentSummary =
        completeJson(
            feature = "doc_summary",
            schemaName = "document",
            prompt = SparkPrompts.DOCUMENT + (text?.let { "\nText: $it" } ?: ""),
            imageBase64 = imageBase64,
            timeoutMillis = config.documentTimeoutMillis,
            parser = { raw -> json.decodeFromString(DocumentSummary.serializer(), raw) },
            fallback = { DocumentSummary(confidence = 0.0) },
        )

    suspend fun extractAppointment(text: String): AppointmentExtraction =
        completeJson(
            feature = "appointment",
            schemaName = "appointment",
            prompt = SparkPrompts.appointment(text),
            timeoutMillis = config.extractionTimeoutMillis,
            parser = { raw -> json.decodeFromString(AppointmentExtraction.serializer(), raw) },
            fallback = { AppointmentExtraction(confidence = 0.0) },
        )

    suspend fun routineHelp(text: String): RoutineHelp =
        completeJson(
            feature = "routine",
            schemaName = "routine",
            prompt = SparkPrompts.routine(text),
            timeoutMillis = config.extractionTimeoutMillis,
            parser = { raw -> json.decodeFromString(RoutineHelp.serializer(), raw) },
            fallback = { RoutineHelp(confidence = 0.0) },
        )

    suspend fun dailyBriefing(todayDataJson: String): BriefingResult =
        completeJson(
            feature = "briefing",
            schemaName = "briefing",
            prompt = SparkPrompts.briefing(todayDataJson),
            timeoutMillis = config.briefingTimeoutMillis,
            parser = { raw -> json.decodeFromString(BriefingResult.serializer(), raw) },
            fallback = { BriefingResult() },
        )

    /**
     * Tool-calling loop (max 2 rounds). Read-only tools run directly; approval-gated
     * tools go through [gate]; banned actions always throw.
     */
    suspend fun chatWithTools(
        messages: List<SparkMessage>,
        executor: ToolExecutor,
        gate: ApprovalGate,
        maxRounds: Int = 2,
    ): String = limiter.guard {
        val working = messages.toMutableList()
        repeat(maxRounds) {
            val response = callWithFallback(
                SparkRequest(
                    model = config.sparkModel,
                    messages = working,
                    tools = ToolRegistry.toWireFormat(),
                    tool_choice = "auto",
                ),
                config.briefingTimeoutMillis,
            )
            val message = response.choices.firstOrNull()?.message
                ?: throw InvalidAiResponseException()
            val calls = message.tool_calls.orEmpty()
            if (calls.isEmpty()) return@guard contentText(message.content)
            working += message
            calls.forEach { call ->
                val name = call.function.name
                if (ToolRegistry.isBanned(name)) throw AiException("That action is not allowed.")
                val def = ToolRegistry.find(name) ?: throw AiException("Unknown assistant action.")
                if (def.requiresApproval && !gate.requestApproval(name, call.function.arguments)) {
                    throw AiException("Action cancelled. Nothing was changed.")
                }
                val output = executor.execute(name, call.function.arguments)
                working += SparkMessage(
                    role = "tool",
                    content = JsonPrimitive(output),
                    tool_call_id = call.id,
                    name = name,
                )
            }
        }
        val final = callWithFallback(
            SparkRequest(model = config.sparkModel, messages = working),
            config.briefingTimeoutMillis,
        )
        contentText(final.choices.firstOrNull()?.message?.content ?: JsonNull)
    }

    // Internals

    private suspend fun <T> completeJson(
        feature: String,
        schemaName: String,
        prompt: String,
        timeoutMillis: Long,
        parser: (String) -> T,
        fallback: () -> T,
        imageBase64: String? = null,
    ): T = limiter.guard {
        val cacheKey = cache.key(feature, schemaName, prompt, imageBase64?.take(64) ?: "")
        cache.get(cacheKey)?.let { cached ->
            return@guard runCatching { parser(cached) }.getOrElse { fallback() }
        }
        val started = System.currentTimeMillis()
        var success = false
        try {
            val raw = retries.withRetry(
                transientRetries = config.transientMaxRetries,
                timeoutRetries = config.timeoutMaxRetries,
            ) {
                val response = callWithFallback(
                    SparkRequest(
                        model = config.sparkModel,
                        messages = listOf(userMessage(prompt, imageBase64)),
                        response_format = ToolRegistry.jsonSchemaFormat(schemaName),
                    ),
                    timeoutMillis,
                )
                val content = response.choices.firstOrNull()?.message?.content
                    ?: throw InvalidAiResponseException()
                contentText(content)
            }
            // Invalid JSON: retry once, then safe default + warning (spec).
            val parsed = runCatching { parser(raw) }.getOrNull()
                ?: runCatching {
                    val retry = callWithFallback(
                        SparkRequest(
                            model = config.sparkModel,
                            messages = listOf(userMessage("$prompt\nReturn valid JSON only.", imageBase64)),
                            response_format = ToolRegistry.jsonSchemaFormat(schemaName),
                        ),
                        timeoutMillis,
                    )
                    parser(contentText(retry.choices.firstOrNull()?.message?.content ?: JsonNull))
                }.getOrElse { throw InvalidAiResponseException() }
            cache.put(cacheKey, raw)
            success = true
            parsed
        } finally {
            val latency = System.currentTimeMillis() - started
            usage.record(feature, latency, success)
            eval.log(EvalSample(feature, SparkPrompts.VERSION, success, latency))
        }
    }

    private suspend fun callWithFallback(request: SparkRequest, timeoutMillis: Long): SparkResponse {
        var attempt = 0
        var lastError: Exception? = null
        while (true) {
            val model = ModelFallback.modelForAttempt(route, attempt) ?: throw (
                lastError ?: AiException("The AI service is unavailable.")
                )
            try {
                return withTimeout(timeoutMillis) { api.complete(request.copy(model = model)) }
            } catch (e: TimeoutCancellationException) {
                lastError = TimeoutException(e.message)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) throw RateLimitedException()
                if (e.code() !in 500..599) throw AiException("The AI request failed. Please try again.", e)
                lastError = e
            }
            if (++attempt > 1) throw AiException("The AI service is unavailable.", lastError)
        }
    }

    private fun userMessage(prompt: String, imageBase64: String?): SparkMessage {
        val content: JsonElement = if (imageBase64 == null) {
            JsonPrimitive(prompt)
        } else {
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,$imageBase64")
                    })
                })
            }
        }
        return SparkMessage(role = "user", content = content)
    }

    private fun contentText(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull ?: ""
        is JsonArray -> content.jsonArray.joinToString("") { part ->
            val obj = runCatching { part.jsonObject }.getOrNull()
            obj?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        }
        else -> ""
    }
}
