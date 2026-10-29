package com.smartcess.app.features.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Google ID-token acquisition seam (Credential Manager in prod, fake in tests). */
interface GoogleIdTokenProvider {

    enum class Failure { CANCELLED, NO_ACCOUNTS, INTERRUPTED, FAILED }

    sealed interface Result {
        data class Token(val idToken: String) : Result
        data class Error(val failure: Failure) : Result
    }

    suspend fun requestIdToken(activity: Activity): Result
}

@Singleton
class CredentialManagerIdTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : GoogleIdTokenProvider {

    private val manager by lazy { CredentialManager.create(context) }

    override suspend fun requestIdToken(activity: Activity): GoogleIdTokenProvider.Result {
        // No serverClientId: the token's audience is this app's Android OAuth
        // client, which the backend verifies (GOOGLE_ANDROID_CLIENT_ID).
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(sha256Hex(UUID.randomUUID().toString()))
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return try {
            val credential = manager.getCredential(activity, request).credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.FAILED)
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            GoogleIdTokenProvider.Result.Token(google.idToken)
        } catch (e: GetCredentialCancellationException) {
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.CANCELLED)
        } catch (e: NoCredentialException) {
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.NO_ACCOUNTS)
        } catch (e: GetCredentialInterruptedException) {
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.INTERRUPTED)
        } catch (e: Exception) {
            GoogleIdTokenProvider.Result.Error(GoogleIdTokenProvider.Failure.FAILED)
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
