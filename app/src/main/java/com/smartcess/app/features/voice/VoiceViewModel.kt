package com.smartcess.app.features.voice

import android.content.Context
import androidx.annotation.StringRes
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
 * Talk flow (push-to-talk): record a WAV on Stop-tap, upload it to the
 * backend for Muse Voice Transcribe file transcription, classify the
 * transcript with Muse Spark, then SPEAK the answer, clarification, or
 * module confirmation. Typed input is the touch fallback (spec parity).
 *
 * Blind-first guarantees:
 * - Recording runs only between Talk-tap and Stop; TTS speaks only after
 *   the mic is released, so the app never hears its own speech. A new
 *   recording always stops speech first (barge-in).
 * - Talk performs no tool calls itself (classify/answer are read-only);
 *   actions happen downstream with explicit user approval, so a repeated
 *   turn can never double-execute anything.
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

    init {
        viewModelScope.launch { speaker.setRate(prefs.get().ttsRate) }
    }

    /** Talk-tap: single-flight; ignored while recording or processing. */
    fun startTalk() {
        if (_state.value.recording || _state.value.processing) return
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
    }

    /** Stop-tap: ends capture, uploads, transcribes, answers. */
    fun stopTalk() {
        if (!_state.value.recording) return
        maxDurationJob?.cancel()
        maxDurationJob = null
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
                val transcript = upload(bytes)
                _state.update { it.copy(transcript = transcript) }
                classify(transcript)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UploadException) {
                fail(e.messageRes)
            } catch (e: UnclearException) {
                fail(R.string.voice_not_clear)
            } finally {
                withContext(ioDispatcher) { runCatching { rec.file.delete() } }
            }
        }
    }

    fun cancel() {
        maxDurationJob?.cancel()
        maxDurationJob = null
        pending = null
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
            try {
                // Follow-up turns carry the pending question + recent turns so
                // the task survives across Talk taps; fresh turns classify raw.
                val context = pending?.let { "Assistant asked: ${it.question} (intent ${it.intent})." }
                val history = runCatching {
                    turns.all().takeLast(6).joinToString("\n") { "${it.role}: ${it.text.take(200)}" }
                }.getOrDefault("")
                val fullContext = listOfNotNull(context, history.ifBlank { null })
                    .joinToString("\n").ifBlank { null }
                val result = spark.classifyIntent(text, fullContext)
                turns.upsert(ConversationTurnEntity(role = "USER", text = text, intent = result.intent))
                val question = result.clarifying_question
                val route = routeForIntent(result.intent)
                when {
                    question != null -> {
                        pending = PendingQuestion(result.intent, question)
                        turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = question, intent = result.intent))
                        _state.update { it.copy(processing = false, message = question) }
                        speakAndTrack(question)
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
                        val answer = spark.answer(text, fullContext)
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

    /** Speaks dynamic AI text and tracks the Speaking state for the screen. */
    private fun speakAndTrack(text: String) {
        _state.update { it.copy(speaking = true) }
        speaker.speak(text) {
            _state.update { it.copy(speaking = false) }
        }
    }

    private fun fail(@StringRes res: Int, fromVoice: Boolean = true) {
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
