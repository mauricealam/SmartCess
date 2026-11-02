package com.smartcess.app.features.camera

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.AiException
import com.smartcess.app.core.ai.BillExtraction
import com.smartcess.app.core.ai.ContentSafety
import com.smartcess.app.core.ai.DocumentSummary
import com.smartcess.app.core.ai.OcrEngine
import com.smartcess.app.core.ai.OfflineDrafts
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.google.mlkit.vision.common.InputImage
import com.smartcess.app.core.media.ImageCompressor
import com.smartcess.app.core.privacy.AnalyticsEvent
import com.smartcess.app.core.privacy.AnalyticsLogger
import com.smartcess.app.core.voice.MuseVoiceTranscriber
import com.smartcess.app.core.voice.TranscriptEvent
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Camera phase: framing loop, capture/processing, review-before-save, or a recoverable error. */
enum class CameraPhase { FRAMING, CAPTURING, PROCESSING, REVIEW, ERROR }

/** Saved capture id plus which result screen owns it. */
data class CaptureResult(val id: Long, val isDocument: Boolean)

data class CameraUiState(
    val condition: FramingCondition = FramingCondition.NO_TEXT,
    @StringRes val instructionRes: Int = R.string.framing_no_text,
    val phase: CameraPhase = CameraPhase.FRAMING,
    val captureEnabled: Boolean = false,
    /** ML Kit failed; manual capture is allowed (spec partial-failure table). */
    val framingUnavailable: Boolean = false,
    @StringRes val errorRes: Int? = null,
    val isOffline: Boolean = false,
    /** Voice capture armed: saying "capture" triggers [CameraViewModel.capture]. */
    val voiceCapture: Boolean = false,
    /** True when this session scans a document instead of a bill. */
    val isDocument: Boolean = false,
    /** Spoken + shown extraction summary awaiting explicit Save/Discard. Null outside REVIEW. */
    val reviewBody: String? = null,
)

/**
 * Guided-scan owner: framing loop -> capture -> extraction -> review -> save.
 *
 * Framing is fully offline (CameraX + on-device ML Kit + TTS + haptics).
 * The network is touched exactly once per capture: extraction receives
 * only the final compressed capture (plus an on-device OCR hint).
 * Capture is NOT approval to save: the extraction is shown and spoken in
 * REVIEW, and only an explicit Save persists (same approval contract as
 * the document import flow). Capture mode (bill/doc) arrives as an
 * explicit nav argument, survives restoration via SavedStateHandle, and
 * selects extraction, repository, instructions, and result screen.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spark: SparkClient,
    private val bills: BillRepository,
    private val documents: DocumentRepository,
    private val prefs: PreferencesRepository,
    private val speaker: Speaker,
    private val guidance: GuidancePlayer,
    private val analytics: AnalyticsLogger,
    private val transcriber: MuseVoiceTranscriber,
    private val ocr: OcrEngine,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Explicit capture mode; missing or invalid values fall back to bill. */
    val captureMode: String = normalizeMode(savedStateHandle.get<String>(Routes.ARG_MODE))
    val isDocumentMode: Boolean = captureMode == Routes.MODE_DOC

    private val _uiState = MutableStateFlow(CameraUiState(isDocument = isDocumentMode))
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /** Emits the saved id + result owner after the user confirms Save. */
    private val _captured = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 1)
    val captured: SharedFlow<CaptureResult> = _captured.asSharedFlow()

    // CameraX/ML Kit objects stay lazy so the review/save logic is
    // constructible on the JVM; the screen binds them via bindAnalyzer().
    val preview: Preview by lazy { Preview.Builder().build() }
    val imageCapture: ImageCapture by lazy {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val imageAnalysis: ImageAnalysis by lazy {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Frames arrive upright with rotation 0, so ML Kit boxes and
            // the offset math share one coordinate system by construction.
            .setOutputImageRotationEnabled(true)
            .build()
    }

    val analyzer by lazy {
        TextFramingAnalyzer(
            onResult = ::onFramingInput,
            onError = { onAnalyzerError() },
        )
    }

    private var framingStartMs = 0L
    private var firstReadyLogged = false
    private var sessionLogged = false
    private var voiceJob: Job? = null
    private var analyzerBound = false

    // Unconfirmed extraction drafts. Only confirmSave() persists, and each
    // mode touches only its own repository.
    private var billDraft: BillExtraction? = null
    private var docDraft: DocumentSummary? = null
    private var draftImagePath: String? = null
    private var draftOffline: Boolean = false

    init {
        viewModelScope.launch {
            val preferences = prefs.get()
            speaker.setRate(preferences.ttsRate)
            guidance.hapticsEnabled = preferences.hapticFeedbackEnabled
            if (!sessionLogged) {
                sessionLogged = true
                analytics.log(AnalyticsEvent.BILL_SCAN_START)
            }
        }
    }

    /** Attaches the framing analyzer; the screen calls this once it binds the camera. */
    fun bindAnalyzer() {
        if (analyzerBound) return
        analyzerBound = true
        guidance.reset()
        framingStartMs = 0L
        firstReadyLogged = false
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
    }

    /**
     * Screen left while the VM may survive on the back stack: stop the mic
     * and guidance speech, forget session state. The capture-readiness gate
     * (condition/captureEnabled) is untouched.
     */
    fun onSessionEnd() {
        stopVoiceCapture()
        guidance.stop()
        guidance.reset()
        framingStartMs = 0L
        firstReadyLogged = false
        _uiState.update { it.copy(voiceCapture = false) }
    }

    // Framing loop

    private fun onFramingInput(input: FramingInput) {
        if (_uiState.value.phase != CameraPhase.FRAMING) return
        if (framingStartMs == 0L) framingStartMs = System.currentTimeMillis()
        val condition = FramingEngine.evaluate(input)
        val previous = _uiState.value.condition
        val res = FramingPhrases.stringRes(condition, isDocumentMode)
        _uiState.update {
            it.copy(
                condition = condition,
                instructionRes = res,
                captureEnabled = condition == FramingCondition.READY || it.framingUnavailable,
            )
        }
        guidance.guide(condition, context.getString(res))
        if (condition != previous) {
            viewModelScope.launch {
                val metadata = mutableMapOf("condition" to condition.name)
                if (condition == FramingCondition.READY && !firstReadyLogged) {
                    firstReadyLogged = true
                    metadata["time_to_ready_ms"] = (System.currentTimeMillis() - framingStartMs).toString()
                }
                analytics.log(AnalyticsEvent.FRAMING_GUIDANCE, metadata)
            }
        }
    }

    private fun onAnalyzerError() {
        // ML Kit failed: fall back to manual capture with a spoken hint.
        _uiState.update { it.copy(framingUnavailable = true, captureEnabled = true) }
        announce(if (isDocumentMode) R.string.framing_unavailable_doc else R.string.framing_unavailable)
    }

    // Capture + extraction

    /** Touch capture. Enabled when READY, or always once framing is unavailable. */
    fun capture() {
        val state = _uiState.value
        if (!state.captureEnabled) return
        if (state.phase == CameraPhase.CAPTURING || state.phase == CameraPhase.PROCESSING) return
        stopVoiceCapture()
        _uiState.update { it.copy(phase = CameraPhase.CAPTURING, errorRes = null, voiceCapture = false) }
        val outputDir = File(context.cacheDir, captureDir()).apply { mkdirs() }
        val rawFile = File(outputDir, "${captureMode}_raw_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(rawFile).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    extractAndSave(rawFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    fail(R.string.error_capture_failed, speak = true)
                }
            },
        )
    }

    /** Voice capture: the voice agent routes a "capture" utterance here. */
    fun onVoiceCaptureCommand() {
        capture()
    }

    /**
     * Arms/disarms "say capture" listening while framing. Only fires through
     * [capture], so the READY gate still applies (spec: capture by voice
     * once framing is good).
     */
    fun setVoiceCaptureEnabled(enabled: Boolean) {
        if (enabled == _uiState.value.voiceCapture) return
        _uiState.update { it.copy(voiceCapture = enabled) }
        if (!enabled) {
            stopVoiceCapture()
            return
        }
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            transcriber.transcribe().collect { event ->
                val text = when (event) {
                    is TranscriptEvent.Partial -> event.text
                    is TranscriptEvent.Final -> event.text
                    is TranscriptEvent.Error -> {
                        _uiState.update { it.copy(voiceCapture = false) }
                        return@collect
                    }
                }
                if (text.contains(CAPTURE_WORD, ignoreCase = true)) {
                    _uiState.update { it.copy(voiceCapture = false) }
                    onVoiceCaptureCommand()
                }
            }
        }
    }

    private fun stopVoiceCapture() {
        voiceJob?.cancel()
        voiceJob = null
        transcriber.stop()
    }

    /** Back to the framing loop after an error (rescan path). */
    fun rescan() {
        speaker.stop()
        framingStartMs = 0L
        firstReadyLogged = false
        _uiState.update {
            it.copy(phase = CameraPhase.FRAMING, errorRes = null, isOffline = false, reviewBody = null)
        }
    }

    /** Speak + show a screen-level message (e.g. permission denied). */
    fun announce(@StringRes res: Int) {
        speaker.speak(context.getString(res))
    }

    private fun extractAndSave(rawFile: File) {
        _uiState.update { it.copy(phase = CameraPhase.PROCESSING) }
        announce(if (isDocumentMode) R.string.camera_processing_doc else R.string.camera_processing)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var outFile: File? = null
                try {
                    val rawBytes = rawFile.readBytes()
                    val compressed = ImageCompressor.compress(rawBytes)
                    outFile = File(rawFile.parent!!, "${captureMode}_${System.currentTimeMillis()}.jpg")
                    outFile.writeBytes(compressed)
                    rawFile.delete()
                    val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                    val ocrHint = analyzer.lastRecognizedText.take(OCR_HINT_MAX).ifBlank { null }
                    if (isDocumentMode) {
                        val summary = spark.summarizeDocument(ocrHint, base64)
                        enterReview(doc = summary, imagePath = outFile.absolutePath, offline = false)
                    } else {
                        val extraction = spark.extractBill(base64, ocrHint)
                        enterReview(bill = extraction, imagePath = outFile.absolutePath, offline = false)
                    }
                } catch (e: OfflineModeException) {
                    reviewOfflineDraft(outFile)
                } catch (e: SessionExpiredException) {
                    reviewOfflineDraft(outFile, signInRequired = true)
                } catch (e: AiException) {
                    fail(R.string.error_extraction_failed, speak = true)
                } catch (e: Exception) {
                    fail(R.string.error_capture_failed, speak = true)
                }
            }
        }
    }

    /**
     * Offline flow: OCR the capture on-device and hold a low-confidence
     * draft for review. Nothing is persisted until the user confirms Save.
     */
    private suspend fun reviewOfflineDraft(outFile: File?, signInRequired: Boolean = false) {
        val ocrText = runCatching {
            if (outFile != null) {
                ocr.recognizeText(InputImage.fromFilePath(context, Uri.fromFile(outFile)))
            } else {
                analyzer.lastRecognizedText
            }
        }.getOrNull().orEmpty().ifBlank { analyzer.lastRecognizedText }
        if (isDocumentMode) {
            enterReview(
                doc = DocumentSummary(
                    title = "",
                    type = "",
                    summary = "${ContentSafety.OFFLINE_LABEL} ${ocrText.take(500)}",
                    confidence = 0.0,
                ),
                imagePath = outFile?.absolutePath,
                offline = true,
                signInRequired = signInRequired,
            )
        } else {
            enterReview(
                bill = OfflineDrafts.billFromOcr(ocrText),
                imagePath = outFile?.absolutePath,
                offline = true,
                signInRequired = signInRequired,
            )
        }
    }

    /** Holds an extraction for explicit review. Persists nothing. */
    private suspend fun enterReview(
        bill: BillExtraction? = null,
        doc: DocumentSummary? = null,
        imagePath: String?,
        offline: Boolean,
        signInRequired: Boolean = false,
    ) {
        billDraft = bill
        docDraft = doc
        draftImagePath = imagePath
        draftOffline = offline
        val body = when {
            bill != null -> context.getString(
                R.string.camera_review_bill,
                bill.provider.ifBlank { context.getString(R.string.bill_unknown_provider) },
                billAmountText(bill),
                bill.due_date.ifBlank { context.getString(R.string.bill_no_due_date) },
            )
            doc != null -> context.getString(
                R.string.camera_review_doc,
                doc.title.ifBlank { context.getString(R.string.documents_untitled) },
                doc.summary.take(REVIEW_SUMMARY_MAX),
            )
            else -> return
        }
        _uiState.update {
            it.copy(phase = CameraPhase.REVIEW, reviewBody = body, isOffline = offline)
        }
        analytics.log(AnalyticsEvent.BILL_SCAN_COMPLETE, mapOf("saved" to "review"))
        speaker.speak(body)
        if (signInRequired) speaker.speak(context.getString(R.string.auth_sign_in_required))
    }

    /**
     * Persists the reviewed draft. Bill mode writes only BillRepository,
     * document mode only DocumentRepository — never both.
     */
    fun confirmSave() {
        val bill = billDraft
        val doc = docDraft
        val imagePath = draftImagePath
        val offline = draftOffline
        if (_uiState.value.phase != CameraPhase.REVIEW || (bill == null && doc == null)) return
        clearDrafts()
        viewModelScope.launch {
            val outcome = if (bill != null) {
                val entity = toBillEntity(bill, imagePath)
                if (offline) bills.saveDraft(entity) else bills.save(entity)
            } else {
                documents.save(toDocumentEntity(doc!!, imagePath))
            }
            val savedId = outcome.savedId
            if (outcome.validation.hasErrors || savedId == null) {
                fail(R.string.error_extraction_failed, speak = true)
                return@launch
            }
            analytics.log(AnalyticsEvent.BILL_SCAN_COMPLETE, mapOf("saved" to "true"))
            announce(R.string.camera_review_saved)
            _captured.emit(CaptureResult(savedId, doc != null))
            _uiState.update { it.copy(phase = CameraPhase.FRAMING, reviewBody = null) }
        }
    }

    /** Discards the unconfirmed draft and returns to framing. Persists nothing. */
    fun discardDraft() {
        runCatching { draftImagePath?.let { File(it).delete() } }
        clearDrafts()
        rescan()
    }

    private fun clearDrafts() {
        billDraft = null
        docDraft = null
        draftImagePath = null
        draftOffline = false
    }

    private fun toBillEntity(extraction: BillExtraction, imagePath: String?) = BillEntity(
        provider = extraction.provider,
        amount = extraction.amount,
        currency = extraction.currency,
        dueDate = extraction.due_date,
        accountReference = extraction.account_reference,
        periodStart = extraction.billing_period_start,
        periodEnd = extraction.billing_period_end,
        requiredAction = extraction.required_action,
        confidence = extraction.confidence,
        missingFields = extraction.missing_fields,
        summary = extraction.summary,
        imagePath = imagePath,
    )

    private fun toDocumentEntity(summary: DocumentSummary, imagePath: String?) = DocumentEntity(
        title = summary.title.ifBlank { summary.summary.take(40) },
        type = summary.type.ifBlank { "other" },
        summary = summary.summary,
        keyPoints = summary.key_points,
        deadline = summary.deadline,
        amount = summary.amount,
        requiredAction = summary.required_action,
        sourcePath = imagePath,
        confidence = summary.confidence,
    )

    private fun billAmountText(bill: BillExtraction): String =
        if (bill.amount < 0) {
            context.getString(R.string.bill_amount_unknown)
        } else {
            "${bill.currency} ${bill.amount}".trim()
        }

    private fun fail(@StringRes res: Int, speak: Boolean) {
        _uiState.update { it.copy(phase = CameraPhase.ERROR, errorRes = res) }
        if (speak) announce(res)
    }

    override fun onCleared() {
        stopVoiceCapture()
        if (analyzerBound) analyzer.close()
        guidance.stop()
        super.onCleared()
    }

    /**
     * JVM seam for review/save regression tests: the real path reaches the
     * same state through capture + extraction. Persists nothing.
     */
    @VisibleForTesting
    internal fun seedReviewForTest(
        bill: BillExtraction? = null,
        doc: DocumentSummary? = null,
        imagePath: String? = null,
        offline: Boolean = false,
    ) {
        billDraft = bill
        docDraft = doc
        draftImagePath = imagePath
        draftOffline = offline
        val body = when {
            bill != null -> "bill:%s".format(bill.provider)
            doc != null -> "doc:%s".format(doc.title)
            else -> return
        }
        _uiState.update { it.copy(phase = CameraPhase.REVIEW, reviewBody = body, isOffline = offline) }
    }

    companion object {
        private const val CAPTURE_DIR_BILL = "bills"
        private const val CAPTURE_DIR_DOC = "docs"
        private const val OCR_HINT_MAX = 500
        private const val CAPTURE_WORD = "capture"
        private const val REVIEW_SUMMARY_MAX = 200

        /** Only "doc" selects document mode; missing/invalid values mean bill. */
        fun normalizeMode(raw: String?): String =
            if (raw == Routes.MODE_DOC) Routes.MODE_DOC else Routes.MODE_BILL
    }

    private fun captureDir(): String =
        if (isDocumentMode) CAPTURE_DIR_DOC else CAPTURE_DIR_BILL
}
