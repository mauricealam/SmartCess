package com.smartcess.app.features.camera

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.smartcess.app.core.ai.BillExtraction
import com.smartcess.app.core.ai.DocumentSummary
import com.smartcess.app.core.voice.MuseVoiceTranscriber
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
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bills: BillRepository
    private lateinit var documents: DocumentRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var guidance: GuidancePlayer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bills = mockk()
        documents = mockk()
        prefs = mockk()
        guidance = mockk(relaxed = true)
        coEvery { prefs.get() } returns UserPreferencesEntity()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(mode: String? = null): CameraViewModel {
        val handle = if (mode == null) SavedStateHandle() else SavedStateHandle(mapOf(Routes.ARG_MODE to mode))
        return CameraViewModel(
            context = mockk<Context>(relaxed = true),
            spark = mockk<SparkClient>(relaxed = true),
            bills = bills,
            documents = documents,
            prefs = prefs,
            speaker = mockk<Speaker>(relaxed = true),
            guidance = guidance,
            analytics = mockk<AnalyticsLogger>(relaxed = true),
            transcriber = mockk<MuseVoiceTranscriber>(relaxed = true),
            ocr = mockk<OcrEngine>(relaxed = true),
            savedStateHandle = handle,
        )
    }

    private fun ok(id: Long) = SaveOutcome(id, ValidationResult(emptyList()))

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
}
