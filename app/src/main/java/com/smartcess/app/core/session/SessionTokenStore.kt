package com.smartcess.app.core.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stored SmartCess session tokens. */
data class StoredSession(
    val accessToken: String,
    val accessExpiresAtMillis: Long,
    val refreshToken: String,
)

/** Token persistence seam (EncryptedSharedPreferences in prod, fake in tests). */
interface SessionTokenStore {
    fun save(session: StoredSession)
    fun load(): StoredSession?
    fun clear()
}

@Singleton
class EncryptedSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionTokenStore {

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(session: StoredSession) {
        prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putLong(KEY_ACCESS_EXP, session.accessExpiresAtMillis)
            .putString(KEY_REFRESH, session.refreshToken)
            .apply()
    }

    override fun load(): StoredSession? {
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        val exp = prefs.getLong(KEY_ACCESS_EXP, 0)
        return if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            null
        } else {
            StoredSession(access, exp, refresh)
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "smartcess_session_prefs"
        const val KEY_ACCESS = "access_token"
        const val KEY_ACCESS_EXP = "access_expires_at"
        const val KEY_REFRESH = "refresh_token"
    }
}
