package com.smartcess.app.core.ai

import com.smartcess.app.core.config.AiConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** H1: images sharing a base64 prefix must not collide in the response cache. */
class SparkCacheKeyTest {

    private class CountingApi : SparkApi {
        var calls = 0
        override suspend fun complete(request: SparkRequest): SparkResponse {
            calls++
            return SparkResponse(
                choices = listOf(
                    SparkChoice(SparkMessage(role = "assistant", content = JsonPrimitive(BILL_JSON))),
                ),
            )
        }
    }

    @Test
    fun extractBill_samePrefixDifferentImage_noCacheCollision(): Unit = runBlocking {
        val api = CountingApi()
        val client = SparkClient(
            api, AiConfig(), RateLimiter(), RetryPolicy(), ResponseCache(),
            UsageTracker(), InMemoryAiEvalLogger(),
        )
        val prefix = "A".repeat(64)
        client.extractBill(prefix + "IMAGE_ONE_DIFFERENT_SUFFIX_111")
        client.extractBill(prefix + "IMAGE_TWO_DIFFERENT_SUFFIX_222")
        assertEquals(2, api.calls)
    }

    companion object {
        private const val BILL_JSON =
            """{"provider":"TNB","amount":120.5,"currency":"MYR","due_date":"2026-11-15","confidence":0.9}"""
    }
}
