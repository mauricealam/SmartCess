package com.smartcess.app.features.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.smartcess.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Google ID-token acquisition seam (Credential Manager in prod, fake in tests). */
interface GoogleIdTokenProvider {

    enum class Failure { CANCELLED, NO_ACCOUNTS, INTERRUPTED, NOT_CONFIGURED, FAILED }

    sealed interface Result {
        data class Token(val idToken: String) : Result
        data class Error(val failure: Failure) : Result
    }

    suspend fun requestIdToken(activity: Activity): Result
}

@Singleton
class CredentialManagerIdTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("googleServerClientId") private val serverClientId: String,
) : GoogleIdTokenProvider {

    private val manager by lazy { CredentialManager.create(context) }

    override suspend fun requestIdToken(activity: Activity): GoogleIdTokenProvider.Result {
        // Blank ID (build without GOOGLE_SERVER_CLIENT_ID) must surface as an
        // error state: GetGoogleIdOption.build() throws on empty serverClientId.
        if (serverClientId.isBlank()) {
            return GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.NOT_CONFIGURED)
        }
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .setServerClientId(serverClientId)
                .setNonce(sha256Hex(UUID.randomUUID().toString()))
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val credential = manager.getCredential(activity, request).credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                if (BuildConfig.DEBUG) Log.w(TAG, "requestIdToken: wrong type ${credential.type}")
                return GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.FAILED)
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            if (BuildConfig.DEBUG) Log.w(TAG, "requestIdToken: token received")
            GoogleIdTokenProvider.Result.Token(google.idToken)
        } catch (e: GetCredentialCancellationException) {
            logFailure(e)
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.CANCELLED)
        } catch (e: NoCredentialException) {
            logFailure(e)
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.NO_ACCOUNTS)
        } catch (e: GetCredentialInterruptedException) {
            logFailure(e)
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.INTERRUPTED)
        } catch (e: Exception) {
            // Covers option-building (IllegalArgumentException), parsing, and
            // transport failures: tapping sign-in must never crash the app.
            logFailure(e)
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.FAILED)
        }
    }

    /**
     * Debug-only failure detail (exception type + message, never tokens or
     * PII) so on-device diagnosis does not fly blind. Silent in release.
     */
    private fun logFailure(e: Exception) {
        if (!BuildConfig.DEBUG) return
        val detail = (e as? GetCredentialException)
            ?.let { " type=${it.type} errorMessage=${it.errorMessage}" }
            ?: ""
        Log.w(TAG, "requestIdToken failed: ${e.javaClass.simpleName}: ${e.message}$detail")
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "SmartCessAuth"
    }
}
