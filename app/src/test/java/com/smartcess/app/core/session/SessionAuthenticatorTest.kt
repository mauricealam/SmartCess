package com.smartcess.app.core.session

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.TimeUnit

/** 401 refresh-and-retry: one refresh, one retry, then give up. */
class SessionAuthenticatorTest {

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
        var refreshPair: SessionPair? = null,
        var failRefresh: Boolean = false,
    ) : BackendAuthApi {
        var refreshCalls = 0
        override suspend fun exchange(body: GoogleExchangeRequest): SessionPair =
            error("unused")

        override suspend fun refresh(body: RefreshRequest): SessionPair {
            refreshCalls++
            if (failRefresh) throw HttpException(Response.error<Any>(401, "".toResponseBody()))
            return refreshPair ?: error("no pair")
        }

        override suspend fun signout(): SignoutResponse = error("unused")
        override suspend fun health(): HealthResponse = error("unused")
    }

    @Test
    fun unauthorized_refreshesAndRetriesOnce() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val auth = FakeAuth(refreshPair = SessionPair("fresh-token", "r2", 900))
            val sessions = SessionManager(auth, FakeStore(StoredSession("old", 0, "r1")))
            val client = OkHttpClient.Builder()
                .authenticator(SessionAuthenticator(sessions))
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            response.use {
                assertEquals(200, it.code)
            }
            assertEquals(1, auth.refreshCalls)
            server.takeRequest()
            val retried = server.takeRequest()
            assertEquals("Bearer fresh-token", retried?.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun persistentUnauthorized_givesUp() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val auth = FakeAuth(failRefresh = true)
            val sessions = SessionManager(auth, FakeStore(StoredSession("old", 0, "r1")))
            val client = OkHttpClient.Builder()
                .authenticator(SessionAuthenticator(sessions))
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            response.use {
                assertEquals(401, it.code)
            }
            assertEquals(1, auth.refreshCalls)
            server.takeRequest()
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        } finally {
            server.shutdown()
        }
    }
}
