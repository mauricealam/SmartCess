package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.RoutineDao
import com.smartcess.app.data.local.entity.RoutineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M9: completing a stale daily routine must roll next-due forward from now. */
class RoutineRepositoryTest {

    private class FakeDao(var routine: RoutineEntity?) : RoutineDao {
        var lastNextDue: Long? = null
        var completed: Long? = null
        override suspend fun upsert(routine: RoutineEntity): Long = 1
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun getById(id: Long): RoutineEntity? = routine
        override fun observeAll(): Flow<List<RoutineEntity>> = flowOf(emptyList())
        override suspend fun dueBefore(upper: Long): List<RoutineEntity> = emptyList()
        override suspend fun markCompleted(id: Long, now: Long, nextDue: Long?) {
            completed = id
            lastNextDue = nextDue
        }
        override suspend fun all(): List<RoutineEntity> = emptyList()
    }

    private fun daily(nextDue: Long?) = RoutineEntity(
        id = 5,
        title = "Meds",
        kind = RoutineEntity.KIND_MEDICATION,
        scheduleRule = "DAILY 08:00",
        nextDueMillis = nextDue,
    )

    @Test
    fun complete_staleDailyDue_rollsFromNow(): Unit = runBlocking {
        val before = System.currentTimeMillis()
        val dao = FakeDao(daily(before - 48L * 3600 * 1000))
        RoutineRepository(dao).complete(5)
        val next = dao.lastNextDue ?: error("no next due recorded")
        val after = System.currentTimeMillis()
        assertTrue("stale due must roll forward from now", next > before)
        assertTrue("rolled due must be ~24h out", next <= after + 24L * 3600 * 1000 + 60_000)
    }

    @Test
    fun complete_nonDaily_keepsManualDue(): Unit = runBlocking {
        val due = System.currentTimeMillis() + 3_600_000
        val dao = FakeDao(daily(due).copy(scheduleRule = "WEEKLY MON 09:00"))
        RoutineRepository(dao).complete(5)
        assertEquals(due, dao.lastNextDue)
    }

    @Test
    fun complete_nullDue_schedulesNextDay(): Unit = runBlocking {
        val before = System.currentTimeMillis()
        val dao = FakeDao(daily(null))
        RoutineRepository(dao).complete(5)
        assertTrue((dao.lastNextDue ?: 0) > before)
    }
}
