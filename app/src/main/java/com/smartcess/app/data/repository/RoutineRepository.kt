package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.RoutineDao
import com.smartcess.app.data.local.entity.RoutineEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Routine Guardian storage. Reminders track; the app never gives medical advice. */
@Singleton
class RoutineRepository @Inject constructor(private val dao: RoutineDao) {

    fun observeAll(): Flow<List<RoutineEntity>> = dao.observeAll()
    suspend fun getById(id: Long): RoutineEntity? = dao.getById(id)

    suspend fun dueToday(): List<RoutineEntity> {
        val endOfDay = Instant.now().toEpochMilli() + 24L * 60 * 60 * 1000
        return dao.dueBefore(endOfDay)
    }

    suspend fun save(routine: RoutineEntity): SaveOutcome {
        val normalized = routine.copy(kind = normalizeRoutineKind(routine.kind))
        val result = validateRoutine(normalized.title, normalized.scheduleRule)
        if (result.hasErrors) return SaveOutcome(null, result)
        return SaveOutcome(dao.upsert(normalized), result)
    }

    /** Marks complete and rolls next-due 24h forward from now for daily rules; stale dues restart from now, others keep manual reschedule. */
    suspend fun complete(id: Long) {
        val routine = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val nextDue = if (routine.scheduleRule.startsWith("DAILY")) {
            (routine.nextDueMillis ?: now).coerceAtLeast(now) + 24L * 60 * 60 * 1000
        } else {
            routine.nextDueMillis
        }
        dao.markCompleted(id, now, nextDue)
    }

    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
