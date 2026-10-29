package com.smartcess.app.core.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.smartcess.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * API key handling. No hard-coded keys anywhere.
 *
 * Resolution order:
 * 1. Key stored in EncryptedSharedPreferences (no in-app entry exists now).
 * 2. BuildConfig.META_API_KEY from process env / local.properties / .env (debug only; release forces empty).
 * 3. Null when neither is present (features must degrade to offline mode).
 */
interface ApiKeyProvider {
    fun getMetaApiKey(): String?

    /**
     * Developer-override key from encrypted storage ONLY (hidden dev screen).
     * Null in the production flow: transport selection must use this, never
     * [getMetaApiKey] (which also sees build-time config).
     */
    fun getStoredKey(): String?
    fun setMetaApiKey(key: String)
    fun clearMetaApiKey()
    fun hasMetaApiKey(): Boolean = getMetaApiKey() != null
}

class ApiKeyProviderImpl(
    @ApplicationContext private val context: Context,
) : ApiKeyProvider {

    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getMetaApiKey(): String? {
        getStoredKey()?.let { return it }
        return BuildConfig.META_API_KEY.takeIf { it.isNotBlank() }
    }

    override fun getStoredKey(): String? =
        prefs.getString(KEY_META_API_KEY, null)?.takeIf { it.isNotBlank() }

    override fun setMetaApiKey(key: String) {
        prefs.edit().putString(KEY_META_API_KEY, key.trim()).apply()
    }

    override fun clearMetaApiKey() {
        prefs.edit().remove(KEY_META_API_KEY).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "smartcess_secure_prefs"
        private const val KEY_META_API_KEY = "meta_api_key"
    }
}
