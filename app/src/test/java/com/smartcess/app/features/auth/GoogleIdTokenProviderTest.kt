package com.smartcess.app.features.auth

import android.app.Activity
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Blank server client ID must surface as NOT_CONFIGURED, never throw. */
class GoogleIdTokenProviderTest {

    @Test
    fun blankServerClientId_returnsNotConfigured(): Unit = runBlocking {
        val provider = CredentialManagerIdTokenProvider(
            mockk<Context>(relaxed = true),
            "",
        )
        val result = provider.requestIdToken(mockk<Activity>(relaxed = true))
        assertEquals(
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.NOT_CONFIGURED),
            result,
        )
    }
}
