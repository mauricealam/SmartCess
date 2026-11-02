package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.RoutineDao
import com.smartcess.app.data.local.entity.RoutineEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dueToday() boundary tests. The rule is a rolling 24-hour window
 * (nextDueMillis <= now + 24h), NOT a local-calendar-day predicate.
 * The fake mirrors the DAO SQL exactly, including null exclusion.
 */
class RoutineDueTest {

    private class FakeDao(val rows: List<RoutineEntity>) : RoutineDao {
        var lastUpper: Long? = null
        override suspend fun upsert(routine: RoutineEntity): Long = 1L
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteAll() {}
        override suspend fun getById(id: Long): RoutineEntity? = rows.firstOrNull { it.id == id }
        override fun observeAll(): Flow<List<RoutineEntity>> = flowOf(rows)
        override suspend fun dueBefore(upper: Long): List<RoutineEntity> {
            lastUpper = upper
            return rows.filter { it.nextDueMillis != null && it.nextDueMillis <= upper }
                .sortedBy { it.nextDueMillis }
        }
        override suspend fun markCompleted(id: Long, now: Long, nextDue: Long?) {}
        override suspend fun all(): List<RoutineEntity> = rows
    }

    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(day: Int, hour: Int, min: Int): Long =
        LocalDateTime.of(2026, 11, day, hour, min).atZone(zone).toInstant().toEpochMilli()

    private fun routine(id: Long, nextDue: Long?) = RoutineEntity(
        id = id,
        title = "R$id",
        kind = RoutineEntity.KIND_OTHER,
        scheduleRule = "DAILY 09:00",
        nextDueMillis = nextDue,
    )

    // Fixed "now": Mon 2026-11-02 13:00 +08:00.
    private val now = at(2, 13, 0)

    @Test
    fun cutoff_isNowPlus24h() = runTest {
        val dao = FakeDao(emptyList())
        RoutineRepository(dao).dueToday(now)
        assertEquals(now + 24L * 60 * 60 * 1000, dao.lastUpper)
    }

    @Test
    fun dueEarlierToday_included() = runTest {
        val dao = FakeDao(listOf(routine(1, at(2, 9, 0))))
        val due = RoutineRepository(dao).dueToday(now)
        assertEquals(listOf(1L), due.map { it.id })
    }

    @Test
    fun dueLaterToday_included() = runTest {
        val dao = FakeDao(listOf(routine(1, at(2, 18, 0))))
        val due = RoutineRepository(dao).dueToday(now)
        assertEquals(listOf(1L), due.map { it.id })
    }

    @Test
    fun overdue_included() = runTest {
        val dao = FakeDao(listOf(routine(1, at(1, 9, 0))))
        val due = RoutineRepository(dao).dueToday(now)
        assertEquals(listOf(1L), due.map { it.id })
    }

    @Test
    fun tomorrowWithin24h_included_notCalendarDay() = runTest {
        // Next calendar day but inside the rolling window: counted "due".
        val dao = FakeDao(listOf(routine(1, at(3, 9, 0))))
        val due = RoutineRepository(dao).dueToday(now)
        assertEquals(listOf(1L), due.map { it.id })
    }

    @Test
    fun beyond24h_excluded() = runTest {
        val dao = FakeDao(listOf(routine(1, at(3, 14, 0))))
        val due = RoutineRepository(dao).dueToday(now)
        assertTrue(due.isEmpty())
    }

    @Test
    fun boundary_inclusive() = runTest {
        val edge = now + 24L * 60 * 60 * 1000
        val dao = FakeDao(
            listOf(routine(1, edge), routine(2, edge + 1)),
        )
        val due = RoutineRepository(dao).dueToday(now)
        assertEquals(listOf(1L), due.map { it.id })
    }

    @Test
    fun nullNextDue_excluded() = runTest {
        val dao = FakeDao(listOf(routine(1, null)))
        val due = RoutineRepository(dao).dueToday(now)
        assertTrue(due.isEmpty())
    }

    @Test
    fun midnightBoundary_usesEpochNotCalendar() = runTest {
        // 23:30 local: a 00:30-next-day routine is inside the window even
        // though the calendar date changes; no timezone conversion happens.
        val late = at(2, 23, 30)
        val dao = FakeDao(
            listOf(
                routine(1, at(3, 0, 30)),
                routine(2, at(2, 0, 30)),
            ),
        )
        val due = RoutineRepository(dao).dueToday(late)
        assertEquals(listOf(2L, 1L), due.map { it.id })
        assertTrue(Instant.ofEpochMilli(late).toString().startsWith("2026-11-02"))
    }
}
