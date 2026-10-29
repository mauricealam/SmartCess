package com.smartcess.app.core.session

import com.smartcess.app.core.ai.SessionExpiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** SmartCess session lifecycle: Google exchange, cached access, refresh, sign-out. */
@Singleton
class SessionManager @Inject constructor(
    @Named("backendPlain") private val auth: BackendAuthApi,
    private val store: SessionTokenStore,
) {

    enum class AuthState { SignedOut, SignedIn }

    private val _authState = MutableStateFlow(
        if (store.load() != null) AuthState.SignedIn else AuthState.SignedOut,
    )
    val authState: StateFlow<AuthState> = _authState

    /** Serializes refresh so parallel 401s trigger exactly one token call. */
    private val refreshMutex = Mutex()

    fun isSignedIn(): Boolean = _authState.value == AuthState.SignedIn

    suspend fun signInWithGoogle(idToken: String) {
        val pair = try {
            auth.exchange(GoogleExchangeRequest(idToken))
        } catch (e: HttpException) {
            throw SessionSignInException()
        }
        store.save(
            StoredSession(
                accessToken = pair.access_token,
                accessExpiresAtMillis = System.currentTimeMillis() + pair.expires_in * 1000,
                refreshToken = pair.refresh_token,
            ),
        )
        _authState.value = AuthState.SignedIn
    }

    /** Cached access token while fresh, else null (no network). */
    fun peekAccessToken(): String? {
        val session = store.load() ?: return null
        return session.accessToken.takeIf {
            System.currentTimeMillis() < session.accessExpiresAtMillis - SKEW_MILLIS
        }
    }

    suspend fun validAccessToken(): String {
        peekAccessToken()?.let { return it }
        return refreshNow()
    }

    suspend fun refreshNow(): String = refreshMutex.withLock {
        peekAccessToken()?.let { return it }
        val refresh = store.load()?.refreshToken ?: throw SessionExpiredException()
        try {
            val pair = auth.refresh(RefreshRequest(refresh))
            store.save(
                StoredSession(
                    accessToken = pair.access_token,
                    accessExpiresAtMillis = System.currentTimeMillis() + pair.expires_in * 1000,
                    refreshToken = pair.refresh_token,
                ),
            )
            _authState.value = AuthState.SignedIn
            pair.access_token
        } catch (e: Exception) {
            signOutLocal()
            throw SessionExpiredException()
        }
    }

    suspend fun signOut() {
        runCatching { auth.signout() }
        signOutLocal()
    }

    private fun signOutLocal() {
        store.clear()
        _authState.value = AuthState.SignedOut
    }

    private companion object {
        const val SKEW_MILLIS = 60_000L
    }
}

/** Google exchange failed (bad token, backend down). Message comes from strings. */
class SessionSignInException : Exception()
