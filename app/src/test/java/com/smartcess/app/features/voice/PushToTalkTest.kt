package com.smartcess.app.features.voice

import android.content.Context
import com.smartcess.app.R
import com.smartcess.app.core.ai.AiException
import com.smartcess.app.core.ai.IntentResult
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.privacy.AnalyticsLogger
import com.smartcess.app.core.session.BackendAuthApi
import com.smartcess.app.core.session.BackendHealthMonitor
import com.smartcess.app.core.session.BackendVoiceApi
import com.smartcess.app.core.session.GoogleExchangeRequest
import com.smartcess.app.core.session.HealthResponse
import com.smartcess.app.core.session.RefreshRequest
import com.smartcess.app.core.session.SessionPair
import com.smartcess.app.core.session.SignoutResponse
import com.smartcess.app.core.session.TranscribeResponse
import com.smartcess.app.core.voice.MicException
import com.smartcess.app.core.voice.RecordedAudio
import com.smartcess.app.core.voice.TalkCues
import com.smartcess.app.core.voice.TalkRecorder
import com.smartcess.app.data.local.dao.ConversationTurnDao
import com.smartcess.app.data.local.dao.UserPreferencesDao
import com.smartcess.app.data.local.entity.ConversationTurnEntity
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import com.smartcess.app.data.repository.PreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Push-to-talk Talk flow: record -> upload -> transcribe -> speak the answer,
 * clarification, or confirmation. Covers silence, expired sessions, timeout,
 * cancellation, duplicate taps, and multi-turn pending questions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushToTalkTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeRecorder(var rec: RecordedAudio? = null, var startError: MicException? = null) :
        TalkRecorder {
        var starts = 0
        var stops = 0
        var cancels = 0
        override var isRecording = false
        override var autoStopped = false
        override fun start() {
            startError?.let { throw it }
            starts++
            isRecording = true
        }
        override fun stop(): RecordedAudio {
            stops++
            isRecording = false
            return rec ?: throw MicException("Not recording.")
        }
        override fun cancel() {
            cancels++
            isRecording = false
            rec?.file?.delete()
        }
    }

    private class FakeVoiceApi(
        var response: TranscribeResponse = TranscribeResponse("hello", 900, 1),
        var error: Throwable? = null,
        var hangMs: Long = 0,
    ) : BackendVoiceApi {
        var calls = 0
        override suspend fun transcribe(audio: okhttp3.MultipartBody.Part): TranscribeResponse {
            calls++
            if (hangMs > 0) delay(hangMs)
            error?.let { throw it }
            return response
        }
    }

    private class FakeCues : TalkCues {
        var cues = 0
        override fun recordingStarted() {
            cues++
        }
    }

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        private val pendingDone = mutableListOf<() -> Unit>()
        override fun speak(text: String) {
            spoken.add(text)
        }
        override fun speak(text: String, onDone: () -> Unit) {
            spoken.add(text)
            pendingDone.add(onDone) // real TTS ends later; tests complete it
        }
        /** Finishes all in-flight speech, firing completion callbacks. */
        fun completeSpeech() {
            val done = pendingDone.toList()
            pendingDone.clear()
            done.forEach { it() }
        }
        override fun stop() {
            stops++
        }
        override fun setRate(rate: Float) {}
    }

    private class FakeTurns : ConversationTurnDao {
        val saved = mutableListOf<ConversationTurnEntity>()
        override suspend fun upsert(turn: ConversationTurnEntity): Long {
            saved.add(turn)
            return 1L
        }
        override suspend fun deleteAll() {}
        override suspend fun all(): List<ConversationTurnEntity> = saved.toList()
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
        override suspend fun exchange(body: GoogleExchangeRequest): SessionPair = error("unused")
        override suspend fun refresh(body: RefreshRequest): SessionPair = error("unused")
        override suspend fun signout(): SignoutResponse = error("unused")
        override suspend fun health(): HealthResponse = HealthResponse("ok", "t")
    }

    private lateinit var recorder: FakeRecorder
    private lateinit var voiceApi: FakeVoiceApi
    private lateinit var cues: FakeCues
    private lateinit var speaker: FakeSpeaker
    private lateinit var spark: SparkClient
    private lateinit var ctx: Context

    private fun recordedFile(): java.io.File =
        kotlin.io.path.createTempFile(suffix = ".wav").toFile().also { it.writeBytes(ByteArray(128)) }

    private fun viewModel(
        micGranted: Boolean = true,
        handler: VoiceActionHandler = mockk(relaxed = true),
    ): VoiceViewModel = VoiceViewModel(
        recorder = recorder,
        voiceApi = voiceApi,
        cues = cues,
        spark = spark,
        speaker = speaker,
        turns = FakeTurns(),
        prefs = PreferencesRepository(FakePrefsDao(UserPreferencesEntity())),
        analytics = mockk<AnalyticsLogger>(relaxed = true),
        appContext = ctx,
        monitor = BackendHealthMonitor(FakeAuth()),
        ioDispatcher = testDispatcher,
        actions = handler,
    ).apply {
        micPermissionCheck = { micGranted }
        logPhase = {} // android.util.Log is a stub on the JVM
    }

    private fun httpError(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<TranscribeResponse>(code, body.toResponseBody("text/plain".toMediaType())))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recorder = FakeRecorder()
        voiceApi = FakeVoiceApi()
        cues = FakeCues()
        speaker = FakeSpeaker()
        spark = mockk(relaxed = true)
        ctx = mockk()
        every { ctx.getString(any()) } returns "opening"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordStopUpload_classifyClarify_speaksQuestion() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.response = TranscribeResponse("my documents", 900, 1)
        coEvery { spark.classifyIntent(any(), any()) } returns
            IntentResult(intent = "read_document", confidence = 0.9, clarifying_question = "Which document?")
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        assertTrue(vm.state.value.recording)
        assertEquals(1, cues.cues)
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals("my documents", vm.state.value.transcript)
        assertEquals("Which document?", vm.state.value.message)
        assertEquals(listOf("Which document?"), speaker.spoken)
        assertNull(vm.state.value.errorRes)
        // Hands-free turn-taking: the question's end restarts listening.
        assertEquals(1, recorder.starts)
        speaker.completeSpeech()
        assertFalse(vm.state.value.speaking)
        assertTrue(vm.state.value.recording)
        assertEquals(2, recorder.starts)
    }

    @Test
    fun followUpTurn_carriesPendingQuestionAsContext() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        val contexts = mutableListOf<String?>()
        coEvery { spark.classifyIntent(any(), any()) } answers {
            contexts.add(invocation.args[1] as String?)
            if (contexts.size == 1) {
                IntentResult(intent = "read_document", confidence = 0.9, clarifying_question = "Which document?")
            } else {
                IntentResult(intent = "read_document", confidence = 0.95)
            }
        }
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        val routes = mutableListOf<String>()
        backgroundScope.launch { vm.navigate.collect { routes.add(it) } }
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1100)
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        // Second classification saw the pending question; route confirmed + spoken.
        assertEquals(2, contexts.size)
        assertTrue(contexts[1] != null && contexts[1]!!.contains("Which document?"))
        assertEquals(listOf("Which document?", "opening"), speaker.spoken)
        assertEquals(1, routes.size)
    }

    @Test
    fun generalQuestion_speaksGeneratedAnswer() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.response = TranscribeResponse("what is AI", 900, 1)
        coEvery { spark.classifyIntent(any(), any()) } returns IntentResult(intent = "general_question", confidence = 0.8)
        coEvery { spark.answer(any(), any()) } returns "Two short sentences."
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals("Two short sentences.", vm.state.value.message)
        assertEquals(listOf("Two short sentences."), speaker.spoken)
    }

    @Test
    fun blankAnswer_fallsBackToHelp() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.response = TranscribeResponse("hmm", 900, 1)
        coEvery { spark.classifyIntent(any(), any()) } returns IntentResult(intent = "general_question", confidence = 0.8)
        coEvery { spark.answer(any(), any()) } returns "  "
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals(R.string.voice_help, vm.state.value.messageRes)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun silentRecording_neverUploads_showsNoSpeech() = runTest {
        val file = recordedFile()
        recorder.rec = RecordedAudio(file, peak = 100, durationMs = 1500)
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals(0, voiceApi.calls)
        assertEquals(R.string.voice_no_speech, vm.state.value.errorRes)
        assertFalse(file.exists())
    }

    @Test
    fun silentTurnWithPendingTask_goesIdleQuietly() = runTest {
        val file = recordedFile()
        recorder.rec = RecordedAudio(file, peak = 100, durationMs = 1500)
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returns true
        val vm = viewModel(handler = handler)
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertNull(vm.state.value.errorRes)
        assertFalse(vm.state.value.processing)
        assertFalse(vm.state.value.recording)
        coVerify(exactly = 0) { handler.discard() }
    }

    @Test
    fun upload401_showsSignInError() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.error = httpError(401)
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals(R.string.auth_sign_in_required, vm.state.value.errorRes)
        assertFalse(vm.state.value.processing)
    }

    @Test
    fun uploadUnclearBody_speaksNotClear() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.error = httpError(502, """{"error":{"code":"ai_unclear"}}""")
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals(R.string.voice_not_clear, vm.state.value.errorRes)
    }

    @Test
    fun uploadTimeout_showsTimeoutError() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.hangMs = 120_000
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals(R.string.voice_timeout, vm.state.value.errorRes)
    }

    @Test
    fun cancelDuringRecording_discardsAndResets() = runTest {
        val file = recordedFile()
        recorder.rec = RecordedAudio(file, peak = 5000, durationMs = 1200)
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.cancel()
        advanceUntilIdle()
        assertEquals(1, recorder.cancels)
        assertEquals(0, voiceApi.calls)
        assertFalse(file.exists())
        assertEquals(VoiceUiState(), vm.state.value)
    }

    @Test
    fun duplicateStart_ignored() = runTest {
        val vm = viewModel()
        vm.startTalk()
        vm.startTalk()
        runCurrent()
        assertEquals(1, recorder.starts)
        assertEquals(1, cues.cues)
    }

    @Test
    fun micDenied_showsPermissionError() = runTest {
        recorder.startError = MicException("Microphone permission is needed to talk.")
        val vm = viewModel()
        vm.startTalk()
        advanceUntilIdle()
        assertFalse(vm.state.value.recording)
        assertEquals(R.string.perm_mic_rationale, vm.state.value.errorRes)
    }

    @Test
    fun micBlockedAtTap_neverRecordsSpeaksGuidance() = runTest {
        // Devices that silence instead of throwing must not capture zeros.
        val vm = viewModel(micGranted = false)
        vm.startTalk()
        advanceUntilIdle()
        assertFalse(vm.state.value.recording)
        assertEquals(0, recorder.starts)
        assertEquals(0, cues.cues)
        assertEquals(R.string.perm_mic_rationale, vm.state.value.errorRes)
    }

    @Test
    fun micDeniedThenGranted_recovers() = runTest {
        val vm = viewModel(micGranted = false)
        vm.startTalk()
        advanceUntilIdle()
        assertEquals(R.string.perm_mic_rationale, vm.state.value.errorRes)
        vm.micPermissionCheck = { true }
        vm.startTalk()
        runCurrent()
        assertTrue(vm.state.value.recording)
        assertEquals(1, recorder.starts)
        assertNull(vm.state.value.errorRes)
    }

    @Test
    fun silenceAutoStop_submitsWithoutStopTap() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 4200)
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        assertTrue(vm.state.value.recording)
        recorder.autoStopped = true // trailing silence ended capture
        advanceUntilIdle()
        assertEquals(1, recorder.stops)
        assertFalse(vm.state.value.recording)
        assertFalse(vm.state.value.processing)
        assertEquals("hello", vm.state.value.transcript)
        assertNull(vm.state.value.errorRes)
    }

    @Test
    fun manualStop_cancelsAutoStopPoll() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        recorder.autoStopped = true // stale flag must not double-submit
        advanceUntilIdle()
        assertEquals(1, recorder.stops)
        assertEquals(1, voiceApi.calls)
    }

    @Test
    fun speechContinues_noPrematureAutoStop() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        val vm = viewModel()
        vm.startTalk()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.state.value.recording)
        assertEquals(0, recorder.stops)
        assertEquals(0, voiceApi.calls)
    }

    @Test
    fun talkTurn_logsPhaseTimingsWithoutContent() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.response = TranscribeResponse("my bills", 900, 1)
        coEvery { spark.classifyIntent(any(), any()) } returns IntentResult(intent = "general_question", confidence = 0.8)
        coEvery { spark.answer(any(), any()) } returns "Two short sentences."
        val phases = mutableListOf<String>()
        val vm = viewModel()
        vm.logPhase = { phases.add(it) }
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals(3, phases.size)
        assertTrue(phases[0].startsWith("upload="))
        assertTrue(phases[1].startsWith("classify="))
        assertTrue(phases[2].startsWith("answer="))
        phases.forEach {
            assertFalse(it.contains("my bills"))
            assertFalse(it.contains("Two short sentences."))
        }
    }

    @Test
    fun actionIntent_startsHandlerInsteadOfNavigate() = runTest {
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1200)
        voiceApi.response = TranscribeResponse("dentist tomorrow", 900, 1)
        coEvery { spark.classifyIntent(any(), any()) } returns
            IntentResult(intent = "add_appointment", confidence = 0.9)
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returns false
        coEvery { handler.start(any(), any(), any()) } returns ActionTurn("What day and time?")
        val vm = viewModel(handler = handler)
        val routes = mutableListOf<String>()
        backgroundScope.launch { vm.navigate.collect { routes.add(it) } }
        vm.startTalk()
        runCurrent()
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals("What day and time?", vm.state.value.message)
        assertEquals(listOf("What day and time?"), speaker.spoken)
        assertTrue(routes.isEmpty())
        coVerify(exactly = 0) { spark.answer(any(), any()) }
    }

    @Test
    fun crispCommand_skipsClassify_runsHandler() = runTest {
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returnsMany listOf(false, false)
        coEvery { handler.start(any(), any(), any()) } returns ActionTurn("What should I remind you about?")
        val phases = mutableListOf<String>()
        val vm = viewModel(handler = handler)
        vm.logPhase = { phases.add(it) }
        vm.onTypedChange("remind me to call mom")
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals("What should I remind you about?", vm.state.value.message)
        coVerify(exactly = 0) { spark.classifyIntent(any(), any()) }
        coVerify(exactly = 1) { handler.start("create_reminder", "remind me to call mom", any()) }
        assertTrue(phases.any { it.startsWith("route=fastpath") })
    }

    @Test
    fun fuzzyCommand_stillClassifies() = runTest {
        coEvery { spark.classifyIntent(any(), any()) } returns IntentResult(intent = "general_question", confidence = 0.8)
        coEvery { spark.answer(any(), any()) } returns "Two short sentences."
        val vm = viewModel()
        vm.onTypedChange("maybe something later")
        vm.submitTyped()
        advanceUntilIdle()
        coVerify(exactly = 1) { spark.classifyIntent(any(), any()) }
        assertEquals("Two short sentences.", vm.state.value.message)
    }

    @Test
    fun pendingFollowUp_skipsClassify_speaksTurn() = runTest {
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returns true
        coEvery { handler.followUp(any()) } returns ActionTurn("Saved.", saved = true)
        val vm = viewModel(handler = handler)
        vm.onTypedChange("yes")
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals("Saved.", vm.state.value.message)
        coVerify(exactly = 0) { spark.classifyIntent(any(), any()) }
    }

    @Test
    fun cancel_discardsPendingAction() = runTest {
        val handler = mockk<VoiceActionHandler>(relaxed = true)
        val vm = viewModel(handler = handler)
        vm.startTalk()
        runCurrent()
        vm.cancel()
        advanceUntilIdle()
        coVerify(exactly = 1) { handler.discard() }
    }

    @Test
    fun bargeIn_stopsSpeech_keepsPending() = runTest {
        // A new Talk tap stops old TTS (so speech is never the next turn's
        // "confirmation") without discarding the pending action.
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returns true
        val vm = viewModel(handler = handler)
        vm.startTalk()
        runCurrent()
        assertEquals(1, speaker.stops)
        coVerify(exactly = 0) { handler.discard() }
    }

    @Test
    fun typedFailure_showsGenericError() = runTest {
        coEvery { spark.classifyIntent(any(), any()) } throws AiException("boom")
        val vm = viewModel()
        vm.onTypedChange("hello")
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals(R.string.error_generic, vm.state.value.errorRes)
    }

    @Test
    fun actionIntentWithClassifierQuestion_routesToHandler_keepsTaskAlive() = runTest {
        // Regression: the classifier's question must not orphan an action
        // task — the executor owns all of its follow-ups.
        coEvery { spark.classifyIntent(any(), any()) } returns
            IntentResult(intent = "add_appointment", confidence = 0.9, clarifying_question = "What day and time?")
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returnsMany listOf(false, true, true)
        coEvery { handler.start(any(), any(), any()) } returns ActionTurn("What is the title?")
        coEvery { handler.followUp(any()) } returns ActionTurn("Saved dentist.", saved = true)
        val vm = viewModel(handler = handler)
        vm.onTypedChange("dentist")
        vm.submitTyped()
        advanceUntilIdle()
        // The executor's question wins, not the classifier's.
        assertEquals("What is the title?", vm.state.value.message)
        coVerify(exactly = 1) { handler.start(any(), any(), any()) }
        // The follow-up stays inside the task: no second classification.
        vm.onTypedChange("yes")
        vm.submitTyped()
        advanceUntilIdle()
        coVerify(exactly = 1) { handler.followUp("yes") }
        coVerify(exactly = 1) { spark.classifyIntent(any(), any()) }
        assertEquals("Saved dentist.", vm.state.value.message)
    }

    @Test
    fun savedTurn_doesNotAutoListen() = runTest {
        val handler = mockk<VoiceActionHandler>()
        every { handler.hasPending } returns true
        coEvery { handler.followUp(any()) } returns ActionTurn("Saved.", saved = true)
        val vm = viewModel(handler = handler)
        vm.onTypedChange("yes")
        vm.submitTyped()
        advanceUntilIdle()
        speaker.completeSpeech()
        assertFalse(vm.state.value.recording)
        assertEquals(0, recorder.starts)
    }

    @Test
    fun cancelDuringSpeech_blocksAutoListen() = runTest {
        coEvery { spark.classifyIntent(any(), any()) } returns
            IntentResult(intent = "read_document", confidence = 0.9, clarifying_question = "Which document?")
        val vm = viewModel()
        vm.onTypedChange("my documents")
        vm.submitTyped()
        advanceUntilIdle()
        assertEquals("Which document?", vm.state.value.message)
        vm.cancel()
        speaker.completeSpeech() // stale TTS callback must not restart the mic
        assertFalse(vm.state.value.recording)
        assertEquals(0, recorder.starts)
    }

    @Test
    fun autoTurnCap_stopsHandsFreeUntilManualTap() = runTest {
        coEvery { spark.classifyIntent(any(), any()) } returns
            IntentResult(intent = "read_document", confidence = 0.9, clarifying_question = "Which document?")
        val vm = viewModel()
        vm.maxAutoTurns = 1
        vm.onTypedChange("my documents")
        vm.submitTyped()
        advanceUntilIdle()
        // First completion auto-listens (allowance 1).
        speaker.completeSpeech()
        assertTrue(vm.state.value.recording)
        // Answer the auto turn; the next completion is capped.
        recorder.rec = RecordedAudio(recordedFile(), peak = 5000, durationMs = 1100)
        voiceApi.response = TranscribeResponse("the bank statement", 900, 1)
        vm.stopTalk()
        advanceUntilIdle()
        assertEquals("Which document?", vm.state.value.message)
        speaker.completeSpeech()
        assertFalse(vm.state.value.recording)
        assertEquals(1, recorder.starts)
        // A deliberate tap restarts the allowance.
        vm.startTalk()
        assertTrue(vm.state.value.recording)
        assertEquals(2, recorder.starts)
    }
}
