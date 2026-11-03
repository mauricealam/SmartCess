package com.smartcess.app.features.camera

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.smartcess.app.core.ai.BillExtraction
import com.smartcess.app.core.ai.DocumentSummary
import com.smartcess.app.core.ai.OcrEngine
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.privacy.AnalyticsLogger
import com.smartcess.app.core.session.BackendVoiceApi
import com.smartcess.app.core.session.TranscribeResponse
import com.smartcess.app.core.voice.MicException
import com.smartcess.app.core.voice.PttAudioSpec
import com.smartcess.app.core.voice.RecordedAudio
import com.smartcess.app.core.voice.TalkCues
import com.smartcess.app.core.voice.TalkRecorder
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.data.repository.SaveOutcome
import com.smartcess.app.data.repository.ValidationResult
import com.smartcess.app.navigation.Routes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import com.smartcess.app.R
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Capture-mode routing, review-before-save, utterance voice, and the
 * TTS/listening guard. Voice tests script transcripts through a fake
 * recorder + fake HTTPS API — the same pipeline shape as Talk.
 */
class CameraViewModelTest {

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        private val pending = mutableListOf<() -> Unit>()
        override fun speak(text: String) {
            spoken.add(text)
        }
        override fun speak(text: String, onDone: () -> Unit) {
            spoken.add(text)
            pending.add(onDone)
        }
        override fun stop() {}
        override fun setRate(rate: Float) {}
        fun completeAll() {
            while (pending.isNotEmpty()) pending.removeAt(0).invoke()
        }
    }

    private class FakeRecorder : TalkRecorder {
        var starts = 0
        var autoStopNow = false
        var stopPeak = 5000
        var startThrows: Exception? = null
        var lastEndSilenceMs = -1L
        var file: File = File.createTempFile("utt", ".wav").also { it.writeBytes(ByteArray(128)) }
        override fun start(endSilenceMs: Long) {
            startThrows?.let { throw it }
            starts++
            lastEndSilenceMs = endSilenceMs
        }
        override fun stop(): RecordedAudio {
            if (!file.exists()) file.writeBytes(ByteArray(128))
            return RecordedAudio(file, stopPeak, 1000)
        }
        override fun cancel() {}
        override val isRecording: Boolean get() = false
        override val autoStopped: Boolean get() = autoStopNow
    }

    private class FakeVoiceApi : BackendVoiceApi {
        val queue = ArrayDeque<Any>()
        var calls = 0
        override suspend fun transcribe(audio: MultipartBody.Part): TranscribeResponse {
            calls++
            return when (val next = queue.removeFirstOrNull()) {
                is TranscribeResponse -> next
                is Throwable -> throw next
                else -> TranscribeResponse("hello there", 900, 1)
            }
        }
        fun hear(vararg texts: String) {
            texts.forEach { queue.add(TranscribeResponse(it, 900, 1)) }
        }
    }

    // Inferred type intentional: the explicit `: StandardTestDispatcher`
    // annotation on this property breaks kotlinc resolution in this file.
    private var testDispatcher = StandardTestDispatcher()
    private lateinit var bills: BillRepository
    private lateinit var documents: DocumentRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var guidance: GuidancePlayer
    private lateinit var recorder: FakeRecorder
    private lateinit var voiceApi: FakeVoiceApi
    private lateinit var fakeSpeaker: FakeSpeaker
    private lateinit var gate: CameraSpeechGate
    private var clockNow = 1_000_000L

    @Before
    fun setUp() {
        // Fresh scheduler per test: listen loops from an earlier test must
        // never wake inside a later one.
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        clockNow = 1_000_000L
        bills = mockk()
        documents = mockk()
        prefs = mockk()
        guidance = mockk(relaxed = true)
        recorder = FakeRecorder()
        voiceApi = FakeVoiceApi()
        fakeSpeaker = FakeSpeaker()
        gate = CameraSpeechGate(fakeSpeaker).also { it.clock = { clockNow } }
        coEvery { prefs.get() } returns UserPreferencesEntity()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(mode: String? = null, capture: String? = null): CameraViewModel {
        val args = mutableMapOf<String, String>()
        if (mode != null) args[Routes.ARG_MODE] = mode
        if (capture != null) args[Routes.ARG_CAPTURE] = capture
        return CameraViewModel(
            context = mockk<Context>(relaxed = true),
            spark = mockk<SparkClient>(relaxed = true),
            bills = bills,
            documents = documents,
            prefs = prefs,
            speaker = mockk<Speaker>(relaxed = true),
            guidance = guidance,
            gate = gate,
            analytics = mockk<AnalyticsLogger>(relaxed = true),
            recorder = recorder,
            voiceApi = voiceApi,
            cues = mockk<TalkCues>(relaxed = true),
            ocr = mockk<OcrEngine>(relaxed = true),
            ioDispatcher = testDispatcher,
            savedStateHandle = SavedStateHandle(args),
        ).also { it.micPermissionCheck = { true } }
    }

    private fun ok(id: Long) = SaveOutcome(id, ValidationResult(emptyList()))

    /** Finishes in-flight app speech and elapses the audio-tail guard. */
    private fun finishSpeech() {
        fakeSpeaker.completeAll()
        clockNow += CameraSpeechGate.TAIL_GUARD_MS
    }

    private fun httpError(code: Int, body: String = ""): HttpException =
        HttpException(
            Response.error<TranscribeResponse>(code, body.toResponseBody("text/plain".toMediaType())),
        )

    private fun unclearError(): HttpException =
        httpError(502, """{"error":{"code":"ai_unclear","message":"unclear"}}""")

    // Mode routing

    @Test
    fun normalizeMode_explicitValues() {
        assertEquals(Routes.MODE_BILL, CameraViewModel.normalizeMode(Routes.MODE_BILL))
        assertEquals(Routes.MODE_DOC, CameraViewModel.normalizeMode(Routes.MODE_DOC))
    }

    @Test
    fun normalizeMode_missingOrInvalid_fallsBackToBill() {
        assertEquals(Routes.MODE_BILL, CameraViewModel.normalizeMode(null))
        assertEquals(Routes.MODE_BILL, CameraViewModel.normalizeMode(""))
        assertEquals(Routes.MODE_BILL, CameraViewModel.normalizeMode("xyz"))
        assertEquals(Routes.MODE_BILL, CameraViewModel.normalizeMode("BILL"))
    }

    @Test
    fun viewModel_docMode_setsDocumentState() = runTest {
        val vm = viewModel(Routes.MODE_DOC)
        advanceUntilIdle()
        assertTrue(vm.isDocumentMode)
        assertTrue(vm.uiState.value.isDocument)
    }

    @Test
    fun viewModel_missingMode_defaultsToBill() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.isDocumentMode)
        assertFalse(vm.uiState.value.isDocument)
    }

    // Repo isolation

    @Test
    fun confirmSave_billMode_writesBillsOnly() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        val captured = async { vm.captured.first() }
        runCurrent()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { bills.saveDraft(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
        vm.confirmSave()
        advanceUntilIdle()
        coVerify(exactly = 1) { bills.save(any()) }
        coVerify(exactly = 0) { bills.saveDraft(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
        val result = captured.await()
        assertEquals(7L, result.id)
        assertFalse(result.isDocument)
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
    }

    @Test
    fun confirmSave_docMode_writesDocumentsOnly() = runTest {
        coEvery { documents.save(any()) } returns ok(9L)
        val vm = viewModel(Routes.MODE_DOC)
        advanceUntilIdle()
        val captured = async { vm.captured.first() }
        runCurrent()
        vm.seedReviewForTest(doc = DocumentSummary(title = "Lease", summary = "Twelve months."))
        advanceUntilIdle()
        coVerify(exactly = 0) { documents.save(any()) }
        vm.confirmSave()
        advanceUntilIdle()
        coVerify(exactly = 1) { documents.save(any()) }
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { bills.saveDraft(any()) }
        val result = captured.await()
        assertEquals(9L, result.id)
        assertTrue(result.isDocument)
    }

    @Test
    fun confirmSave_offlineBill_usesSaveDraft() = runTest {
        coEvery { bills.saveDraft(any()) } returns ok(11L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "", amount = 10.0), offline = true)
        advanceUntilIdle()
        vm.confirmSave()
        advanceUntilIdle()
        coVerify(exactly = 1) { bills.saveDraft(any()) }
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    @Test
    fun confirmSave_withoutDraft_writesNothing() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.confirmSave()
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { bills.saveDraft(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    @Test
    fun discardDraft_writesNothing_returnsToFraming() = runTest {
        val vm = viewModel(Routes.MODE_DOC)
        advanceUntilIdle()
        vm.seedReviewForTest(doc = DocumentSummary(title = "Lease", summary = "Twelve months."))
        advanceUntilIdle()
        assertEquals(CameraPhase.REVIEW, vm.uiState.value.phase)
        vm.discardDraft()
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { bills.saveDraft(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
    }

    @Test
    fun repositories_quiet_outsideConfirm() = runTest {
        viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        confirmVerified(bills, documents)
    }

    @Test
    fun onSessionEnd_stopsGuidance_keepsCaptureGate() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        val gateBefore = vm.uiState.value.captureEnabled
        vm.onSessionEnd()
        advanceUntilIdle()
        verify { guidance.stop() }
        verify { guidance.reset() }
        assertFalse(vm.uiState.value.voiceCapture)
        assertEquals(gateBefore, vm.uiState.value.captureEnabled)
    }

    // Self-trigger guard (utterance path)

    @Test
    fun framingTtsCaptureWord_neverRecordsWhileSpeaking() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        // Guidance speaks the trigger word while armed; no recording starts.
        gate.speak("Ready. Tap capture or say capture.")
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(5_000)
        assertEquals(0, recorder.starts)
        assertEquals(0, voiceApi.calls)
        assertTrue(vm.uiState.value.voiceCapture)
        // After speech + tail, listening resumes and unknown words re-listen.
        finishSpeech()
        advanceTimeBy(2_000)
        assertTrue(startsAtLeast(1))
        assertTrue(vm.uiState.value.voiceCapture)
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        // Mirror leaving the camera: disarm so runTest can reach idle.
        vm.setVoiceCaptureEnabled(false)
        advanceUntilIdle()
    }

    private fun startsAtLeast(n: Int) = recorder.starts >= n

    @Test
    fun framingSpeechMidRecording_dropsAtDispatch() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(500)
        assertEquals(1, recorder.starts) // recording in flight, auto-stop pending
        gate.speak("Move the phone slightly to the left.") // TTS overlaps the take
        recorder.autoStopNow = true
        advanceTimeBy(1_000)
        assertEquals(0, voiceApi.calls) // dropped: never transcribed, never acts
        assertTrue(vm.uiState.value.voiceCapture)
        vm.setVoiceCaptureEnabled(false)
        advanceUntilIdle()
    }

    @Test
    fun captureCommand_rejected_whenNotReady() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        assertFalse(vm.onVoiceCaptureCommand())
        advanceUntilIdle()
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    @Test
    fun captureCommandWhileNotReady_speaksGuidance_keepsListening() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        voiceApi.hear("capture")
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(3_000)
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        assertTrue(fakeSpeaker.spoken.isNotEmpty()) // "not ready" + instruction
        assertTrue(vm.uiState.value.voiceCapture) // still armed for Ready
        coVerify(exactly = 0) { bills.save(any()) }
        vm.setVoiceCaptureEnabled(false)
        advanceUntilIdle()
    }

    @Test
    fun micFailure_speaks_disarms() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.startThrows = MicException("permission denied")
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(2_000)
        assertFalse(vm.uiState.value.voiceCapture)
        assertTrue(fakeSpeaker.spoken.isNotEmpty())
    }

    @Test
    fun authFailure_speaks_disarms() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        voiceApi.queue.add(httpError(401))
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(3_000)
        assertFalse(vm.uiState.value.voiceCapture)
        assertTrue(fakeSpeaker.spoken.isNotEmpty())
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun unclear502_relistensSilently_staysArmed() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        voiceApi.queue.add(unclearError())
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(3_000)
        assertTrue(vm.uiState.value.voiceCapture)
        assertTrue(fakeSpeaker.spoken.isEmpty())
        assertTrue(startsAtLeast(2))
        vm.setVoiceCaptureEnabled(false)
        advanceUntilIdle()
    }

    @Test
    fun plain502_speaks_disarms() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        voiceApi.queue.add(httpError(502))
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(3_000)
        assertFalse(vm.uiState.value.voiceCapture)
        assertTrue(fakeSpeaker.spoken.isNotEmpty())
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun unclearTranscript_relistensSilently() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        voiceApi.queue.add(TranscribeResponse("", 900, 1))
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(3_000)
        assertTrue(vm.uiState.value.voiceCapture)
        assertTrue(fakeSpeaker.spoken.isEmpty())
        assertTrue(startsAtLeast(2))
        vm.setVoiceCaptureEnabled(false)
        advanceUntilIdle()
    }

    // Spoken REVIEW

    @Test
    fun reviewPrompt_yesCannotSave_untilListeningResumes() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Bill from City Electric. Save it?")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.reviewListening) // prompt still speaking
        assertEquals(0, recorder.starts) // nothing recorded the prompt
        voiceApi.hear("yes")
        finishSpeech()
        advanceUntilIdle() // utterance -> SAVE ends the loop
        coVerify(exactly = 1) { bills.save(any()) }
        assertFalse(vm.uiState.value.reviewListening)
    }

    @Test
    fun reviewCommands_saveWords_save_discardWords_discard() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        suspend fun verdict(word: String): CameraPhase {
            vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
            vm.presentReview("Save it?")
            advanceUntilIdle()
            voiceApi.hear(word)
            finishSpeech()
            advanceUntilIdle() // verdict ends the loop (save or discard)
            return vm.uiState.value.phase
        }
        assertEquals(CameraPhase.FRAMING, verdict("save it"))
        coVerify(exactly = 1) { bills.save(any()) }
        assertEquals(CameraPhase.FRAMING, verdict("don't save"))
        assertEquals(CameraPhase.FRAMING, verdict("retake"))
        assertEquals(CameraPhase.FRAMING, verdict("no"))
        coVerify(exactly = 1) { bills.save(any()) } // discards wrote nothing
    }

    @Test
    fun reviewCommands_unclear502_repromptsThenSaves() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.queue.add(unclearError())
        voiceApi.hear("yes")
        finishSpeech() // prompt done -> loop starts
        advanceTimeBy(2_000) // unclear -> reprompt speaks, loop gated on it
        assertEquals(2, fakeSpeaker.spoken.size) // prompt + reprompt, no wedge
        finishSpeech() // reprompt done -> listening resumes
        advanceTimeBy(2_000) // "yes" ends the loop
        assertEquals(2, voiceApi.calls)
        coVerify(exactly = 1) { bills.save(any()) }
        assertEquals(3, fakeSpeaker.spoken.size) // + saved confirmation
        assertFalse(vm.uiState.value.reviewListening)
    }

    @Test
    fun reviewCommands_unclearCap_goesQuiet() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        repeat(3) { voiceApi.queue.add(unclearError()) }
        voiceApi.hear("yes")
        finishSpeech()
        advanceTimeBy(2_000) // unclear#1 -> reprompt#1
        finishSpeech()
        advanceTimeBy(2_000) // unclear#2 -> reprompt#2
        finishSpeech()
        advanceTimeBy(2_000) // unclear#3 capped quiet, then "yes" saves
        assertEquals(4, voiceApi.calls)
        coVerify(exactly = 1) { bills.save(any()) }
        assertEquals(4, fakeSpeaker.spoken.size) // prompt + 2 reprompts + saved
        assertFalse(vm.uiState.value.reviewListening)
    }

    @Test
    fun reviewCommands_tolerantWords_match() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        suspend fun verdict(word: String): CameraPhase {
            vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
            vm.presentReview("Save it?")
            advanceUntilIdle()
            voiceApi.hear(word)
            finishSpeech()
            advanceUntilIdle() // verdict ends the loop
            return vm.uiState.value.phase
        }
        assertEquals(CameraPhase.FRAMING, verdict("safe")) // "save" homophone
        assertEquals(CameraPhase.FRAMING, verdict("ok")) // everyday confirm
        assertEquals(CameraPhase.FRAMING, verdict("cancel")) // contract word
        assertEquals(CameraPhase.FRAMING, verdict("again")) // retake shorthand
        assertEquals(CameraPhase.FRAMING, verdict("nah")) // colloquial no
        coVerify(exactly = 2) { bills.save(any()) } // safe + ok only
    }

    @Test
    fun reviewCommands_remove_deletesNotDiscards() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("remove it")
        finishSpeech()
        advanceUntilIdle()
        assertNull(vm.uiState.value.reviewBody)
        assertEquals(CameraPhase.REVIEW, vm.uiState.value.phase) // exit, not rescan
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun reviewCommands_notSure_ignored_notOk_discards() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("not sure", "not ok")
        finishSpeech() // prompt done -> loop starts
        advanceTimeBy(2_000) // uncertainty reprompts, loop gated on it
        finishSpeech() // reprompt done -> listening resumes
        advanceTimeBy(2_000) // "not ok" discards
        assertEquals(2, voiceApi.calls) // both utterances were heard
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun reviewCommands_none_repromptsThenSaves() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("hmm maybe", "yes")
        finishSpeech() // prompt done -> loop starts
        advanceTimeBy(2_000) // unmatched words -> reprompt speaks
        assertEquals("hmm maybe", vm.uiState.value.lastHeard)
        assertEquals(2, fakeSpeaker.spoken.size) // prompt + reprompt
        finishSpeech() // reprompt done -> listening resumes
        advanceTimeBy(2_000) // "yes" ends the loop
        assertEquals(2, voiceApi.calls)
        coVerify(exactly = 1) { bills.save(any()) }
        assertNull(vm.uiState.value.lastHeard) // cleared on verdict
        assertFalse(vm.uiState.value.reviewListening)
    }

    @Test
    fun reviewCommands_mixedCap_shared() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.queue.add(unclearError())
        voiceApi.hear("hmm", "hmm", "yes")
        finishSpeech()
        advanceTimeBy(2_000) // unclear -> reprompt#1
        finishSpeech()
        advanceTimeBy(2_000) // unmatched -> reprompt#2
        finishSpeech()
        advanceTimeBy(2_000) // capped quiet, then "yes" saves
        assertEquals(4, voiceApi.calls)
        coVerify(exactly = 1) { bills.save(any()) }
        assertEquals(4, fakeSpeaker.spoken.size) // prompt + 2 reprompts + saved
    }

    @Test
    fun framingHeardNonCapture_ignoredSilently() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.setVoiceCaptureEnabled(true)
        advanceTimeBy(3_000) // default "hello there" has no capture word
        assertTrue(vm.uiState.value.voiceCapture)
        assertTrue(fakeSpeaker.spoken.isEmpty())
        assertNull(vm.uiState.value.lastHeard) // review-only display
        assertTrue(startsAtLeast(2))
        vm.setVoiceCaptureEnabled(false)
        advanceUntilIdle()
    }

    @Test
    fun reviewCommands_delete_deletesDraftAndCancels() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        val cancelled = async { vm.reviewCancelled.first() }
        runCurrent()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("delete it")
        finishSpeech()
        advanceUntilIdle() // verdict ends the loop; deleted speech pends
        finishSpeech() // spoken confirmation done -> cancel emitted
        advanceUntilIdle()
        assertEquals(1, voiceApi.calls)
        assertNull(vm.uiState.value.reviewBody)
        assertEquals(CameraPhase.REVIEW, vm.uiState.value.phase) // screen navigates
        cancelled.await() // cancel reached the (already-collecting) screen
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    @Test
    fun reviewCommands_deleteNegation_ignored() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        val cancelled = async { vm.reviewCancelled.first() }
        runCurrent()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("don't delete it", "no")
        finishSpeech() // prompt done -> loop starts
        advanceTimeBy(2_000) // negation reprompts, loop gated on it
        finishSpeech() // reprompt done -> listening resumes
        advanceTimeBy(2_000) // "no" discards
        assertEquals(2, voiceApi.calls) // both utterances were heard
        assertFalse(cancelled.isCompleted) // no cancel was emitted
        cancelled.cancel()
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    @Test
    fun utterances_useShortEndpointing() = runTest {
        assertTrue(PttAudioSpec.SHORT_END_SILENCE_MS < PttAudioSpec.END_SILENCE_MS)
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("yes")
        finishSpeech()
        advanceUntilIdle()
        assertEquals(PttAudioSpec.SHORT_END_SILENCE_MS, recorder.lastEndSilenceMs)
    }

    @Test
    fun reviewCommands_wrongState_ignored() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        recorder.autoStopNow = true
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        voiceApi.hear("capture", "maybe later")
        finishSpeech()
        advanceTimeBy(4_000) // bounded: ignored words never end the loop
        assertEquals(CameraPhase.REVIEW, vm.uiState.value.phase)
        coVerify(exactly = 0) { bills.save(any()) }
        vm.discardDraft()
        advanceUntilIdle()
    }

    @Test
    fun staleUtterance_afterDiscard_ignored() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        finishSpeech()
        runCurrent() // arming starts one utterance; auto-stop still pending
        assertTrue(vm.uiState.value.reviewListening)
        assertEquals(1, recorder.starts)
        vm.discardDraft()
        recorder.autoStopNow = true
        advanceTimeBy(2_000)
        assertEquals(0, voiceApi.calls)
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun staleUtterance_afterSessionEnd_ignored() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        finishSpeech()
        runCurrent()
        assertEquals(1, recorder.starts)
        vm.onSessionEnd()
        recorder.autoStopNow = true
        advanceTimeBy(2_000)
        assertEquals(0, voiceApi.calls)
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun repeatedConfirm_savesOnce() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.confirmSave()
        vm.confirmSave()
        advanceUntilIdle()
        coVerify(exactly = 1) { bills.save(any()) }
    }

    // Diagnostic no-capture session

    @Test
    fun normalizeCapture_onlyOffDisables() {
        assertTrue(CameraViewModel.normalizeCapture(null))
        assertTrue(CameraViewModel.normalizeCapture("on"))
        assertTrue(CameraViewModel.normalizeCapture("xyz"))
        assertFalse(CameraViewModel.normalizeCapture(Routes.CAPTURE_OFF))
    }

    @Test
    fun demoMode_captureBlocked_announced() = runTest {
        val vm = viewModel(Routes.MODE_BILL, Routes.CAPTURE_OFF)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.demoNoCapture)
        vm.capture()
        advanceUntilIdle()
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        assertTrue(fakeSpeaker.spoken.isNotEmpty())
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    @Test
    fun normalMode_captureNotBlockedByDefault() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.demoNoCapture)
    }

    // Temporary guidance-test diagnostics

    @Test
    fun diag_populatedInGuidanceTest_only() = runTest {
        every { guidance.guide(any(), any()) } returns GuidanceOutcome.SPOKEN
        val normal = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        normal.seedDiagFrameForTest(
            FramingInput(hasText = true, coverage = 0.5f, offsetX = -0.3f, minEdgeMargin = 0.1f),
            FrameMeta(width = 1280, height = 720, rotation = 0, blocks = 12),
        )
        advanceUntilIdle()
        assertNull(normal.uiState.value.diag)

        val demo = viewModel(Routes.MODE_BILL, Routes.CAPTURE_OFF)
        advanceUntilIdle()
        val logged = mutableListOf<String>()
        demo.logDiag = { logged.add(it) }
        demo.seedDiagFrameForTest(
            FramingInput(hasText = true, coverage = 0.5f, offsetX = -0.3f, minEdgeMargin = 0.1f),
            FrameMeta(width = 1280, height = 720, rotation = 0, blocks = 12),
        )
        advanceUntilIdle()
        val diag = demo.uiState.value.diag
        assertNotNull(diag)
        diag!!
        assertEquals(1280, diag.frameWidth)
        assertEquals(720, diag.frameHeight)
        assertEquals(0, diag.rotation)
        assertEquals(12, diag.blocks)
        assertEquals(0.5f, diag.coverage)
        assertEquals(-0.3f, diag.offsetX)
        assertEquals(FramingCondition.OFF_LEFT, diag.condition)
        assertEquals(GuidanceOutcome.SPOKEN, diag.outcome)
        assertTrue(diag.gateAllowed)
        assertEquals(1, logged.size)
        val line = logged[0]
        assertTrue(line.contains("frame=1280x720"))
        assertTrue(line.contains("rot=0"))
        assertTrue(line.contains("blocks=12"))
        assertTrue(line.contains("cond=OFF_LEFT"))
        assertTrue(line.contains("spoke=SPOKEN"))
    }

    @Test
    fun diag_logThrottled_sameCondition() = runTest {
        every { guidance.guide(any(), any()) } returns GuidanceOutcome.LATCHED
        val demo = viewModel(Routes.MODE_BILL, Routes.CAPTURE_OFF)
        advanceUntilIdle()
        val logged = mutableListOf<String>()
        demo.logDiag = { logged.add(it) }
        val input = FramingInput(hasText = true, coverage = 0.05f, minEdgeMargin = 0.2f)
        val meta = FrameMeta(width = 1280, height = 720, rotation = 0, blocks = 4)
        demo.seedDiagFrameForTest(input, meta)
        demo.seedDiagFrameForTest(input, meta)
        demo.seedDiagFrameForTest(input, meta)
        advanceUntilIdle()
        // Same condition within the window: one log line, live overlay kept.
        assertEquals(1, logged.size)
        assertTrue(logged[0].contains("cond=TOO_FAR"))
        val diag = demo.uiState.value.diag
        assertNotNull(diag)
        assertEquals(FramingCondition.TOO_FAR, diag!!.condition)
        assertEquals(GuidanceOutcome.LATCHED, diag.outcome)
    }

    // Compound edge guidance + demo READY phrase

    @Test
    fun compoundEdge_requestsDirectionalPhrase() = runTest {
        val requested = slot<FramingCondition>()
        every { guidance.guide(capture(requested), any()) } returns GuidanceOutcome.SPOKEN
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedDiagFrameForTest(
            FramingInput(hasText = true, coverage = 0.5f, offsetX = -0.3f, minEdgeMargin = 0.0f),
            FrameMeta(width = 480, height = 640, rotation = 0, blocks = 30),
        )
        advanceUntilIdle()
        assertEquals(FramingCondition.EDGE_LEFT, requested.captured)
        assertEquals(R.string.framing_edge_left, vm.uiState.value.instructionRes)
    }

    @Test
    fun hysteresis_dither_holdsStable_reportsRaw() = runTest {
        every { guidance.guide(any(), any()) } returns GuidanceOutcome.SPOKEN
        val demo = viewModel(Routes.MODE_BILL, Routes.CAPTURE_OFF)
        advanceUntilIdle()
        demo.logDiag = {}
        val meta = FrameMeta(width = 480, height = 640, rotation = 0, blocks = 8)
        // Far, then a dither flicker that passes entry but not the exit band.
        demo.seedDiagFrameForTest(
            FramingInput(hasText = true, coverage = 0.10f, minEdgeMargin = 0.2f),
            meta,
        )
        demo.seedDiagFrameForTest(
            FramingInput(hasText = true, coverage = 0.16f, minEdgeMargin = 0.2f),
            meta,
        )
        advanceUntilIdle()
        assertEquals(FramingCondition.TOO_FAR, demo.uiState.value.condition)
        val held = demo.uiState.value.diag
        assertNotNull(held)
        assertEquals(FramingCondition.TOO_FAR, held!!.condition)
        assertEquals(FramingCondition.READY, held.raw)
        // Clearing the band releases to the new condition.
        demo.seedDiagFrameForTest(
            FramingInput(hasText = true, coverage = 0.19f, minEdgeMargin = 0.2f),
            meta,
        )
        advanceUntilIdle()
        assertEquals(FramingCondition.READY, demo.uiState.value.condition)
    }

    @Test
    fun demoReady_requestsDemoPhrase_normalKeepsCaptureInvite() = runTest {
        every { guidance.guide(any(), any()) } returns GuidanceOutcome.SPOKEN
        val ready = FramingInput(
            hasText = true, coverage = 0.5f, offsetX = 0.02f, offsetY = -0.02f,
            minEdgeMargin = 0.2f,
        )
        val meta = FrameMeta(width = 480, height = 640, rotation = 0, blocks = 16)
        val normal = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        normal.seedDiagFrameForTest(ready, meta)
        advanceUntilIdle()
        assertEquals(FramingCondition.READY, normal.uiState.value.condition)
        assertEquals(R.string.framing_ready, normal.uiState.value.instructionRes)

        val demo = viewModel(Routes.MODE_BILL, Routes.CAPTURE_OFF)
        advanceUntilIdle()
        demo.logDiag = {}
        demo.seedDiagFrameForTest(ready, meta)
        advanceUntilIdle()
        assertEquals(FramingCondition.READY, demo.uiState.value.condition)
        assertEquals(R.string.framing_ready_demo, demo.uiState.value.instructionRes)
    }
}

