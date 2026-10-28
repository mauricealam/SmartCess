package com.smartcess.app.core.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/** H3: RateLimiter.configure must apply maxConcurrent, not just per-minute. */
class RateLimiterTest {

    @Test
    fun configure_appliesMaxConcurrent(): Unit = runBlocking {
        val limiter = RateLimiter()
        limiter.configure(1, 100)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val first = async {
            limiter.guard {
                order += "a-start"
                delay(100)
                order += "a-end"
            }
        }
        delay(25)
        val second = async { limiter.guard { order += "b" } }
        first.await()
        second.await()
        assertEquals(listOf("a-start", "a-end", "b"), order)
    }
}
