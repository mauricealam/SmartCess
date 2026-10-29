package com.smartcess.app.features.voice

import com.smartcess.app.R
import com.smartcess.app.core.ai.AiException
import com.smartcess.app.core.ai.IntentResult
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Talk stream lifecycle: silent streams and post-Stop silence must surface
 * errors (never hang or go quiet), finals after Stop still get answered,
 * and typed failures show a generic error (never a hearing-flavored one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSilentStreamTest {

    private val testDispatcher = StandardTestDispatcher()

    private class ScriptedTranscriber(val events: List<TranscriptEvent>) : MuseVoiceTranscriber {
        override fun transcribe(): Flow<TranscriptEvent> = events.asFlow()
        override fun stop() {}
    }

    /** Stays open until stop(), then emits [events] and completes. */
    private class DeferredTranscriber(var events: List<TranscriptEvent> = emptyList()) :
        MuseVoiceTranscriber {
        private val gate = CompletableDeferred<Unit>()
        var stops = 0
        override fun transcribe(): Flow<TranscriptEvent> = flow {
            gate.await()
            events.forEach { emit(it) }
        }
        override fun stop() {
            stops++
            gate.complete(Unit)
        }
    }

    private class FakeSpeaker : Speaker {
        override fun speak(text: String) {}
        override fun stop() {}
        override fun setRate(rate: Float) {}
    }

    private class FakeTurns : ConversationTurnDao {
        val saved = mutableListOf<ConversationTurnEntity>()
        override suspend fun upsert(turn: ConversationTurnEntity): Long {
            saved.add(turn)
            return 1L
        }
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

    private fun helpSpark(): SparkClient = mockk<SparkClient>().also { spark ->
        coEvery { spark.classifyIntent(any()) } returns
            IntentResult(intent = "general_question", confidence = 1.0)
    }

    private fun viewModel(
        transcriber: MuseVoiceTranscriber,
        spark: SparkClient = mockk(relaxed = true),
    ) = VoiceViewModel(
        transcriber = transcriber,
        spark = spark,
        speaker = FakeSpeaker(),
        turns = FakeTurns(),
        prefs = PreferencesRepository(FakePrefsDao(UserPreferencesEntity())),
        analytics = mockk(relaxed = true),
        monitor = BackendHealthMonitor(FakeAuth()),
    )

    @Test
    fun silentStream_showsNoSpeechError() = runTest {
        val vm = viewModel(ScriptedTranscriber(emptyList()))
        vm.startListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertEquals(R.string.voice_no_speech, vm.state.value.errorRes)
    }

    @Test
    fun partialThenSilent_showsNotClearError() = runTest {
        val vm = viewModel(ScriptedTranscriber(listOf(TranscriptEvent.Partial("hel"))))
        vm.startListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertEquals(R.string.voice_not_clear, vm.state.value.errorRes)
    }

    @Test
    fun errorEvent_stillMapsKnownCauses() = runTest {
        val vm = viewModel(
            ScriptedTranscriber(listOf(TranscriptEvent.Error("Microphone permission denied"))),
        )
        vm.startListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertEquals(R.string.perm_mic_rationale, vm.state.value.errorRes)
    }

    @Test
    fun stopThenFinal_getsAnswered() = runTest {
        val transcriber = DeferredTranscriber(listOf(TranscriptEvent.Final("my bills")))
        val vm = viewModel(transcriber, helpSpark())
        vm.startListening()
        advanceUntilIdle()
        assertTrue(vm.state.value.listening)
        vm.stopListening()
        advanceUntilIdle()
        assertEquals(1, transcriber.stops)
        assertEquals("my bills", vm.state.value.transcript)
        assertFalse(vm.state.value.processing)
        assertNull(vm.state.value.errorRes)
        // general_question stays on Talk with the help message.
        assertEquals(R.string.voice_help, vm.state.value.messageRes)
    }

    @Test
    fun stopThenSilence_showsNoSpeechError() = runTest {
        val vm = viewModel(DeferredTranscriber(emptyList()))
        vm.startListening()
        advanceUntilIdle()
        vm.stopListening()
        advanceUntilIdle()
        assertFalse(vm.state.value.listening)
        assertFalse(vm.state.value.processing)
        assertEquals(R.string.voice_no_speech, vm.state.value.errorRes)
    }

    @Test
    fun typedFailure_showsGenericError() = runTest {
        val spark = mockk<SparkClient>()
        coEvery { spark.classifyIntent(any()) } throws AiException("boom")
        val vm = viewModel(ScriptedTranscriber(emptyList()), spark)
        vm.onTypedChange("hello")
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(R.string.error_generic, vm.state.value.errorRes)
    }

    @Test
    fun typedSuccess_answersWithoutError() = runTest {
        val vm = viewModel(ScriptedTranscriber(emptyList()), helpSpark())
        vm.onTypedChange("hello")
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals("hello", vm.state.value.transcript)
        assertNull(vm.state.value.errorRes)
        assertEquals(R.string.voice_help, vm.state.value.messageRes)
    }
}
