package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.ReminderEntity

@Dao
interface ReminderDao {
    @Upsert suspend fun upsert(reminder: ReminderEntity): Long
    @Query("DELETE FROM reminders") suspend fun deleteAll()
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' ORDER BY triggerAtMillis ASC")
    suspend fun scheduled(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE kind = :kind AND referenceId = :referenceId AND status = 'SCHEDULED'")
    suspend fun scheduledFor(kind: String, referenceId: Long): List<ReminderEntity>

    @Query("UPDATE reminders SET status = :status, workRequestId = :workId WHERE id = :id")
    suspend fun setScheduled(id: Long, status: String, workId: String?)

    @Query("UPDATE reminders SET status = 'FIRED', firedAt = :now WHERE id = :id")
    suspend fun markFired(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    /** Retargets a reminder (lead-time change, snooze); scheduler re-enqueues after. */
    @Query("UPDATE reminders SET triggerAtMillis = :trigger, status = 'SCHEDULED', workRequestId = NULL WHERE id = :id")
    suspend fun retarget(id: Long, trigger: Long)

    @Query("SELECT * FROM reminders ORDER BY createdAt DESC") suspend fun all(): List<ReminderEntity>
}
