package com.smartcess.app.features.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.AiException
import com.smartcess.app.core.ai.OfflineModeException
import com.smartcess.app.core.ai.RateLimitedException
import com.smartcess.app.core.ai.SessionExpiredException
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.ai.SparkClient
import com.smartcess.app.core.privacy.AnalyticsEvent
import com.smartcess.app.core.privacy.AnalyticsLogger
import com.smartcess.app.core.session.BackendHealthMonitor
import com.smartcess.app.core.session.BackendVoiceApi
import com.smartcess.app.core.session.HealthState
import com.smartcess.app.core.voice.MicException
import com.smartcess.app.core.voice.PttAudioSpec
import com.smartcess.app.core.voice.TalkCues
import com.smartcess.app.core.voice.TalkRecorder
import com.smartcess.app.data.local.dao.ConversationTurnDao
import com.smartcess.app.data.local.entity.ConversationTurnEntity
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Named

data class VoiceUiState(
    val recording: Boolean = false,
    val processing: Boolean = false,
    val speaking: Boolean = false,
    val transcript: String = "",
    val typed: String = "",
    /** Dynamic AI text; spoken by the ViewModel directly (never a live region). */
    val message: String? = null,
    /** Static message; the screen shows and speaks it. */
    @StringRes val messageRes: Int? = null,
    @StringRes val errorRes: Int? = null,
)

/** A spoken clarification awaiting the user's next Talk (or typed) turn. */
private data class PendingQuestion(val intent: String, val question: String)

/**
 * Talk flow: record a WAV between Talk-tap and trailing-silence auto-stop
 * (a Stop-tap still ends capture early), upload it to the backend for
 * Muse Voice Transcribe file transcription, classify the transcript with
 * Muse Spark, then SPEAK the answer, clarification, or module confirmation.
 * Typed input is the touch fallback (spec parity).
 *
 * Blind-first guarantees:
 * - Recording runs only between Talk-tap and Stop; TTS speaks only after
 *   the mic is released, so the app never hears its own speech. A new
 *   recording always stops speech first (barge-in).
 * - Talk performs no tool calls itself (classify/answer are read-only);
 *   actions happen downstream with explicit user approval, so a repeated
 *   turn can never double-execute anything.
 * - Mid-task turns are hands-free: after the app speaks a question or a
 *   read-back, listening restarts automatically when speech ends. Saving,
 *   failure, cancel, and navigation all stop the chain; a cap bounds it.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val recorder: TalkRecorder,
    @Named("backendAuthed") private val voiceApi: BackendVoiceApi,
    private val cues: TalkCues,
    private val spark: SparkClient,
    private val speaker: Speaker,
    private val turns: ConversationTurnDao,
    private val prefs: PreferencesRepository,
    private val analytics: AnalyticsLogger,
    @ApplicationContext private val appContext: Context,
    monitor: BackendHealthMonitor,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    private val actions: VoiceActionHandler,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Backend warm-up state for the "Preparing SmartCess…" banner. */
    val health: StateFlow<HealthState> = monitor.state

    /** Emits a [Routes] destination once an intent is classified. */
    private val _navigate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigate: SharedFlow<String> = _navigate.asSharedFlow()

    private var pending: PendingQuestion? = null
    private var maxDurationJob: Job? = null
    private var autoStopJob: Job? = null
    // Hands-free turn-taking: turnGen disarms stale speech callbacks (any
    // fresh tap, typed answer, or cancel invalidates them); autoTurns counts
    // consecutive auto-started turns against the cap (longest M1 flow needs
    // 3: title?, when?, read-back; +2 margin before a manual tap is asked).
    private var turnGen = 0
    private var autoTurns = 0
    internal var maxAutoTurns = 5

    /**
     * Fresh mic-grant check on every Talk tap. The screen's remembered
     * permission flag can go stale (revoked in Settings, silenced stream),
     * and on some devices AudioRecord never throws when blocked — it just
     * records zeros. Checking here guarantees we never capture silence;
     * tests override this (JVM has no permission system).
     */
    internal var micPermissionCheck: () -> Boolean = {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Phase-timing sink (durations and byte counts only — never transcripts,
     * questions, or answers). Tests override this (android.util.Log is a stub
     * on the JVM); production logs to logcat for latency diagnosis.
     */
    internal var logPhase: (String) -> Unit = { msg -> Log.d("SmartCessTalk", msg) }

    init {
        viewModelScope.launch { speaker.setRate(prefs.get().ttsRate) }
    }

    /** Talk-tap: single-flight; ignored while recording or processing. */
    fun startTalk() {
        autoTurns = 0 // a deliberate tap restarts the hands-free allowance
        startTalkInternal()
    }

    /** Hands-free next turn: same capture as a tap, minus the allowance reset. */
    private fun autoListen() {
        startTalkInternal()
    }

    private fun startTalkInternal() {
        turnGen++ // any fresh capture disarms stale speech callbacks
        if (_state.value.recording || _state.value.processing) return
        if (!micPermissionCheck()) {
            // Blocked mic: say so (the screen speaks + shows this) instead
            // of recording silence. Pending questions survive the tap.
            _state.update { it.copy(errorRes = R.string.perm_mic_rationale) }
            return
        }
        speaker.stop()
        // pending is deliberately kept: a fresh Talk after a spoken question
        // is that question's answer.
        _state.update {
            it.copy(errorRes = null, message = null, messageRes = null, transcript = "")
        }
        try {
            recorder.start()
        } catch (e: MicException) {
            val res = if ((e.message ?: "").contains("permission", ignoreCase = true)) {
                R.string.perm_mic_rationale
            } else {
                R.string.error_generic
            }
            _state.update { it.copy(errorRes = res) }
            return
        }
        cues.recordingStarted()
        _state.update { it.copy(recording = true) }
        maxDurationJob?.cancel()
        maxDurationJob = viewModelScope.launch {
            delay(PttAudioSpec.MAX_DURATION_MS + 2_000)
            if (_state.value.recording) stopTalk()
        }
        // Silence auto-stop: submit without a Stop-tap so blind users never
        // hunt for the button. Manual Stop/cancel win by cancelling this.
        autoStopJob?.cancel()
        autoStopJob = viewModelScope.launch {
            while (!recorder.autoStopped && _state.value.recording) {
                delay(AUTO_STOP_POLL_MS)
            }
            if (_state.value.recording) stopTalk()
        }
    }

    /** Stop-tap (or auto-stop): ends capture, uploads, transcribes, answers. */
    fun stopTalk() {
        if (!_state.value.recording) return
        maxDurationJob?.cancel()
        maxDurationJob = null
        autoStopJob?.cancel()
        autoStopJob = null
        _state.update { it.copy(recording = false, processing = true) }
        viewModelScope.launch {
            val rec = try {
                withContext(ioDispatcher) { recorder.stop() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(R.string.error_generic)
                return@launch
            }
            try {
                if (rec.peak < PttAudioSpec.SILENCE_PEAK) {
                    fail(R.string.voice_no_speech)
                    return@launch
                }
                val bytes = withContext(ioDispatcher) { rec.file.readBytes() }
                val uploadStart = System.currentTimeMillis()
                val transcript = upload(bytes)
                logPhase("upload=${System.currentTimeMillis() - uploadStart}ms bytes=${bytes.size}")
                _state.update { it.copy(transcript = transcript) }
                classify(transcript)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UploadException) {
                fail(e.messageRes)
            } catch (e: UnclearException) {
                fail(R.string.voice_not_clear)
            } catch (e: IOException) {
                // The capture file vanished (or is unreadable) between stop
                // and upload: a spoken error, never a crashed turn.
                fail(R.string.error_generic)
            } finally {
                withContext(ioDispatcher) { runCatching { rec.file.delete() } }
            }
        }
    }

    fun cancel() {
        maxDurationJob?.cancel()
        maxDurationJob = null
        autoStopJob?.cancel()
        autoStopJob = null
        turnGen++ // a cancelled task must never auto-listen when speech ends
        pending = null
        actions.discard()
        viewModelScope.launch(ioDispatcher) { runCatching { recorder.cancel() } }
        speaker.stop()
        _state.value = VoiceUiState()
    }

    fun onTypedChange(value: String) {
        _state.update { it.copy(typed = value) }
    }

    fun submitTyped() {
        val text = _state.value.typed.trim()
        if (text.isEmpty()) return
        if (_state.value.processing) return
        turnGen++ // a typed answer disarms the spoken question's callback
        if (_state.value.recording) {
            maxDurationJob?.cancel()
            maxDurationJob = null
            viewModelScope.launch(ioDispatcher) { runCatching { recorder.cancel() } }
            _state.update { it.copy(recording = false) }
        }
        _state.update { it.copy(transcript = text) }
        classify(text, fromVoice = false)
    }

    fun speak(text: String) = speaker.speak(text)

    override fun onCleared() {
        // The speaker is app-scoped and may outlive this screen; its
        // completion callback must never start the mic with no UI present.
        turnGen++
        super.onCleared()
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
        // Backend distinguishes unclear transcripts (502 ai_unclear) from
        // outages; sniff the code so silence speaks as silence.
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: ""
        if (body.contains("ai_unclear")) return R.string.voice_not_clear
        if (e.code() == 504) return R.string.voice_timeout
        return R.string.voice_api_unavailable
    }

    private fun classify(text: String, fromVoice: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(processing = true, errorRes = null, message = null, messageRes = null) }
            // An in-progress voice action owns its follow-up turns (confirm /
            // cancel / more details). Typed input flows through the same path.
            if (actions.hasPending) {
                val actionStart = System.currentTimeMillis()
                val turn = actions.followUp(text)
                if (turn != null) {
                    logPhase("action=${System.currentTimeMillis() - actionStart}ms saved=${turn.saved}")
                    _state.update { it.copy(processing = false, message = turn.speak) }
                    speakAndTrack(turn.speak, expectReply = !turn.saved && actions.hasPending)
                    analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to "voice_action", "success" to if (turn.saved) "saved" else "prompt"))
                    return@launch
                }
            }
            try {
                // Follow-up turns carry the pending question + recent turns so
                // the task survives across Talk taps; fresh turns classify raw.
                val context = pending?.let { "Assistant asked: ${it.question} (intent ${it.intent})." }
                val history = runCatching {
                    turns.all().takeLast(6).joinToString("\n") { "${it.role}: ${it.text.take(200)}" }
                }.getOrDefault("")
                val fullContext = listOfNotNull(context, history.ifBlank { null })
                    .joinToString("\n").ifBlank { null }
                // Fast path: crisp commands skip AI classification entirely.
                val fast = VoiceFastPath.route(text)
                if (fast != null && VoiceActionIntents.isAction(fast)) {
                    logPhase("route=fastpath intent=$fast")
                    turns.upsert(ConversationTurnEntity(role = "USER", text = text, intent = fast))
                    runAction(fast, text, fullContext)
                    return@launch
                }
                val classifyStart = System.currentTimeMillis()
                val result = spark.classifyIntent(text, fullContext)
                logPhase("classify=${System.currentTimeMillis() - classifyStart}ms intent=${result.intent}")
                turns.upsert(ConversationTurnEntity(role = "USER", text = text, intent = result.intent))
                val question = result.clarifying_question
                val route = routeForIntent(result.intent)
                when {
                    // Action intents own their follow-ups even when the
                    // classifier also asks a question: the executor extracts
                    // what was said and asks its own precise slot questions,
                    // so the task survives across turns. Checking the question
                    // first would orphan the task (next tap starts over).
                    VoiceActionIntents.isAction(result.intent) -> {
                        runAction(result.intent, text, fullContext)
                    }
                    question != null -> {
                        pending = PendingQuestion(result.intent, question)
                        turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = question, intent = result.intent))
                        _state.update { it.copy(processing = false, message = question) }
                        speakAndTrack(question, expectReply = true)
                        analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "clarify"))
                    }
                    route != null -> {
                        pending = null
                        turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = "navigate:$route", intent = result.intent))
                        _state.update { it.copy(processing = false) }
                        // Spoken first (TTS is app-scoped and continues across
                        // screens), then hand off; the module confirms actions.
                        speaker.speak(appContext.getString(confirmationRes(result.intent)))
                        analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "true"))
                        _navigate.emit(route)
                    }
                    else -> {
                        pending = null
                        val answerStart = System.currentTimeMillis()
                        val answer = spark.answer(text, fullContext)
                        logPhase("answer=${System.currentTimeMillis() - answerStart}ms chars=${answer.length}")
                        if (answer.isBlank()) {
                            // Empty model reply: offer help instead of silence.
                            turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = "help", intent = result.intent))
                            _state.update { it.copy(processing = false, messageRes = R.string.voice_help) }
                            analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "help"))
                        } else {
                            turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = answer, intent = result.intent))
                            _state.update { it.copy(processing = false, message = answer) }
                            speakAndTrack(answer)
                            analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "answer"))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OfflineModeException) {
                fail(R.string.voice_api_unavailable, fromVoice)
            } catch (e: SessionExpiredException) {
                fail(R.string.auth_sign_in_required, fromVoice)
            } catch (e: RateLimitedException) {
                fail(R.string.error_rate_limit, fromVoice)
            } catch (e: TimeoutException) {
                fail(R.string.voice_timeout, fromVoice)
            } catch (e: AiException) {
                fail(R.string.voice_not_clear, fromVoice)
            } catch (e: Exception) {
                fail(R.string.error_generic, fromVoice)
            }
        }
    }

    /** Starts an action intent (shared by the fast route and AI classification). */
    private suspend fun runAction(intent: String, text: String, fullContext: String?) {
        pending = null // the action owns its follow-ups now
        val actionStart = System.currentTimeMillis()
        val turn = actions.start(intent, text, fullContext)
        logPhase("action=${System.currentTimeMillis() - actionStart}ms saved=${turn.saved}")
        _state.update { it.copy(processing = false, message = turn.speak) }
        speakAndTrack(turn.speak, expectReply = !turn.saved && actions.hasPending)
        analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to intent, "success" to "prompt"))
    }

    /**
     * Speaks dynamic AI text and tracks the Speaking state for the screen.
     * When a reply is expected (a question was asked or an action awaits
     * details/confirmation), listening restarts automatically once speech
     * ends — unless a newer turn, cancel, or the cap intervened.
     */
    private fun speakAndTrack(text: String, expectReply: Boolean = false) {
        _state.update { it.copy(speaking = true) }
        val gen = turnGen
        speaker.speak(text) {
            _state.update { it.copy(speaking = false) }
            if (expectReply && gen == turnGen && autoTurns < maxAutoTurns) {
                autoTurns++
                autoListen()
            }
        }
    }

    private fun fail(@StringRes res: Int, fromVoice: Boolean = true) {
        // Thinking pauses are not errors: silence while a task awaits its
        // reply goes idle quietly, keeping the pending task untouched.
        if (fromVoice && res == R.string.voice_no_speech && actions.hasPending) {
            _state.update { it.copy(processing = false, recording = false, speaking = false) }
            return
        }
        // Hearing-flavored errors make no sense for typed input.
        val shown = if (!fromVoice && (res == R.string.voice_no_speech || res == R.string.voice_not_clear)) {
            R.string.error_generic
        } else {
            res
        }
        _state.update { it.copy(processing = false, recording = false, speaking = false, errorRes = shown) }
        viewModelScope.launch {
            analytics.log(AnalyticsEvent.ERROR_SHOWN, mapOf("error_type" to "voice", "screen" to "voice"))
        }
    }

    companion object {
        const val UPLOAD_TIMEOUT_MS = 45_000L
        private const val AUTO_STOP_POLL_MS = 200L

        /**
         * Intent -> owning module route (spec voice/touch parity). Intents that need
         * an id land on the module list so the user picks the item by touch.
         * Null means "stay and answer" (general_question).
         */
        fun routeForIntent(intent: String): String? = when (intent) {
            "daily_briefing" -> Routes.BRIEFING
            "scan_bill" -> Routes.CAMERA
            "read_document" -> Routes.DOCUMENTS
            "email_briefing" -> Routes.EMAIL
            "ask_bill_question" -> Routes.BILLS
            "create_reminder" -> Routes.BILLS
            "cancel_reminder" -> Routes.BILLS
            "archive_bill" -> Routes.BILLS
            "add_appointment" -> Routes.APPOINTMENTS
            "add_routine" -> Routes.ROUTINES
            else -> null
        }

        /** Short spoken confirmation per destination (heard while it opens). */
        @StringRes
        fun confirmationRes(intent: String): Int = when (intent) {
            "daily_briefing" -> R.string.talk_open_briefing
            "scan_bill" -> R.string.talk_open_camera
            "read_document" -> R.string.talk_open_documents
            "email_briefing" -> R.string.talk_open_email
            "ask_bill_question", "create_reminder", "cancel_reminder", "archive_bill" -> R.string.talk_open_bills
            "add_appointment" -> R.string.talk_open_appointments
            "add_routine" -> R.string.talk_open_routines
            else -> R.string.talk_opening
        }
    }
}

private class UploadException(@StringRes val messageRes: Int) : Exception()
private class UnclearException : Exception()
