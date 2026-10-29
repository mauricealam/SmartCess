package com.smartcess.app.core.session

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/** Adds the cached session token when fresh; skips otherwise (authenticator retries). */
@Singleton
class SessionInterceptor @Inject constructor(
    private val sessions: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = sessions.peekAccessToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

/** On 401: refreshes the session once and retries with the new token. */
@Singleton
class SessionAuthenticator @Inject constructor(
    private val sessions: SessionManager,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val token = try {
            runBlocking { sessions.refreshNow() }
        } catch (e: Exception) {
            return null
        }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var current = response.priorResponse
        var count = 1
        while (current != null) {
            count++
            current = current.priorResponse
        }
        return count
    }
}
