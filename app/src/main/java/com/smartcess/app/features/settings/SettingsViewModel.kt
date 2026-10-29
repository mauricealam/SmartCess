package com.smartcess.app.features.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.ai.Speaker
import com.smartcess.app.core.privacy.PrivacyManager
import com.smartcess.app.core.session.SessionManager
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import com.smartcess.app.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class SettingsUiState(
    val signedIn: Boolean = false,
    val messageRes: Int? = null,
    val exportPayload: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val sessions: SessionManager,
    private val privacy: PrivacyManager,
    private val speaker: Speaker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val preferences: StateFlow<UserPreferencesEntity> = prefs.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferencesEntity())

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(signedIn = sessions.isSignedIn())
            speaker.setRate(prefs.get().ttsRate)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            sessions.signOut()
            _state.value = _state.value.copy(signedIn = false)
        }
    }

    fun setTtsRate(rate: Float) {
        viewModelScope.launch {
            prefs.setTtsRate(rate)
            speaker.setRate(rate)
        }
    }

    fun setBillReminderDays(days: Int) {
        viewModelScope.launch {
            prefs.update { it.copy(defaultBillReminderDays = days.coerceIn(0, 14)) }
        }
    }

    fun setAnalyticsOptIn(optIn: Boolean) {
        viewModelScope.launch { prefs.setAnalyticsOptIn(optIn) }
    }

    fun setCrashOptIn(optIn: Boolean) {
        viewModelScope.launch { prefs.update { it.copy(crashOptIn = optIn) } }
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { prefs.update { it.copy(highContrast = enabled) } }
    }

    fun setLargeText(enabled: Boolean) {
        viewModelScope.launch { prefs.update { it.copy(largeText = enabled) } }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.update { it.copy(hapticFeedbackEnabled = enabled) } }
    }

    fun setDefaultReminderTime(totalMinutes: Int) {
        viewModelScope.launch {
            prefs.update { it.copy(defaultReminderTime = totalMinutes.coerceIn(0, 1439)) }
        }
    }

    /** Writes a JSON or CSV export to the user-chosen location (spec: explicit export). */
    fun writeExportFile(uri: Uri, csv: Boolean) {
        viewModelScope.launch {
            val payload = if (csv) privacy.exportCsv() else privacy.exportAll()
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(payload.toByteArray())
                    } ?: throw IllegalStateException("No stream")
                }
            }.isSuccess
            _state.value = _state.value.copy(
                messageRes = if (ok) R.string.settings_export_saved else R.string.error_generic,
            )
        }
    }

    /** In-app feedback (spec): stored on-device; the support address plugs in later. */
    fun saveFeedback(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.openFileOutput(FEEDBACK_FILE, Context.MODE_APPEND).use {
                        it.write(("${Instant.now()} | ${text.trim().take(2000)}\n").toByteArray())
                    }
                }
            }
            _state.value = _state.value.copy(messageRes = R.string.settings_feedback_saved)
        }
    }

    companion object {
        private const val FEEDBACK_FILE = "feedback.txt"
    }

    fun exportData() {
        viewModelScope.launch {
            val payload = privacy.exportAll()
            _state.value = _state.value.copy(
                exportPayload = payload,
                messageRes = R.string.settings_export_done,
            )
        }
    }

    fun clearExport() {
        _state.value = _state.value.copy(exportPayload = null)
    }

    /** Deletes everything only after the caller shows the confirmation dialog. */
    fun confirmDeleteAll() {
        viewModelScope.launch {
            privacy.deleteAll()
            _state.value = _state.value.copy(messageRes = R.string.settings_deleted)
        }
    }
}
