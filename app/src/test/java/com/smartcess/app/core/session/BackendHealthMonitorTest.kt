package com.smartcess.app.core.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Warm-up schedule: success flips READY, exhaustion flips DOWN. */
class BackendHealthMonitorTest {

    private class ScriptedAuth(private val script: List<Boolean>) : BackendAuthApi {
        var calls = 0
        override suspend fun exchange(body: GoogleExchangeRequest): SessionPair =
            error("unused")

        override suspend fun refresh(body: RefreshRequest): SessionPair = error("unused")
        override suspend fun signout(): SignoutResponse = error("unused")
        override suspend fun health(): HealthResponse {
            calls++
            if (script.getOrElse(calls - 1) { false }) return HealthResponse("ok", "t")
            throw RuntimeException("down")
        }
    }

    private fun awaitState(monitor: BackendHealthMonitor): HealthState = runBlocking {
        repeat(200) {
            val state = monitor.state.value
            if (state == HealthState.READY || state == HealthState.DOWN) return@runBlocking state
            delay(10)
        }
        error("warmup never settled")
    }

    @Test
    fun warmup_succeedsAfterRetries() {
        val monitor = BackendHealthMonitor(ScriptedAuth(listOf(false, false, true)))
        monitor.delaysMillis = listOf(0, 0, 0)
        monitor.warmup()
        assertEquals(HealthState.READY, awaitState(monitor))
    }

    @Test
    fun warmup_exhausted_reportsDown() {
        val monitor = BackendHealthMonitor(ScriptedAuth(listOf(false, false, false)))
        monitor.delaysMillis = listOf(0, 0, 0)
        monitor.warmup()
        assertEquals(HealthState.DOWN, awaitState(monitor))
    }

    @Test
    fun warmup_skipsWhenReady() {
        val auth = ScriptedAuth(listOf(true))
        val monitor = BackendHealthMonitor(auth)
        monitor.delaysMillis = listOf(0)
        monitor.warmup()
        assertEquals(HealthState.READY, awaitState(monitor))
        monitor.warmup()
        assertEquals(1, auth.calls)
    }
}
