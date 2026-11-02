package com.smartcess.app.features.camera

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.smartcess.app.core.ai.BillExtraction
import com.smartcess.app.core.ai.DocumentSummary
import com.smartcess.app.core.voice.MuseVoiceTranscriber
import com.smartcess.app.core.voice.TranscriptEvent
import com.smartcess.app.core.ai.OcrEngine
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.privacy.AnalyticsLogger
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.data.repository.SaveOutcome
import com.smartcess.app.data.repository.ValidationResult
import com.smartcess.app.navigation.Routes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Capture-mode routing + review-before-save regression tests.
 *
 * Bill mode must persist only through BillRepository, document mode only
 * through DocumentRepository, and nothing may be written before the user
 * confirms Save. The JVM seam is [CameraViewModel.seedReviewForTest],
 * which reaches the same REVIEW state as capture + extraction.
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

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bills: BillRepository
    private lateinit var documents: DocumentRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var guidance: GuidancePlayer
    private lateinit var transcriber: MuseVoiceTranscriber
    private lateinit var speech: MutableSharedFlow<TranscriptEvent>
    private lateinit var fakeSpeaker: FakeSpeaker
    private lateinit var gate: CameraSpeechGate
    private var clockNow = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bills = mockk()
        documents = mockk()
        prefs = mockk()
        guidance = mockk(relaxed = true)
        speech = MutableSharedFlow(extraBufferCapacity = 8)
        transcriber = mockk()
        every { transcriber.transcribe() } returns speech
        every { transcriber.stop() } returns Unit
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
            transcriber = transcriber,
            ocr = mockk<OcrEngine>(relaxed = true),
            savedStateHandle = SavedStateHandle(args),
        ).also { it.micPermissionCheck = { true } }
    }

    private fun ok(id: Long) = SaveOutcome(id, ValidationResult(emptyList()))

    private suspend fun say(text: String) {
        speech.emit(TranscriptEvent.Final(text))
    }

    /** Finishes in-flight app speech and elapses the audio-tail guard. */
    private fun finishSpeech() {
        fakeSpeaker.completeAll()
        clockNow += CameraSpeechGate.TAIL_GUARD_MS
    }

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

    // Self-trigger guard

    @Test
    fun framingTtsCaptureWord_cannotTriggerCapture() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.setVoiceCaptureEnabled(true)
        advanceUntilIdle()
        // Guidance speaks the trigger word while armed; the mic hears it.
        gate.speak("Ready. Tap capture or say capture.")
        say("capture")
        advanceUntilIdle()
        // No dispatch: still armed, still framing, nothing captured.
        assertTrue(vm.uiState.value.voiceCapture)
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        finishSpeech()
        advanceUntilIdle()
        // Framing commands are state-scoped: "yes" is not a framing command.
        say("yes")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.voiceCapture)
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
    }

    @Test
    fun captureCommand_rejected_whenNotReady() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.onVoiceCaptureCommand()
        advanceUntilIdle()
        assertEquals(CameraPhase.FRAMING, vm.uiState.value.phase)
        coVerify(exactly = 0) { bills.save(any()) }
        coVerify(exactly = 0) { documents.save(any()) }
    }

    // Spoken REVIEW

    @Test
    fun reviewPrompt_yesCannotSave_untilListeningResumes() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Bill from City Electric. Save it?")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.reviewListening) // prompt still speaking
        say("yes")
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
        finishSpeech()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.reviewListening)
        say("yes")
        advanceUntilIdle()
        coVerify(exactly = 1) { bills.save(any()) }
        assertFalse(vm.uiState.value.reviewListening)
    }

    @Test
    fun reviewCommands_saveWords_save_discardWords_discard() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        suspend fun verdict(word: String): CameraPhase {
            vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
            vm.presentReview("Save it?")
            advanceUntilIdle()
            finishSpeech()
            advanceUntilIdle()
            say(word)
            advanceUntilIdle()
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
    fun reviewCommands_wrongState_ignored() = runTest {
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        finishSpeech()
        advanceUntilIdle()
        say("capture") // framing word in REVIEW: ignored
        advanceUntilIdle()
        assertEquals(CameraPhase.REVIEW, vm.uiState.value.phase)
        say("maybe later") // not a command: ignored
        advanceUntilIdle()
        assertEquals(CameraPhase.REVIEW, vm.uiState.value.phase)
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun staleTranscript_afterDiscard_ignored() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        finishSpeech()
        advanceUntilIdle()
        vm.discardDraft()
        advanceUntilIdle()
        say("yes")
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun staleTranscript_afterSessionEnd_ignored() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "City Electric", amount = 42.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        finishSpeech()
        advanceUntilIdle()
        vm.onSessionEnd()
        advanceUntilIdle()
        say("yes")
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
    }

    @Test
    fun staleTranscript_afterNewCapture_appliesOnlyToCurrent() = runTest {
        coEvery { bills.save(any()) } returns ok(7L)
        val vm = viewModel(Routes.MODE_BILL)
        advanceUntilIdle()
        vm.seedReviewForTest(bill = BillExtraction(provider = "First", amount = 1.0))
        vm.presentReview("Save it?")
        advanceUntilIdle()
        finishSpeech()
        advanceUntilIdle()
        // A new capture replaces the session; the old collector is stale.
        vm.seedReviewForTest(bill = BillExtraction(provider = "Second", amount = 2.0))
        advanceUntilIdle()
        say("yes")
        advanceUntilIdle()
        coVerify(exactly = 0) { bills.save(any()) }
        // The current session arms fresh and accepts its own verdict.
        vm.startReviewListening()
        advanceUntilIdle()
        say("yes")
        advanceUntilIdle()
        coVerify(exactly = 1) { bills.save(any()) }
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
}
