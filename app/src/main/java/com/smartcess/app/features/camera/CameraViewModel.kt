package com.smartcess.app.features.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import com.smartcess.app.ui.format.SmartCessFormat
import com.smartcess.app.core.ai.AiException
import com.smartcess.app.core.ai.BillExtraction
import com.smartcess.app.core.ai.DocumentSummary
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
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
enum class ReviewCommand { SAVE, DISCARD, DELETE, NONE }

/** Saved capture id plus which result screen owns it. */
data class CaptureResult(val id: Long, val isDocument: Boolean)

data class CameraUiState(
    val condition: FramingCondition = FramingCondition.NO_TEXT,
    @StringRes val instructionRes: Int = R.string.framing_no_text,
    val phase: CameraPhase = CameraPhase.FRAMING,
    val captureEnabled: Boolean = false,
    /** Edge analysis failed; manual capture is allowed (spec partial-failure table). */
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
    /**
     * Last review transcript, for the on-screen "I heard" line (sighted
     * transparency; TalkBack users get the spoken reprompt instead).
     * Cleared on verdict and session end; never set while framing.
     */
    val lastHeard: String? = null,
    /** Rescan warning awaiting a second explicit Save. Null when no duplicate. */
    val duplicateWarning: String? = null,
)

/**
 * Guided-scan owner: framing loop -> capture -> extraction -> review -> save.
 *
 * Framing is fully offline (CameraX + no-model edge detection + TTS + haptics).
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
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Explicit capture mode; missing or invalid values fall back to bill. */
    val captureMode: String = normalizeMode(savedStateHandle.get<String>(Routes.ARG_MODE))
    val isDocumentMode: Boolean = captureMode == Routes.MODE_DOC

    private val _uiState = MutableStateFlow(
        CameraUiState(isDocument = isDocumentMode),
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /** Emits the saved id + result owner after the user confirms Save. */
    private val _captured = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 1)
    val captured: SharedFlow<CaptureResult> = _captured.asSharedFlow()

    /** Emits after a spoken/touch delete so the screen can exit the camera. */
    private val _reviewCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reviewCancelled: SharedFlow<Unit> = _reviewCancelled.asSharedFlow()

    // CameraX objects stay lazy so the review/save logic is
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
            // Frames arrive upright with rotation 0, so edge boxes and
            // the offset math share one coordinate system by construction.
            .setOutputImageRotationEnabled(true)
            .build()
    }

    val analyzer by lazy {
        EdgeFramingAnalyzer(
            onResult = ::onFramingInput,
            onError = { onAnalyzerError() },
        )
    }

    private var framingStartMs = 0L
    private var firstReadyLogged = false
    private var sessionLogged = false
    private var stableCondition: FramingCondition? = null
    private var smoothInput: FramingInput? = null
    private var voiceJob: Job? = null
    private var reviewJob: Job? = null
    /** In-flight capture extraction: cancelled when the session ends. */
    private var extractionJob: Job? = null
    private var analyzerBound = false
    /**
     * Recognition generation: every arming/disarm bumps it, and each
     * collector dispatches only while its generation is current. Stale
     * callbacks from a previous session, state, or capture can never act.
     */
    private var listenGen = 0L
    /** Review session id: a confirmation applies only to its own capture. */
    private var reviewId = 0L
    /** Consecutive unusable review answers (garbled or unmatched). */
    private var consecutiveUnclear = 0

    /**
     * Spoken review reprompt, capped so noise can't nag forever.
     * Genuine silence resets the cap (see the review loop).
     */
    private fun repromptReview() {
        if (consecutiveUnclear < MAX_CONSECUTIVE_REPROMPT) {
            consecutiveUnclear++
            announce(R.string.camera_review_reprompt)
        }
    }

    // Unconfirmed extraction drafts. Only confirmSave() persists, and each
    // mode touches only its own repository.
    private var billDraft: BillExtraction? = null
    private var docDraft: DocumentSummary? = null
    private var draftImagePath: String? = null
    private var draftOffline: Boolean = false
    /** Duplicate warning shown for the current review: next Save commits. */
    private var duplicateWarned: Boolean = false
    /** Sync single-flight guard: the save body now suspends before clearing. */
    private var confirmInFlight: Boolean = false

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
        smoothInput = null
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
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
        extractionJob?.cancel()
        extractionJob = null
        guidance.stop()
        guidance.reset()
        framingStartMs = 0L
        firstReadyLogged = false
        stableCondition = null
        smoothInput = null
        _uiState.update { it.copy(voiceCapture = false) }
    }

    // Framing loop

    private fun onFramingInput(input: FramingInput) {
        if (_uiState.value.phase != CameraPhase.FRAMING) return
        if (framingStartMs == 0L) framingStartMs = System.currentTimeMillis()
        // EMA smoothing: edge-mass geometry jitters faster than hands move;
        // the engine decides on the smoothed stream. First frame seeds.
        smoothInput = smoothFraming(smoothInput, input)
        val effective = smoothInput!!
        val condition = FramingEngine.update(effective, stableCondition)
        stableCondition = condition
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
            if (condition == FramingCondition.READY) {
                // Capture cue: beep + vibration the moment framing locks,
                // so the user knows the mic is armed for "capture".
                cues.recordingStarted()
            }
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

    @VisibleForTesting
    internal fun seedFrameForTest(input: FramingInput) = onFramingInput(input)

    private fun onAnalyzerError() {
        // Edge analysis failed: fall back to manual capture with a spoken hint.
        _uiState.update { it.copy(framingUnavailable = true, captureEnabled = true) }
        announce(if (isDocumentMode) R.string.framing_unavailable_doc else R.string.framing_unavailable)
    }

    // Capture + extraction

    /**
     * Touch capture. Enabled when READY, or always once framing is
     * unavailable. Both touch and voice capture funnel through this
     * single choke point. Returns true when a capture actually started.
     */
    fun capture(): Boolean {
        val state = _uiState.value
        if (!state.captureEnabled) return false
        if (state.phase == CameraPhase.CAPTURING || state.phase == CameraPhase.PROCESSING) return false
        stopVoiceCapture()
        _uiState.update { it.copy(phase = CameraPhase.CAPTURING, errorRes = null, voiceCapture = false) }
        // Instant beep + vibration: the command registered, hold still.
        // Words follow once the image is secured (see extractAndSave).
        cues.recordingStarted()
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
                            } else {
                                speakNotReady()
                            }
                        }
                    }
                    is UtteranceResult.Silence -> Unit // no speech: listen again
                    // Garbled window while framing: also listen again quietly.
                    // (Review reprompts instead; see the review loop.)
                    is UtteranceResult.Unclear -> Unit
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
     * Only save (confirm), retake/discard (rescan), and delete (exit) act,
     * and only for the review session that armed them.
     */
    @VisibleForTesting
    internal fun startReviewListening() {
        consecutiveUnclear = 0
        if (!micGranted()) {
            _uiState.update { it.copy(reviewListening = false) }
            return
        }
        reviewJob?.cancel()
        val gen = ++listenGen
        val id = reviewId
        _uiState.update { it.copy(reviewListening = true, lastHeard = null) }
        reviewJob = viewModelScope.launch {
            while (gen == listenGen && id == reviewId && _uiState.value.phase == CameraPhase.REVIEW) {
                when (val heard = listenUtterance(gen)) {
                    is UtteranceResult.Heard -> {
                        // No `continue` here either: stale or muted
                        // utterances must still pace through the delay below.
                        if (gen == listenGen && id == reviewId &&
                            _uiState.value.phase == CameraPhase.REVIEW && gate.commandsAllowed
                        ) {
                            _uiState.update { it.copy(lastHeard = heard.text.take(120)) }
                            when (reviewCommand(heard.text)) {
                                ReviewCommand.SAVE -> {
                                    stopReviewListening()
                                    confirmSave()
                                }
                                ReviewCommand.DISCARD -> {
                                    stopReviewListening()
                                    discardDraft()
                                }
                                ReviewCommand.DELETE -> {
                                    stopReviewListening()
                                    deleteDraft()
                                }
                                // Heard words, no command: the user tried but
                                // STT garbled it — say so instead of silence.
                                ReviewCommand.NONE -> repromptReview()
                            }
                        }
                    }
                    is UtteranceResult.Silence -> {
                        // Genuinely quiet (not our own speech): fresh ears.
                        if (gate.commandsAllowed) consecutiveUnclear = 0
                    }
                    is UtteranceResult.Unclear -> {
                        // The user demonstrably tried (an upload happened) but
                        // no words came back: say so instead of dead silence.
                        repromptReview()
                    }
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
        _uiState.update { it.copy(reviewListening = false, lastHeard = null) }
    }

    private sealed interface UtteranceResult {
        data class Heard(val text: String) : UtteranceResult
        data object Silence : UtteranceResult
        /** An upload was made but the transcript was empty/unusable. */
        data object Unclear : UtteranceResult
        data class Fatal(@StringRes val res: Int) : UtteranceResult
    }

    /**
     * One record-transcribe round. Never records while app speech is
     * audible (no paid call on our own TTS); drops recordings overlapped
     * by speech at dispatch. Mic silence returns [UtteranceResult.Silence],
     * empty transcripts [UtteranceResult.Unclear]; auth/network/mic failures
     * return [UtteranceResult.Fatal].
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
            // Single-word commands ("capture", "save") — short endpointing
            // so the app answers ~0.5s sooner. Main Talk keeps the default.
            recorder.start(PttAudioSpec.SHORT_END_SILENCE_MS)
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
            return UtteranceResult.Unclear
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
            // Empty Meta transcript (502 ai_unclear) is just a missed
            // utterance, not an outage: re-listen quietly and stay armed.
            // Any other HTTP failure still disarms with an announcement.
            if (isUnclearResponse(e)) throw UnclearException()
            throw UploadException(uploadErrorRes(e))
        } catch (e: IOException) {
            throw UploadException(R.string.voice_api_unavailable)
        }
    }

    /** True only for the backend's empty-transcript signal (502 ai_unclear). */
    private fun isUnclearResponse(e: retrofit2.HttpException): Boolean {
        if (e.code() != 502) return false
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: ""
        return body.contains("ai_unclear")
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
        duplicateWarned = false
        _uiState.update {
            it.copy(
                phase = CameraPhase.FRAMING, errorRes = null, isOffline = false,
                reviewBody = null, duplicateWarning = null,
            )
        }
    }

    /** Speak + show a screen-level message (e.g. permission denied). */
    fun announce(@StringRes res: Int) {
        gate.speak(context.getString(res))
    }

    private fun extractAndSave(rawFile: File) {
        _uiState.update { it.copy(phase = CameraPhase.PROCESSING) }
        // Image secured: the user can move the phone; extraction runs on the file.
        announce(if (isDocumentMode) R.string.camera_captured_doc else R.string.camera_captured_bill)
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var outFile: File? = null
                try {
                    val rawBytes = rawFile.readBytes()
                    val compressed = ImageCompressor.compress(rawBytes)
                    outFile = File(rawFile.parent!!, "${captureMode}_${System.currentTimeMillis()}.jpg")
                    outFile.writeBytes(compressed)
                    rawFile.delete()
                    val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                    if (isDocumentMode) {
                        val summary = spark.summarizeDocument(null, base64)
                        enterReview(doc = summary, imagePath = outFile.absolutePath, offline = false)
                    } else {
                        val extraction = spark.extractBill(base64)
                        enterReview(bill = extraction, imagePath = outFile.absolutePath, offline = false)
                    }
                } catch (e: OfflineModeException) {
                    offlineCaptureFailed(outFile, signInRequired = false)
                } catch (e: SessionExpiredException) {
                    offlineCaptureFailed(outFile, signInRequired = true)
                } catch (e: AiException) {
                    fail(R.string.error_extraction_failed, speak = true)
                } catch (e: Exception) {
                    fail(R.string.error_capture_failed, speak = true)
                }
            }
        }
    }

    /**
     * Offline flow: without on-device OCR there is nothing truthful to
     * review, so the capture is discarded and the failure is spoken with a
     * rescan path. Nothing is persisted.
     */
    private fun offlineCaptureFailed(outFile: File?, signInRequired: Boolean) {
        runCatching { outFile?.delete() }
        fail(
            if (signInRequired) R.string.auth_sign_in_required else R.string.backend_down,
            speak = true,
        )
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
                if (bill.due_date.isBlank()) context.getString(R.string.bill_no_due_date)
                else SmartCessFormat.spokenDate(bill.due_date),
            )
            doc != null -> context.getString(
                R.string.camera_review_doc,
                doc.title.ifBlank { context.getString(R.string.documents_untitled) },
                doc.summary.take(REVIEW_SUMMARY_MAX),
            )
            else -> return
        }
        duplicateWarned = false
        _uiState.update {
            it.copy(
                phase = CameraPhase.REVIEW, reviewBody = body, isOffline = offline,
                duplicateWarning = null,
            )
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
        if (confirmInFlight) return
        confirmInFlight = true
        stopReviewListening()
        viewModelScope.launch {
            try {
                confirmSaveBody(bill, doc, imagePath, offline)
            } finally {
                // Post-completion calls are still blocked by the phase/draft
                // guards; the warning path returns early so Save-again works.
                confirmInFlight = false
            }
        }
    }

    private suspend fun confirmSaveBody(
        bill: BillExtraction?,
        doc: DocumentSummary?,
        imagePath: String?,
        offline: Boolean,
    ) {
            val outcome = if (bill != null) {
                val entity = toBillEntity(bill, imagePath)
                if (!duplicateWarned) {
                    val dupe = runCatching {
                        bills.findPossibleDuplicate(entity.amount, entity.dueDate)
                    }.getOrNull()
                    if (dupe != null) {
                        duplicateWarned = true
                        warnDuplicate(dupe)
                        return
                    }
                }
                duplicateWarned = false
                // Drafts clear only on a real save attempt: the warning path
                // above must leave them intact for the second confirmation.
                clearDrafts()
                if (offline) bills.saveDraft(entity) else bills.save(entity)
            } else {
                clearDrafts()
                documents.save(toDocumentEntity(doc!!, imagePath))
            }
            val savedId = outcome.savedId
            if (outcome.validation.hasErrors || savedId == null) {
                fail(R.string.error_extraction_failed, speak = true)
                return
            }
            analytics.log(AnalyticsEvent.BILL_SCAN_COMPLETE, mapOf("saved" to "true"))
            announce(R.string.camera_review_saved)
            _captured.emit(CaptureResult(savedId, doc != null))
            _uiState.update { it.copy(phase = CameraPhase.FRAMING, reviewBody = null, duplicateWarning = null) }
    }

    /**
     * Shows + speaks the rescan warning and re-arms review listening so the
     * user can say save again (commit) or delete (discard). Persists nothing.
     */
    private fun warnDuplicate(dupe: BillEntity) {
        val amount = SmartCessFormat.money(dupe.amount, dupe.currency)
        _uiState.update {
            it.copy(
                duplicateWarning = context.getString(
                    R.string.camera_review_duplicate_banner,
                    dupe.provider, amount, SmartCessFormat.isoDate(dupe.dueDate),
                ),
            )
        }
        gate.speak(
            context.getString(
                R.string.camera_review_duplicate_speak,
                dupe.provider, amount, SmartCessFormat.spokenDate(dupe.dueDate),
            ),
        )
        startReviewListening()
    }

    /** Discards the unconfirmed draft and returns to framing. Persists nothing. */
    fun discardDraft() {
        stopReviewListening()
        runCatching { draftImagePath?.let { File(it).delete() } }
        clearDrafts()
        rescan()
    }

    /**
     * Deletes the unconfirmed draft and exits the camera. Persists nothing.
     * The screen observes [reviewCancelled] and navigates back once the
     * spoken confirmation finishes.
     */
    fun deleteDraft() {
        stopReviewListening()
        runCatching { draftImagePath?.let { File(it).delete() } }
        clearDrafts()
        duplicateWarned = false
        _uiState.update { it.copy(reviewBody = null, duplicateWarning = null) }
        gate.speak(context.getString(R.string.camera_review_deleted)) {
            _reviewCancelled.tryEmit(Unit)
        }
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
            // STT-tolerant discard: "cancel" per the confirmation contract,
            // "not ok" as dissatisfaction with the result. Checked first so
            // waffling ("yeah no") conservatively does not save.
            has("do not save") || has("dont save") || has("not save") ||
                has("not ok") || has("not okay") ||
                has("discard") || has("retake") || has("take again") ||
                has("cancel") || has("again") ||
                has("no") || has("nope") || has("nah") -> ReviewCommand.DISCARD
            // Uncertainty is ignored, not misread as a verdict.
            has("do not delete") || has("dont delete") || has("not delete") ||
                has("not sure") -> ReviewCommand.NONE
            // "remove" is delete's everyday synonym; "deleted" its STT echo.
            has("delete") || has("remove") || has("deleted") -> ReviewCommand.DELETE
            // STT-tolerant save: "safe" is "save" homophone-confused, "saved"
            // its echo; ok/okay/sure/yup are everyday confirmations.
            has("save") || has("safe") || has("saved") ||
                has("yes") || has("yeah") || has("yep") || has("yup") ||
                has("confirm") || has("ok") || has("okay") ||
                has("sure") -> ReviewCommand.SAVE
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
        duplicateWarned = false
        _uiState.update {
            it.copy(
                phase = CameraPhase.REVIEW, reviewBody = body, isOffline = offline,
                duplicateWarning = null,
            )
        }
    }

    companion object {
        private const val CAPTURE_DIR_BILL = "bills"
        private const val CAPTURE_DIR_DOC = "docs"
        private const val CAPTURE_WORD = "capture"
        private const val REVIEW_SUMMARY_MAX = 200
        const val UPLOAD_TIMEOUT_MS = 45_000L
        private const val AUTO_STOP_POLL_MS = 200L
        private const val GATE_WAIT_MS = 300L
        /** Spoken review reprompts before going quiet against noise. */
        private const val MAX_CONSECUTIVE_REPROMPT = 2
        private const val GATE_WAIT_ROUNDS = 20
        /** Breather between takes: lets guidance interleave, bounds call rate. */
        private const val INTER_UTTERANCE_MS = 250L

        /** Only "doc" selects document mode; missing/invalid values mean bill. */
        fun normalizeMode(raw: String?): String =
            if (raw == Routes.MODE_DOC) Routes.MODE_DOC else Routes.MODE_BILL
    }

    private fun captureDir(): String =
        if (isDocumentMode) CAPTURE_DIR_DOC else CAPTURE_DIR_BILL
}

private class UploadException(@StringRes val messageRes: Int) : Exception()
private class UnclearException : Exception()
