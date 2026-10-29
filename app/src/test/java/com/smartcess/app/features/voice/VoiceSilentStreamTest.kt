package com.smartcess.app.features.voice

import com.smartcess.app.R
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.privacy.AnalyticsLogger
import com.smartcess.app.core.session.BackendAuthApi
import com.smartcess.app.core.session.BackendHealthMonitor
import com.smartcess.app.core.session.GoogleExchangeRequest
import com.smartcess.app.core.session.HealthResponse
import com.smartcess.app.core.session.RefreshRequest
import com.smartcess.app.core.session.SessionPair
import com.smartcess.app.core.session.SignoutResponse
import com.smartcess.app.core.voice.MuseVoiceTranscriber
import com.smartcess.app.core.voice.TranscriptEvent
import com.smartcess.app.data.local.dao.ConversationTurnDao
import com.smartcess.app.data.local.dao.UserPreferencesDao
import com.smartcess.app.data.local.entity.ConversationTurnEntity
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import com.smartcess.app.data.repository.PreferencesRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * A transcript stream that ends without a Final or Error (server closed
 * silently) must leave Talk in an error state, never stuck on "Listening".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSilentStreamTest {

    private val testDispatcher = StandardTestDispatcher()

    private class ScriptedTranscriber(val events: List<TranscriptEvent>) : MuseVoiceTranscriber {
        override fun transcribe(): Flow<TranscriptEvent> = events.asFlow()
        override fun stop() {}
    }

    private class FakeSpeaker : Speaker {
        override fun speak(text: String) {}
        override fun stop() {}
        override fun setRate(rate: Float) {}
    }

    private class FakeTurns : ConversationTurnDao {
        override suspend fun upsert(turn: ConversationTurnEntity): Long = 1L
        override suspend fun deleteAll() {}
        override suspend fun all(): List<ConversationTurnEntity> = emptyList()
    }

    private class FakePrefsDao(var current: UserPreferencesEntity?) : UserPreferencesDao {
        override suspend fun upsert(prefs: UserPreferencesEntity) {
            current = prefs
        }
        override suspend fun get(): UserPreferencesEntity? = current
        override fun observe(): Flow<UserPreferencesEntity?> = flowOf(current)
        override suspend fun deleteAll() {
            current = null
        }
    }

    private class FakeAuth : BackendAuthApi {
        override suspend fun exchange(body: GoogleExchangeRequest): SessionPair =
            error("unused")

        override suspend fun refresh(body: RefreshRequest): SessionPair = error("unused")
        override suspend fun signout(): SignoutResponse = error("unused")
        override suspend fun health(): HealthResponse = HealthResponse("ok", "t")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(events: List<TranscriptEvent>) = VoiceViewModel(
        transcriber = ScriptedTranscriber(events),
        spark = mockk<SparkClient>(relaxed = true),
        speaker = FakeSpeaker(),
        turns = FakeTurns(),
        prefs = PreferencesRepository(FakePrefsDao(UserPreferencesEntity())),
        analytics = mockk<AnalyticsLogger>(relaxed = true),
        monitor = BackendHealthMonitor(FakeAuth()),
    )

    @Test
    fun silentStream_showsNoSpeechError() = runTest {
        val vm = viewModel(emptyList())
        vm.startListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertEquals(R.string.voice_no_speech, vm.state.value.errorRes)
    }

    @Test
    fun partialThenSilent_showsNotClearError() = runTest {
        val vm = viewModel(listOf(TranscriptEvent.Partial("hel")))
        vm.startListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertEquals(R.string.voice_not_clear, vm.state.value.errorRes)
    }

    @Test
    fun errorEvent_stillMapsKnownCauses() = runTest {
        val vm = viewModel(listOf(TranscriptEvent.Error("Microphone permission denied")))
        vm.startListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertEquals(R.string.perm_mic_rationale, vm.state.value.errorRes)
    }
}
