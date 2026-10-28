package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.UserPreferencesDao
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Single-row preferences with spec defaults. */
@Singleton
class PreferencesRepository @Inject constructor(private val dao: UserPreferencesDao) {

    /** Serializes read-modify-write so concurrent updates cannot lose writes (M9). */
    private val updateMutex = Mutex()

    fun observe(): Flow<UserPreferencesEntity> = dao.observe().map { it ?: UserPreferencesEntity() }

    suspend fun get(): UserPreferencesEntity = dao.get() ?: UserPreferencesEntity()

    suspend fun update(transform: (UserPreferencesEntity) -> UserPreferencesEntity) {
        updateMutex.withLock { dao.upsert(transform(get())) }
    }

    suspend fun setAnalyticsOptIn(optIn: Boolean) = update { it.copy(analyticsOptIn = optIn) }
    suspend fun setTtsRate(rate: Float) = update { it.copy(ttsRate = rate.coerceIn(0.5f, 2.0f)) }
    suspend fun deleteAll() = dao.deleteAll()
}
