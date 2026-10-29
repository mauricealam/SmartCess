package com.smartcess.app.features.voice

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
import com.smartcess.app.core.session.HealthState
import com.smartcess.app.core.voice.MuseVoiceTranscriber
import com.smartcess.app.core.voice.TranscriptEvent
import com.smartcess.app.data.local.dao.ConversationTurnDao
import com.smartcess.app.data.local.entity.ConversationTurnEntity
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeoutException
import javax.inject.Inject

data class VoiceUiState(
    val listening: Boolean = false,
    val processing: Boolean = false,
    val partial: String = "",
    val transcript: String = "",
    val typed: String = "",
    /** Dynamic AI text (clarifying question); spoken by the ViewModel directly. */
    val message: String? = null,
    /** Static message; the screen shows and speaks it. */
    @StringRes val messageRes: Int? = null,
    @StringRes val errorRes: Int? = null,
)

/**
 * Talk flow: stream mic audio through Muse Voice Transcribe, classify the
 * final transcript with Muse Spark, then route to the owning module.
 * Typed input is the touch fallback when speech fails (spec fallback table).
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val transcriber: MuseVoiceTranscriber,
    private val spark: SparkClient,
    private val speaker: Speaker,
    private val turns: ConversationTurnDao,
    private val prefs: PreferencesRepository,
    private val analytics: AnalyticsLogger,
    monitor: BackendHealthMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Backend warm-up state for the "Preparing SmartCess…" banner. */
    val health: StateFlow<HealthState> = monitor.state

    /** Emits a [Routes] destination once an intent is classified. */
    private val _navigate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigate: SharedFlow<String> = _navigate.asSharedFlow()

    private var listenJob: Job? = null

    init {
        viewModelScope.launch { speaker.setRate(prefs.get().ttsRate) }
    }

    fun startListening() {
        if (_state.value.listening) return
        _state.update {
            it.copy(listening = true, processing = false, errorRes = null, message = null, messageRes = null)
        }
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            transcriber.transcribe().collect { event ->
                when (event) {
                    is TranscriptEvent.Partial -> _state.update { it.copy(partial = event.text) }
                    is TranscriptEvent.Final -> {
                        _state.update { it.copy(listening = false, transcript = event.text) }
                        classify(event.text)
                    }
                    is TranscriptEvent.Error -> onTranscriberError(event.message)
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        transcriber.stop()
        _state.update { it.copy(listening = false) }
    }

    fun cancel() {
        stopListening()
        speaker.stop()
        _state.value = VoiceUiState()
    }

    fun onTypedChange(value: String) {
        _state.update { it.copy(typed = value) }
    }

    fun submitTyped() {
        val text = _state.value.typed.trim()
        if (text.isEmpty() || _state.value.processing) return
        stopListening()
        _state.update { it.copy(transcript = text) }
        classify(text)
    }

    fun speak(text: String) = speaker.speak(text)

    private fun onTranscriberError(message: String) {
        val res = when {
            message.contains("Microphone permission", ignoreCase = true) -> R.string.perm_mic_rationale
            message.contains("sign in", ignoreCase = true) -> R.string.auth_sign_in_required
            message.contains("API key", ignoreCase = true) -> R.string.voice_api_unavailable
            message.contains("busy", ignoreCase = true) -> R.string.error_rate_limit
            _state.value.partial.isBlank() && _state.value.transcript.isBlank() -> R.string.voice_no_speech
            else -> R.string.voice_not_clear
        }
        fail(res)
    }

    private fun classify(text: String) {
        viewModelScope.launch {
            _state.update { it.copy(processing = true, errorRes = null, message = null, messageRes = null) }
            try {
                val result = spark.classifyIntent(text)
                turns.upsert(ConversationTurnEntity(role = "USER", text = text, intent = result.intent))
                val question = result.clarifying_question
                val route = routeForIntent(result.intent)
                when {
                    question != null -> {
                        turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = question, intent = result.intent))
                        _state.update { it.copy(processing = false, message = question) }
                        speaker.speak(question)
                        analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "clarify"))
                    }
                    route != null -> {
                        turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = "navigate:$route", intent = result.intent))
                        _state.update { it.copy(processing = false) }
                        analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "true"))
                        _navigate.emit(route)
                    }
                    else -> {
                        // general_question: stay and offer help instead of guessing a screen.
                        turns.upsert(ConversationTurnEntity(role = "ASSISTANT", text = "help", intent = result.intent))
                        _state.update { it.copy(processing = false, messageRes = R.string.voice_help) }
                        analytics.log(AnalyticsEvent.VOICE_COMMAND, mapOf("intent" to result.intent, "success" to "help"))
                    }
                }
            } catch (e: OfflineModeException) {
                fail(R.string.voice_api_unavailable)
            } catch (e: SessionExpiredException) {
                fail(R.string.auth_sign_in_required)
            } catch (e: RateLimitedException) {
                fail(R.string.error_rate_limit)
            } catch (e: TimeoutException) {
                fail(R.string.voice_timeout)
            } catch (e: AiException) {
                fail(R.string.voice_not_clear)
            } catch (e: Exception) {
                fail(R.string.error_generic)
            }
        }
    }

    private fun fail(@StringRes res: Int) {
        _state.update { it.copy(processing = false, listening = false, errorRes = res) }
        viewModelScope.launch {
            analytics.log(AnalyticsEvent.ERROR_SHOWN, mapOf("error_type" to "voice", "screen" to "voice"))
        }
    }

    companion object {
        /**
         * Intent -> owning module route (spec voice/touch parity). Intents that need
         * an id land on the module list so the user picks the item by touch.
         * Null means "stay and help" (general_question).
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
    }
}
