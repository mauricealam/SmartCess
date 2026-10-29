package com.smartcess.app.features.camera

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.StringRes
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.AiException
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
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.PreferencesRepository
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

/** Camera phase: framing loop, capture/processing, or a recoverable error. */
enum class CameraPhase { FRAMING, CAPTURING, PROCESSING, ERROR }

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
)

/**
 * Guided-scan owner: framing loop -> capture -> Spark extraction -> saved bill.
 *
 * Framing is fully offline (CameraX + on-device ML Kit + TTS + haptics).
 * The network is touched exactly once: [SparkClient.extractBill] receives
 * only the final compressed capture (plus an on-device OCR hint).
 * Tapping Capture is the user's explicit confirmation for this save; the
 * Bill Watcher review screen owns validation warnings, compare, and
 * reminder confirmation downstream.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spark: SparkClient,
    private val bills: BillRepository,
    private val prefs: PreferencesRepository,
    private val speaker: Speaker,
    private val guidance: GuidancePlayer,
    private val analytics: AnalyticsLogger,
    private val transcriber: MuseVoiceTranscriber,
    private val ocr: OcrEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /** Emits the saved bill id when capture + extraction succeed. */
    private val _captured = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val captured: SharedFlow<Long> = _captured.asSharedFlow()

    val preview: Preview = Preview.Builder().build()
    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    val analyzer = TextFramingAnalyzer(
        onResult = ::onFramingInput,
        onError = { onAnalyzerError() },
    )

    private var framingStartMs = 0L
    private var firstReadyLogged = false
    private var sessionLogged = false
    private var voiceJob: Job? = null

    init {
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
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

    // Framing loop

    private fun onFramingInput(input: FramingInput) {
        if (_uiState.value.phase != CameraPhase.FRAMING) return
        if (framingStartMs == 0L) framingStartMs = System.currentTimeMillis()
        val condition = FramingEngine.evaluate(input)
        val previous = _uiState.value.condition
        val res = FramingPhrases.stringRes(condition)
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
        announce(R.string.framing_unavailable)
    }

    // Capture + extraction

    /** Touch capture. Enabled when READY, or always once framing is unavailable. */
    fun capture() {
        val state = _uiState.value
        if (!state.captureEnabled) return
        if (state.phase == CameraPhase.CAPTURING || state.phase == CameraPhase.PROCESSING) return
        stopVoiceCapture()
        _uiState.update { it.copy(phase = CameraPhase.CAPTURING, errorRes = null, voiceCapture = false) }
        val outputDir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val rawFile = File(outputDir, "bill_raw_${System.currentTimeMillis()}.jpg")
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
            it.copy(phase = CameraPhase.FRAMING, errorRes = null, isOffline = false)
        }
    }

    /** Speak + show a screen-level message (e.g. permission denied). */
    fun announce(@StringRes res: Int) {
        speaker.speak(context.getString(res))
    }

    private fun extractAndSave(rawFile: File) {
        _uiState.update { it.copy(phase = CameraPhase.PROCESSING) }
        announce(R.string.camera_processing)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var outFile: File? = null
                try {
                    val rawBytes = rawFile.readBytes()
                    val compressed = ImageCompressor.compress(rawBytes)
                    outFile = File(rawFile.parent!!, "bill_${System.currentTimeMillis()}.jpg")
                    outFile.writeBytes(compressed)
                    rawFile.delete()
                    val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                    val ocrHint = analyzer.lastRecognizedText.take(OCR_HINT_MAX).ifBlank { null }
                    val extraction = spark.extractBill(base64, ocrHint)
                    val entity = BillEntity(
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
                        imagePath = outFile.absolutePath,
                    )
                    val outcome = bills.save(entity)
                    val savedId = outcome.savedId
                    if (savedId == null) {
                        outFile.delete()
                        fail(R.string.error_extraction_failed, speak = true)
                        return@withContext
                    }
                    analytics.log(AnalyticsEvent.BILL_SCAN_COMPLETE, mapOf("saved" to "true"))
                    _captured.emit(savedId)
                    _uiState.update { it.copy(phase = CameraPhase.FRAMING) }
                } catch (e: OfflineModeException) {
                    saveOfflineDraft(outFile)
                } catch (e: SessionExpiredException) {
                    saveOfflineDraft(outFile, announceRes = R.string.auth_sign_in_required)
                } catch (e: AiException) {
                    fail(R.string.error_extraction_failed, speak = true)
                } catch (e: Exception) {
                    fail(R.string.error_capture_failed, speak = true)
                }
            }
        }
    }

    /**
     * Offline bill flow (spec §16): OCR the capture on-device, save a
     * low-confidence draft for review, and say so honestly. Framing already
     * worked offline; only the Spark step was skipped.
     */
    private suspend fun saveOfflineDraft(
        outFile: File?,
        @StringRes announceRes: Int = R.string.banner_offline,
    ) {
        val ocrText = runCatching {
            if (outFile != null) {
                ocr.recognizeText(InputImage.fromFilePath(context, Uri.fromFile(outFile)))
            } else {
                analyzer.lastRecognizedText
            }
        }.getOrNull().orEmpty()
        val draft = OfflineDrafts.billFromOcr(ocrText.ifBlank { analyzer.lastRecognizedText })
        val outcome = bills.saveDraft(
            BillEntity(
                provider = "",
                amount = draft.amount,
                currency = "",
                dueDate = draft.due_date,
                requiredAction = "review_bill",
                confidence = 0.0,
                missingFields = draft.missing_fields,
                summary = draft.summary,
                imagePath = outFile?.absolutePath,
            ),
        )
        val savedId = outcome.savedId
        if (savedId == null) {
            _uiState.update { it.copy(phase = CameraPhase.FRAMING, isOffline = true) }
            announce(announceRes)
            return
        }
        analytics.log(AnalyticsEvent.BILL_SCAN_COMPLETE, mapOf("saved" to "draft", "offline" to "true"))
        _captured.emit(savedId)
        _uiState.update { it.copy(phase = CameraPhase.FRAMING, isOffline = true) }
        announce(announceRes)
    }

    private fun fail(@StringRes res: Int, speak: Boolean) {
        _uiState.update { it.copy(phase = CameraPhase.ERROR, errorRes = res) }
        if (speak) announce(res)
    }

    override fun onCleared() {
        stopVoiceCapture()
        analyzer.close()
        guidance.stop()
        super.onCleared()
    }

    companion object {
        private const val CAPTURE_DIR = "bills"
        private const val OCR_HINT_MAX = 500
        private const val CAPTURE_WORD = "capture"
    }
}
