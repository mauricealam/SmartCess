package com.smartcess.app.core.ai

import com.smartcess.app.core.config.AiConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meta instruction-message guidance: standing rules in `developer` (highest
 * precedence), untrusted input data in `user` — data must never share a
 * message with the instructions it could override.
 */
class DeveloperRoleTest {

    private fun client(api: SparkApi) = SparkClient(
        api = api,
        config = AiConfig(),
        limiter = RateLimiter(),
        retries = RetryPolicy(),
        cache = ResponseCache(),
        usage = UsageTracker(),
        eval = InMemoryAiEvalLogger(),
    )

    private fun jsonResponse(body: String) = SparkResponse(
        choices = listOf(
            SparkChoice(SparkMessage(role = "assistant", content = JsonPrimitive(body))),
        ),
        usage = SparkUsage(prompt_tokens = 1, completion_tokens = 1),
    )

    private fun textOf(message: SparkMessage): String {
        val content = message.content
        if (content is JsonPrimitive) return content.contentOrNull ?: ""
        return content.jsonArray.joinToString("") { part ->
            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    }

    @Test
    fun classifyIntent_sendsDeveloperInstructionPlusUserData(): Unit = runBlocking {
        var seen: SparkRequest? = null
        val api = object : SparkApi {
            override suspend fun complete(request: SparkRequest): SparkResponse {
                seen = request
                return jsonResponse("""{"intent":"scan_bill","confidence":0.9}""")
            }
        }
        val result = client(api).classifyIntent("scan my bill")
        assertEquals("scan_bill", result.intent)
        val messages = seen!!.messages
        assertEquals(2, messages.size)
        assertEquals("developer", messages[0].role)
        assertEquals("user", messages[1].role)
        assertTrue(textOf(messages[0]).contains("Classify the voice command"))
        assertTrue(textOf(messages[1]).contains("scan my bill"))
    }

    @Test
    fun extractBill_imageStaysInUserMessage(): Unit = runBlocking {
        var seen: SparkRequest? = null
        val api = object : SparkApi {
            override suspend fun complete(request: SparkRequest): SparkResponse {
                seen = request
                return jsonResponse("""{"provider":"TNB","confidence":0.9}""")
            }
        }
        val result = client(api).extractBill("QUJD", ocrHint = "TNB")
        assertEquals("TNB", result.provider)
        val messages = seen!!.messages
        assertEquals(2, messages.size)
        assertEquals("developer", messages[0].role)
        assertEquals("user", messages[1].role)
        assertTrue(textOf(messages[0]).contains("Extract the bill"))
        assertTrue(textOf(messages[1]).contains("TNB"))
        val imagePart = messages[1].content.jsonArray.first { part ->
            part.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image_url"
        }
        assertTrue(
            imagePart.jsonObject["image_url"]!!.jsonObject["url"]!!
                .jsonPrimitive.content.startsWith("data:image/jpeg;base64,"),
        )
    }

    @Test
    fun invalidJson_retryKeepsRolesAndAmendsInstruction(): Unit = runBlocking {
        val seen = mutableListOf<SparkRequest>()
        val api = object : SparkApi {
            override suspend fun complete(request: SparkRequest): SparkResponse {
                seen.add(request)
                if (seen.size == 1) return jsonResponse("not json")
                return jsonResponse("""{"intent":"general_question","confidence":0.5}""")
            }
        }
        client(api).classifyIntent("hello")
        assertEquals(2, seen.size)
        val retry = seen[1].messages
        assertEquals("developer", retry[0].role)
        assertEquals("user", retry[1].role)
        assertTrue(textOf(retry[0]).contains("Return valid JSON only."))
    }
}
