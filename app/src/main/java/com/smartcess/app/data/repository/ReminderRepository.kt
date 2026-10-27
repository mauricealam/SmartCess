package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.ReminderDao
import com.smartcess.app.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Reminder storage. WorkManager scheduling lives in ReminderScheduler (core.work). */
@Singleton
class ReminderRepository @Inject constructor(private val dao: ReminderDao) {

    fun observeScheduled(): Flow<List<ReminderEntity>> = dao.observeScheduled()
    suspend fun scheduled(): List<ReminderEntity> = dao.scheduled()
    suspend fun getById(id: Long): ReminderEntity? = dao.getById(id)
    suspend fun scheduledFor(kind: String, referenceId: Long): List<ReminderEntity> =
        dao.scheduledFor(kind, referenceId)

    suspend fun create(
        kind: String,
        title: String,
        triggerAtMillis: Long,
        referenceId: Long = -1,
        detail: String? = null,
        offsetMinutes: Int? = null,
    ): Long {
        require(title.isNotBlank()) { "Reminder title is required." }
        require(triggerAtMillis > System.currentTimeMillis()) { "Reminder time must be in the future." }
        return dao.upsert(
            ReminderEntity(
                kind = kind,
                referenceId = referenceId,
                title = title,
                detail = detail,
                triggerAtMillis = triggerAtMillis,
                offsetMinutes = offsetMinutes,
            ),
        )
    }

    suspend fun setScheduled(id: Long, workId: String) =
        dao.setScheduled(id, ReminderEntity.STATUS_SCHEDULED, workId)

    suspend fun markFired(id: Long) = dao.markFired(id)

    /** Moves a reminder to a new future trigger (lead change, snooze). */
    suspend fun retarget(id: Long, triggerAtMillis: Long) {
        require(triggerAtMillis > System.currentTimeMillis()) { "Reminder time must be in the future." }
        dao.retarget(id, triggerAtMillis)
    }
    suspend fun complete(id: Long) = dao.setStatus(id, ReminderEntity.STATUS_COMPLETED)
    suspend fun cancel(id: Long) = dao.setStatus(id, ReminderEntity.STATUS_CANCELLED)
    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
