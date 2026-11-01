package com.smartcess.app.features.home

import com.smartcess.app.core.session.BackendAuthApi
import com.smartcess.app.core.session.BackendHealthMonitor
import com.smartcess.app.core.session.GoogleExchangeRequest
import com.smartcess.app.core.session.HealthResponse
import com.smartcess.app.core.session.HealthState
import com.smartcess.app.core.session.RefreshRequest
import com.smartcess.app.core.session.SessionManager
import com.smartcess.app.core.session.SessionPair
import com.smartcess.app.core.session.SignoutResponse
import com.smartcess.app.data.repository.BriefingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A stuck DOWN banner must clear on Refresh without an app restart; a
 * healthy backend must not be re-probed.
 */
class HomeViewModelTest {

    private class ScriptedAuth(private val script: MutableList<Boolean>) : BackendAuthApi {
        var calls = 0
        override suspend fun exchange(body: GoogleExchangeRequest): SessionPair =
            error("unused")

        override suspend fun refresh(body: RefreshRequest): SessionPair = error("unused")
        override suspend fun signout(): SignoutResponse = error("unused")
        override suspend fun health(): HealthResponse {
            calls++
            if (script.removeFirstOrNull() == true) return HealthResponse("ok", "t")
            throw RuntimeException("down")
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(monitor: BackendHealthMonitor): HomeViewModel {
        val briefing = mockk<BriefingRepository>()
        coEvery { briefing.getTodayData() } throws RuntimeException("offline")
        val sessions = mockk<SessionManager>()
        every { sessions.isSignedIn() } returns true
        return HomeViewModel(briefing, sessions, monitor)
    }

    private fun awaitSettled(monitor: BackendHealthMonitor): HealthState = runBlocking {
        repeat(200) {
            val state = monitor.state.value
            if (state == HealthState.READY || state == HealthState.DOWN) return@runBlocking state
            delay(10)
        }
        error("health never settled")
    }

    @Test
    fun refresh_retriesAfterDown() {
        val auth = ScriptedAuth(mutableListOf(false, true))
        val monitor = BackendHealthMonitor(auth)
        monitor.delaysMillis = listOf(0)
        monitor.warmup()
        assertEquals(HealthState.DOWN, awaitSettled(monitor))

        viewModel(monitor) // init refresh() must re-probe the stuck DOWN state
        assertEquals(HealthState.READY, awaitSettled(monitor))
        assertEquals(2, auth.calls)
    }

    @Test
    fun refresh_keepsReadyWithoutReprobe() {
        val auth = ScriptedAuth(mutableListOf(true))
        val monitor = BackendHealthMonitor(auth)
        monitor.delaysMillis = listOf(0)
        monitor.warmup()
        assertEquals(HealthState.READY, awaitSettled(monitor))

        val vm = viewModel(monitor)
        vm.refresh()
        runBlocking { delay(100) }
        assertEquals(HealthState.READY, monitor.state.value)
        assertEquals(1, auth.calls)
    }
}
