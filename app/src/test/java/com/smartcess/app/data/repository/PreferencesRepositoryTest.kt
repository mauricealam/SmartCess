package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.UserPreferencesDao
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** M9: concurrent preference updates must not lose writes. */
class PreferencesRepositoryTest {

    /** Slow fake: without the update lock, two concurrent updates interleave and lose one. */
    private class FakeDao(var current: UserPreferencesEntity?) : UserPreferencesDao {
        override suspend fun upsert(prefs: UserPreferencesEntity) {
            delay(20)
            current = prefs
        }
        override suspend fun get(): UserPreferencesEntity? {
            delay(20)
            return current
        }
        override fun observe(): Flow<UserPreferencesEntity?> = flowOf(current)
        override suspend fun deleteAll() {
            current = null
        }
    }

    @Test
    fun update_concurrentIncrements_bothApply(): Unit = runBlocking {
        val repo = PreferencesRepository(FakeDao(UserPreferencesEntity(ttsRate = 1.0f)))
        val first = async { repo.update { it.copy(ttsRate = it.ttsRate + 1f) } }
        val second = async { repo.update { it.copy(ttsRate = it.ttsRate + 1f) } }
        first.await()
        second.await()
        assertEquals(3.0f, repo.get().ttsRate, 0.001f)
    }

    @Test
    fun setTtsRate_coercesToRange(): Unit = runBlocking {
        val repo = PreferencesRepository(FakeDao(null))
        repo.setTtsRate(9.0f)
        assertEquals(2.0f, repo.get().ttsRate, 0.001f)
    }
}
