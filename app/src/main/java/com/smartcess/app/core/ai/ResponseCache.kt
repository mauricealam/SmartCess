package com.smartcess.app.core.ai

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory response cache: identical AI inputs reuse the last answer (cost control). */
@Singleton
class ResponseCache @Inject constructor() {
    private data class Entry(val value: String, val expiresAt: Long)

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>()
    var maxEntries: Int = 100
    var ttlMillis: Long = 15 * 60 * 1000

    fun key(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): String? = synchronized(lock) {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        entry.value
    }

    fun put(key: String, value: String) = synchronized(lock) {
        if (map.size >= maxEntries) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }

    fun clear() = synchronized(lock) { map.clear() }
}
