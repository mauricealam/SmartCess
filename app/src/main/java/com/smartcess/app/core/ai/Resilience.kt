package com.smartcess.app.core.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** Sanitized AI failure: safe to show and speak, never carries payloads or keys. */
open class AiException(message: String, cause: Throwable? = null) : IOException(message, cause)
class RateLimitedException : AiException("The AI service is busy. Please try again in a moment.")
class OfflineModeException : AiException("You are offline. Showing what is saved on this device.")
class InvalidAiResponseException : AiException("The AI answer was unclear. Please try again.")
/** Backend session missing or refresh failed: the user must sign in again. Never retried. */
class SessionExpiredException : AiException("Please sign in again.")

/** Client-side rate limit: max concurrent calls + per-minute budget. */
@Singleton
class RateLimiter @Inject constructor() {
    @Volatile
    private var semaphore = Semaphore(2)
    private val mutex = Mutex()
    private val windowStart = mutableListOf<Long>()
    private var maxPerMinute = 20

    suspend fun <T> guard(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            mutex.withLock {
                val now = System.currentTimeMillis()
                windowStart.removeAll { now - it > 60_000 }
                if (windowStart.size >= maxPerMinute) throw RateLimitedException()
                windowStart += now
            }
            return block()
        } finally {
            semaphore.release()
        }
    }

    /**
     * Applies both limits. Replacing the semaphore only affects calls that
     * acquire after this returns; in-flight calls keep their permit.
     */
    fun configure(maxConcurrent: Int, perMinute: Int) {
        semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
        maxPerMinute = perMinute
    }
}

/** Retry policy: transient 2x backoff, timeout 1x; rate-limit and offline never retry. */
@Singleton
class RetryPolicy @Inject constructor() {
    suspend fun <T> withRetry(
        transientRetries: Int = 2,
        timeoutRetries: Int = 1,
        block: suspend () -> T,
    ): T {
        var transientLeft = transientRetries
        var timeoutLeft = timeoutRetries
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: OfflineModeException) {
                throw e
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: TimeoutException) {
                if (timeoutLeft-- <= 0) throw AiException("The AI service timed out. Please try again.", e)
                delay(backoff(attempt++))
            } catch (e: IOException) {
                if (transientLeft-- <= 0) throw AiException("Could not reach the AI service.", e)
                delay(backoff(attempt++))
            }
        }
    }

    private fun backoff(attempt: Int): Long = 500L * (1 shl attempt.coerceAtMost(3))
}
