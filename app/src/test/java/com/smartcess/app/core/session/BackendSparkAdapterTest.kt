package com.smartcess.app.core.session

import com.smartcess.app.core.ai.SparkMessage
import com.smartcess.app.core.ai.SparkRequest
import com.smartcess.app.core.ai.SparkResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Backend adapter delegates the Spark contract unchanged. */
class BackendSparkAdapterTest {

    @Test
    fun complete_delegatesAndPassesThrough(): Unit = runBlocking {
        val response = SparkResponse()
        var seen: SparkRequest? = null
        val chat = object : BackendChatApi {
            override suspend fun chat(request: SparkRequest): SparkResponse {
                seen = request
                return response
            }
        }
        val request = SparkRequest(
            model = "muse-spark-1.3",
            messages = listOf(SparkMessage(role = "user", content = JsonPrimitive("hi"))),
        )
        val out = BackendSparkAdapter(chat).complete(request)
        assertSame(request, seen)
        assertSame(response, out)
        assertEquals("muse-spark-1.3", seen?.model)
    }
}
