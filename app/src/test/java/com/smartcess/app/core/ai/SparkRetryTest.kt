package com.smartcess.app.core.ai

import com.smartcess.app.core.config.AiConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Transport failures must surface as [AiException] so [RetryPolicy] engages:
 * raw retrofit errors would bypass retries and fail instantly.
 */
class SparkRetryTest {

    private fun client(api: SparkApi) = SparkClient(
        api = api,
        config = AiConfig(),
        limiter = RateLimiter(),
        retries = RetryPolicy(),
        cache = ResponseCache(),
        usage = UsageTracker(),
        eval = InMemoryAiEvalLogger(),
    )

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<SparkResponse>(code, "err".toResponseBody("text/plain".toMediaType())))

    private fun intentResponse(intent: String) = SparkResponse(
        choices = listOf(
            SparkChoice(
                SparkMessage(
                    role = "assistant",
                    content = JsonPrimitive("""{"intent":"$intent","confidence":0.9}"""),
                ),
            ),
        ),
        usage = SparkUsage(prompt_tokens = 1, completion_tokens = 1),
    )

    @Test
    fun backend502sThenSucceeds_retriesAndParses(): Unit = runBlocking {
        var calls = 0
        val api = object : SparkApi {
            override suspend fun complete(request: SparkRequest): SparkResponse {
                calls++
                if (calls < 3) throw httpError(502)
                return intentResponse("general_question")
            }
        }
        val result = client(api).classifyIntent("hello")
        assertEquals("general_question", result.intent)
        assertEquals(3, calls)
    }

    @Test
    fun backendAlways502_throwsAiException(): Unit = runBlocking {
        var calls = 0
        val api = object : SparkApi {
            override suspend fun complete(request: SparkRequest): SparkResponse {
                calls++
                throw httpError(502)
            }
        }
        try {
            client(api).classifyIntent("hello")
            throw AssertionError("expected AiException")
        } catch (e: AiException) {
            // Initial try + 2 transient retries.
            assertEquals(3, calls)
        }
    }
}
