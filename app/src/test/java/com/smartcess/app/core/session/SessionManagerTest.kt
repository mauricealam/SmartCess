package com.smartcess.app.core.session

import com.smartcess.app.core.ai.SessionExpiredException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Session lifecycle: exchange, cache, refresh, expiry, sign-out. */
class SessionManagerTest {

    private class FakeStore(var session: StoredSession? = null) : SessionTokenStore {
        override fun save(session: StoredSession) {
            this.session = session
        }
        override fun load(): StoredSession? = session
        override fun clear() {
            session = null
        }
    }

    private class FakeAuth(
        var exchangePair: SessionPair? = null,
        var refreshPair: SessionPair? = null,
        var failExchange: Boolean = false,
        var failRefresh: Boolean = false,
    ) : BackendAuthApi {
        var refreshCalls = 0
        var signoutCalls = 0

        override suspend fun exchange(body: GoogleExchangeRequest): SessionPair =
            if (failExchange) throw httpError(401) else exchangePair ?: error("no pair")

        override suspend fun refresh(body: RefreshRequest): SessionPair {
            refreshCalls++
            if (failRefresh) throw httpError(401)
            return refreshPair ?: error("no pair")
        }

        override suspend fun signout(): SignoutResponse {
            signoutCalls++
            return SignoutResponse(true)
        }

        override suspend fun health(): HealthResponse = HealthResponse("ok", "test")
    }

    @Test
    fun signIn_storesPairAndSignsIn(): Unit = runBlocking {
        val store = FakeStore()
        val auth = FakeAuth(exchangePair = pair("a1", "r1"))
        val sessions = SessionManager(auth, store)
        sessions.signInWithGoogle("google-token")
        assertEquals("a1", store.session?.accessToken)
        assertEquals("r1", store.session?.refreshToken)
        assertTrue(sessions.isSignedIn())
        assertEquals(SessionManager.AuthState.SignedIn, sessions.authState.value)
    }

    @Test
    fun signIn_rejected_staysSignedOut(): Unit = runBlocking {
        val store = FakeStore()
        val auth = FakeAuth(failExchange = true)
        val sessions = SessionManager(auth, store)
        var failed = false
        try {
            sessions.signInWithGoogle("bad-token")
        } catch (e: SessionSignInException) {
            failed = true
        }
        assertTrue(failed)
        assertNull(store.session)
        assertTrue(!sessions.isSignedIn())
    }

    @Test
    fun peek_returnsFreshTokenOnly() {
        val now = System.currentTimeMillis()
        val fresh = FakeStore(StoredSession("a", now + 600_000, "r"))
        assertEquals("a", SessionManager(FakeAuth(), fresh).peekAccessToken())
        val stale = FakeStore(StoredSession("a", now - 1_000, "r"))
        assertNull(SessionManager(FakeAuth(), stale).peekAccessToken())
        assertNull(SessionManager(FakeAuth(), FakeStore()).peekAccessToken())
    }

    @Test
    fun validAccess_refreshesWhenStale(): Unit = runBlocking {
        val store = FakeStore(StoredSession("old", 0, "r1"))
        val auth = FakeAuth(refreshPair = pair("a2", "r2"))
        val sessions = SessionManager(auth, store)
        assertEquals("a2", sessions.validAccessToken())
        assertEquals(1, auth.refreshCalls)
        assertEquals("r2", store.session?.refreshToken)
    }

    @Test
    fun refresh_failure_wipesAndExpires(): Unit = runBlocking {
        val store = FakeStore(StoredSession("old", 0, "r1"))
        val auth = FakeAuth(failRefresh = true)
        val sessions = SessionManager(auth, store)
        var expired = false
        try {
            sessions.validAccessToken()
        } catch (e: SessionExpiredException) {
            expired = true
        }
        assertTrue(expired)
        assertNull(store.session)
        assertTrue(!sessions.isSignedIn())
    }

    @Test
    fun signOut_wipesAndNotifiesBackend(): Unit = runBlocking {
        val store = FakeStore(StoredSession("a", System.currentTimeMillis() + 999_999, "r"))
        val auth = FakeAuth()
        val sessions = SessionManager(auth, store)
        sessions.signOut()
        assertNull(store.session)
        assertEquals(1, auth.signoutCalls)
        assertTrue(!sessions.isSignedIn())
    }

    companion object {
        private fun pair(access: String, refresh: String) =
            SessionPair(access, refresh, 900)

        private fun httpError(code: Int) =
            HttpException(Response.error<Any>(code, "".toResponseBody()))
    }
}
