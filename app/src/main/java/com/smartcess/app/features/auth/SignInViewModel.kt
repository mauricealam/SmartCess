package com.smartcess.app.features.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcess.app.R
import com.smartcess.app.core.session.SessionManager
import com.smartcess.app.core.session.SessionSignInException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val busy: Boolean = false,
    val errorRes: Int? = null,
    val signedIn: Boolean = false,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val provider: GoogleIdTokenProvider,
    private val sessions: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state

    fun signIn(activity: Activity) {
        if (_state.value.busy) return
        _state.value = SignInUiState(busy = true)
        viewModelScope.launch {
            when (val result = provider.requestIdToken(activity)) {
                is GoogleIdTokenProvider.Result.Token -> exchange(result.idToken)
                is GoogleIdTokenProvider.Result.Error -> when (result.failure) {
                    GoogleIdTokenProvider.Failure.CANCELLED ->
                        _state.value = SignInUiState()
                    GoogleIdTokenProvider.Failure.NO_ACCOUNTS ->
                        _state.value = SignInUiState(errorRes = R.string.sign_in_no_accounts)
                    GoogleIdTokenProvider.Failure.INTERRUPTED,
                    GoogleIdTokenProvider.Failure.FAILED
                    -> _state.value = SignInUiState(errorRes = R.string.sign_in_failed)
                }
            }
        }
    }

    private suspend fun exchange(idToken: String) {
        _state.value = try {
            sessions.signInWithGoogle(idToken)
            SignInUiState(signedIn = true)
        } catch (e: SessionSignInException) {
            SignInUiState(errorRes = R.string.sign_in_failed)
        } catch (e: Exception) {
            SignInUiState(errorRes = R.string.sign_in_failed)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorRes = null)
    }
}
