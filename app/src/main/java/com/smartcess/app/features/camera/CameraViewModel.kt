package com.smartcess.app.features.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.util.Log
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
import com.smartcess.app.core.session.BackendVoiceApi
import com.smartcess.app.core.voice.MicException
import com.smartcess.app.core.voice.PttAudioSpec
import com.smartcess.app.core.voice.TalkCues
import com.smartcess.app.core.voice.TalkRecorder
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.repository.BillRepository
import com.smartcess.app.data.repository.DocumentRepository
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

/** Camera phase: framing loop, capture/processing, review-before-save, or a recoverable error. */
enum class CameraPhase { FRAMING, CAPTURING, PROCESSING, REVIEW, ERROR }

/** Spoken review verdict. Anything else is ignored in REVIEW. */
enum class ReviewCommand { SAVE, DISCARD, NONE }

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
    /** Review confirmation listening: saying yes/save or no/discard/retake acts. */
    val reviewListening: Boolean = false,
    /** Diagnostic session: framing guidance runs, capture execution is disabled. */
    val demoNoCapture: Boolean = false,
    /** Temporary guidance-test diagnostics; null in normal sessions. */
    val diag: FramingDiag? = null,
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
    private val gate: CameraSpeechGate,
    private val analytics: AnalyticsLogger,
    private val recorder: TalkRecorder,
    @Named("backendAuthed") private val voiceApi: BackendVoiceApi,
    private val cues: TalkCues,
    private val ocr: OcrEngine,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Explicit capture mode; missing or invalid values fall back to bill. */
    val captureMode: String = normalizeMode(savedStateHandle.get<String>(Routes.ARG_MODE))
    val isDocumentMode: Boolean = captureMode == Routes.MODE_DOC

    /**
     * Diagnostic switch: capture=off disables capture execution (touch and
     * voice) while guidance keeps running. Missing/invalid values keep
     * normal behavior.
     */
    val captureAllowed: Boolean = normalizeCapture(savedStateHandle.get<String>(Routes.ARG_CAPTURE))

    private val _uiState = MutableStateFlow(
        CameraUiState(isDocument = isDocumentMode, demoNoCapture = !captureAllowed),
    )
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
            onFrameMeta = { lastFrameMeta = it },
        )
    }

    /**
     * Temporary guidance-test log sink. Numbers and condition names only —
     * never OCR text, images, or document content. Tests override this
     * (android.util.Log is a stub on the JVM).
     */
    internal var logDiag: (String) -> Unit = { msg -> Log.d(TAG_DIAG, msg) }
    private var lastFrameMeta = FrameMeta()
    private var lastDiagLogMs = 0L
    private var lastDiagCondition: FramingCondition? = null

    private var framingStartMs = 0L
    private var firstReadyLogged = false
    private var sessionLogged = false
    private var stableCondition: FramingCondition? = null
    private var voiceJob: Job? = null
    private var reviewJob: Job? = null
    private var analyzerBound = false
    private var demoAnnounced = false
    /**
     * Recognition generation: every arming/disarm bumps it, and each
     * collector dispatches only while its generation is current. Stale
     * callbacks from a previous session, state, or capture can never act.
     */
    private var listenGen = 0L
    /** Review session id: a confirmation applies only to its own capture. */
    private var reviewId = 0L

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
        stableCondition = null
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
        if (!captureAllowed && !demoAnnounced) {
            demoAnnounced = true
            gate.speak(
                context.getString(
                    if (isDocumentMode) R.string.camera_demo_no_capture_doc
                    else R.string.camera_demo_no_capture_bill,
                ),
            )
        }
    }

    /**
     * Screen left while the VM may survive on the back stack: stop the mic
     * and guidance speech, forget session state, invalidate any in-flight
     * recognition. The capture-readiness gate (condition/captureEnabled) is
     * untouched.
     */
    fun onSessionEnd() {
        stopVoiceCapture()
        stopReviewListening()
        guidance.stop()
        guidance.reset()
        framingStartMs = 0L
        firstReadyLogged = false
        stableCondition = null
        _uiState.update { it.copy(voiceCapture = false) }
    }

    // Framing loop

    private fun onFramingInput(input: FramingInput) {
        if (_uiState.value.phase != CameraPhase.FRAMING) return
        if (framingStartMs == 0L) framingStartMs = System.currentTimeMillis()
        val raw = FramingEngine.evaluate(input)
        val condition = FramingEngine.update(input, stableCondition)
        stableCondition = condition
        val previous = _uiState.value.condition
        val res = FramingPhrases.stringRes(condition, isDocumentMode, captureAllowed)
        _uiState.update {
            it.copy(
                condition = condition,
                instructionRes = res,
                captureEnabled = condition == FramingCondition.READY || it.framingUnavailable,
            )
        }
        val outcome = guidance.guide(condition, context.getString(res))
        if (_uiState.value.demoNoCapture) publishDiag(input, condition, outcome, raw)
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

    /**
     * Temporary guidance-test diagnostics: overlay state every frame, logcat
     * on condition change (or every [DIAG_LOG_MS]). Numbers and condition
     * names only — never OCR text, images, or document content.
     */
    private fun publishDiag(
        input: FramingInput,
        condition: FramingCondition,
        outcome: GuidanceOutcome,
        raw: FramingCondition,
    ) {
        val meta = lastFrameMeta
        val diag = FramingDiag(
            frameWidth = meta.width,
            frameHeight = meta.height,
            rotation = meta.rotation,
            blocks = meta.blocks,
            coverage = input.coverage,
            offsetX = input.offsetX,
            offsetY = input.offsetY,
            margin = input.minEdgeMargin,
            condition = condition,
            raw = raw,
            outcome = outcome,
            gateAllowed = gate.commandsAllowed,
        )
        _uiState.update { it.copy(diag = diag) }
        val now = System.currentTimeMillis()
        if (condition != lastDiagCondition || now - lastDiagLogMs >= DIAG_LOG_MS) {
            lastDiagCondition = condition
            lastDiagLogMs = now
            logDiag(
                "frame=${meta.width}x${meta.height} rot=${meta.rotation} " +
                    "blocks=${meta.blocks} cov=${"%.3f".format(input.coverage)} " +
                    "off=${"%.3f".format(input.offsetX)}/${"%.3f".format(input.offsetY)} " +
                    "margin=${"%.3f".format(input.minEdgeMargin)} cond=$condition raw=$raw " +
                    "spoke=$outcome gate=${gate.commandsAllowed}",
            )
        }
    }

    @VisibleForTesting
    internal fun seedDiagFrameForTest(input: FramingInput, meta: FrameMeta) {
        lastFrameMeta = meta
        onFramingInput(input)
    }

    private fun onAnalyzerError() {
        // ML Kit failed: fall back to manual capture with a spoken hint.
        _uiState.update { it.copy(framingUnavailable = true, captureEnabled = true) }
        announce(if (isDocumentMode) R.string.framing_unavailable_doc else R.string.framing_unavailable)
    }

    // Capture + extraction

    /**
     * Touch capture. Enabled when READY, or always once framing is
     * unavailable. The diagnostic no-capture session blocks here: both
     * touch and voice capture funnel through this single choke point.
     * Returns true when a capture actually started.
     */
    fun capture(): Boolean {
        if (!captureAllowed) {
            gate.speak(
                context.getString(
                    if (isDocumentMode) R.string.camera_demo_no_capture_doc
                    else R.string.camera_demo_no_capture_bill,
                ),
            )
            return false
        }
        val state = _uiState.value
        if (!state.captureEnabled) return false
        if (state.phase == CameraPhase.CAPTURING || state.phase == CameraPhase.PROCESSING) return false
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
        return true
    }

    /** Voice capture: the voice agent routes a "capture" utterance here. */
    fun onVoiceCaptureCommand(): Boolean {
        return capture()
    }

    /**
     * Arms/disarms "say capture" listening while framing. Utterance-based
     * (same proven Talk pipeline: record with silence auto-stop, HTTPS
     * transcribe, dispatch). Only fires through [capture], so the READY
     * gate still applies; a command while not ready speaks guidance and
     * keeps listening instead of failing silently.
     */
    fun setVoiceCaptureEnabled(enabled: Boolean) {
        if (enabled == _uiState.value.voiceCapture) return
        _uiState.update { it.copy(voiceCapture = enabled) }
        if (!enabled) {
            stopVoiceCapture()
            return
        }
        voiceJob?.cancel()
        val gen = ++listenGen
        voiceJob = viewModelScope.launch {
            while (gen == listenGen && _uiState.value.voiceCapture) {
                when (val heard = listenUtterance(gen)) {
                    is UtteranceResult.Heard -> {
                        // No `continue`: every iteration must reach the
                        // inter-utterance delay below, otherwise ignored
                        // utterances re-listen in a zero-time spin.
                        if (gen == listenGen && gate.commandsAllowed &&
                            heard.text.contains(CAPTURE_WORD, ignoreCase = true)
                        ) {
                            if (onVoiceCaptureCommand()) {
                                _uiState.update { it.copy(voiceCapture = false) }
                            } else if (captureAllowed) {
                                speakNotReady()
                            }
                        }
                    }
                    is UtteranceResult.Silence -> Unit // no speech: listen again
                    is UtteranceResult.Fatal -> {
                        _uiState.update { it.copy(voiceCapture = false) }
                        stopVoiceCapture()
                        announce(heard.res)
                    }
                }
                delay(INTER_UTTERANCE_MS)
            }
        }
    }

    private fun stopVoiceCapture() {
        listenGen++
        voiceJob?.cancel()
        voiceJob = null
        viewModelScope.launch(ioDispatcher) { runCatching { recorder.cancel() } }
    }

    /**
     * Arms review confirmation listening after the spoken result finishes.
     * Only yes/save (confirm) and no/discard/retake (discard) act, and only
     * for the review session that armed them.
     */
    @VisibleForTesting
    internal fun startReviewListening() {
        if (!micGranted()) {
            _uiState.update { it.copy(reviewListening = false) }
            return
        }
        reviewJob?.cancel()
        val gen = ++listenGen
        val id = reviewId
        _uiState.update { it.copy(reviewListening = true) }
        reviewJob = viewModelScope.launch {
            while (gen == listenGen && id == reviewId && _uiState.value.phase == CameraPhase.REVIEW) {
                when (val heard = listenUtterance(gen)) {
                    is UtteranceResult.Heard -> {
                        // No `continue` here either: stale or muted
                        // utterances must still pace through the delay below.
                        if (gen == listenGen && id == reviewId &&
                            _uiState.value.phase == CameraPhase.REVIEW && gate.commandsAllowed
                        ) {
                            when (reviewCommand(heard.text)) {
                                ReviewCommand.SAVE -> {
                                    stopReviewListening()
                                    confirmSave()
                                }
                                ReviewCommand.DISCARD -> {
                                    stopReviewListening()
                                    discardDraft()
                                }
                                ReviewCommand.NONE -> Unit
                            }
                        }
                    }
                    is UtteranceResult.Silence -> Unit // no verdict: listen again
                    is UtteranceResult.Fatal -> {
                        stopReviewListening()
                        announce(heard.res)
                    }
                }
                delay(INTER_UTTERANCE_MS)
            }
        }
    }

    private fun stopReviewListening() {
        listenGen++
        reviewId++
        reviewJob?.cancel()
        reviewJob = null
        viewModelScope.launch(ioDispatcher) { runCatching { recorder.cancel() } }
        _uiState.update { it.copy(reviewListening = false) }
    }

    private sealed interface UtteranceResult {
        data class Heard(val text: String) : UtteranceResult
        data object Silence : UtteranceResult
        data class Fatal(@StringRes val res: Int) : UtteranceResult
    }

    /**
     * One record-transcribe round. Never records while app speech is
     * audible (no paid call on our own TTS); drops recordings overlapped
     * by speech at dispatch. Silence/unclear returns [UtteranceResult.Silence]
     * for a quiet retry; auth/network/mic failures return [UtteranceResult.Fatal].
     */
    private suspend fun listenUtterance(gen: Long): UtteranceResult {
        repeat(GATE_WAIT_ROUNDS) {
            if (gen != listenGen) return UtteranceResult.Silence
            if (gate.commandsAllowed) return recordAndUpload(gen)
            delay(GATE_WAIT_MS)
        }
        return UtteranceResult.Silence
    }

    private suspend fun recordAndUpload(gen: Long): UtteranceResult {
        try {
            recorder.start()
        } catch (e: MicException) {
            val res = if ((e.message ?: "").contains("permission", ignoreCase = true)) {
                R.string.perm_mic_rationale
            } else {
                R.string.error_generic
            }
            return UtteranceResult.Fatal(res)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UtteranceResult.Fatal(R.string.error_generic)
        }
        cues.recordingStarted()
        try {
            while (!recorder.autoStopped && gen == listenGen) {
                delay(AUTO_STOP_POLL_MS)
            }
            if (gen != listenGen) {
                runCatching { withContext(ioDispatcher) { recorder.cancel() } }
                return UtteranceResult.Silence
            }
            val rec = withContext(ioDispatcher) { recorder.stop() }
            try {
                if (rec.peak < PttAudioSpec.SILENCE_PEAK) return UtteranceResult.Silence
                if (!gate.commandsAllowed || gen != listenGen) return UtteranceResult.Silence
                val bytes = withContext(ioDispatcher) { rec.file.readBytes() }
                return UtteranceResult.Heard(upload(bytes))
            } finally {
                withContext(ioDispatcher) { runCatching { rec.file.delete() } }
            }
        } catch (e: CancellationException) {
            runCatching { withContext(ioDispatcher) { recorder.cancel() } }
            throw e
        } catch (e: UploadException) {
            return UtteranceResult.Fatal(e.messageRes)
        } catch (e: UnclearException) {
            return UtteranceResult.Silence
        } catch (e: IOException) {
            return UtteranceResult.Fatal(R.string.error_generic)
        }
    }

    private suspend fun upload(wav: ByteArray): String {
        val part = MultipartBody.Part.createFormData(
            "audio", "talk.wav", wav.toRequestBody("audio/wav".toMediaType()),
        )
        try {
            val response = withTimeout(UPLOAD_TIMEOUT_MS) { voiceApi.transcribe(part) }
            if (response.transcript.isBlank()) throw UnclearException()
            return response.transcript
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) throw UploadException(R.string.voice_timeout)
            throw e
        } catch (e: retrofit2.HttpException) {
            throw UploadException(uploadErrorRes(e))
        } catch (e: IOException) {
            throw UploadException(R.string.voice_api_unavailable)
        }
    }

    private fun uploadErrorRes(e: retrofit2.HttpException): Int {
        if (e.code() == 401) return R.string.auth_sign_in_required
        if (e.code() == 429) return R.string.error_rate_limit
        if (e.code() == 413) return R.string.talk_recording_too_long
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: ""
        if (body.contains("ai_unclear")) return R.string.voice_not_clear
        if (e.code() == 504) return R.string.voice_timeout
        return R.string.voice_api_unavailable
    }

    private fun speakNotReady() {
        gate.speak(
            context.getString(
                R.string.camera_not_ready,
                context.getString(_uiState.value.instructionRes),
            ),
        )
    }

    /** Overridable for JVM tests (Android permission statics are stubs there). */
    var micPermissionCheck: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun micGranted(): Boolean = micPermissionCheck()

    /** Back to the framing loop after an error (rescan path). */
    fun rescan() {
        gate.stop()
        framingStartMs = 0L
        firstReadyLogged = false
        _uiState.update {
            it.copy(phase = CameraPhase.FRAMING, errorRes = null, isOffline = false, reviewBody = null)
        }
    }

    /** Speak + show a screen-level message (e.g. permission denied). */
    fun announce(@StringRes res: Int) {
        gate.speak(context.getString(res))
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
        reviewId++
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
        presentReview(body, signInRequired)
    }

    /**
     * Speaks the extracted result and arms confirmation listening only
     * after the prompt finishes — the prompt's own words ("Save it?")
     * can never confirm. Persists nothing.
     */
    @VisibleForTesting
    internal fun presentReview(body: String, signInRequired: Boolean = false) {
        _uiState.update {
            if (it.phase == CameraPhase.REVIEW) it else it.copy(phase = CameraPhase.REVIEW, reviewBody = body)
        }
        gate.speak(body) {
            if (signInRequired) {
                gate.speak(context.getString(R.string.auth_sign_in_required)) {
                    startReviewListening()
                }
            } else {
                startReviewListening()
            }
        }
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
        stopReviewListening()
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
        stopReviewListening()
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

    /**
     * Whole-word review verdict. Negations and multi-word commands are
     * checked before bare "save" so "don't save it" discards.
     */
    private fun reviewCommand(text: String): ReviewCommand {
        val norm = " " + text.lowercase()
            .replace("’", "").replace("'", "")
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ") + " "
        fun has(phrase: String): Boolean = norm.contains(" $phrase ")
        return when {
            has("do not save") || has("dont save") || has("not save") ||
                has("discard") || has("retake") || has("take again") ||
                has("no") || has("nope") -> ReviewCommand.DISCARD
            has("save") || has("yes") || has("yeah") || has("yep") ||
                has("confirm") -> ReviewCommand.SAVE
            else -> ReviewCommand.NONE
        }
    }

    private fun fail(@StringRes res: Int, speak: Boolean) {
        _uiState.update { it.copy(phase = CameraPhase.ERROR, errorRes = res) }
        if (speak) announce(res)
    }

    override fun onCleared() {
        stopVoiceCapture()
        stopReviewListening()
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
        reviewId++
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
        const val TAG_DIAG = "FramingDiag"
        private const val DIAG_LOG_MS = 2_000L
        const val UPLOAD_TIMEOUT_MS = 45_000L
        private const val AUTO_STOP_POLL_MS = 200L
        private const val GATE_WAIT_MS = 300L
        private const val GATE_WAIT_ROUNDS = 20
        /** Breather between takes: lets guidance interleave, bounds call rate. */
        private const val INTER_UTTERANCE_MS = 500L

        /** Only "doc" selects document mode; missing/invalid values mean bill. */
        fun normalizeMode(raw: String?): String =
            if (raw == Routes.MODE_DOC) Routes.MODE_DOC else Routes.MODE_BILL

        /** Only capture=off disables capture; missing/invalid values keep it enabled. */
        fun normalizeCapture(raw: String?): Boolean = raw != Routes.CAPTURE_OFF
    }

    private fun captureDir(): String =
        if (isDocumentMode) CAPTURE_DIR_DOC else CAPTURE_DIR_BILL
}

private class UploadException(@StringRes val messageRes: Int) : Exception()
private class UnclearException : Exception()
