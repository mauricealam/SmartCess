package com.smartcess.app.features.settings

import androidx.lifecycle.ViewModel
import com.smartcess.app.R
import com.smartcess.app.core.config.ApiKeyProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class DevUiState(
    val keyText: String = "",
    val hasKey: Boolean = false,
    val messageRes: Int? = null,
)

/**
 * Hidden developer fallback: a stored Meta key bypasses the SmartCess backend
 * and talks to Meta directly. Reachable only via 7 taps on the Settings
 * version row; never part of the production user flow.
 */
@HiltViewModel
class DevViewModel @Inject constructor(
    private val keys: ApiKeyProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(DevUiState(hasKey = keys.getStoredKey() != null))
    val state: StateFlow<DevUiState> = _state.asStateFlow()

    fun onKeyChange(value: String) {
        _state.value = _state.value.copy(keyText = value, messageRes = null)
    }

    fun save() {
        val key = _state.value.keyText.trim()
        if (key.isEmpty()) return
        keys.setMetaApiKey(key)
        _state.value = _state.value.copy(
            keyText = "",
            hasKey = true,
            messageRes = R.string.dev_key_saved,
        )
    }

    fun clear() {
        keys.clearMetaApiKey()
        _state.value = _state.value.copy(
            keyText = "",
            hasKey = false,
            messageRes = R.string.dev_key_cleared,
        )
    }
}
