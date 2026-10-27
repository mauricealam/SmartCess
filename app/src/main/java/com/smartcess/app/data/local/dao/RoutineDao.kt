package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.RoutineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Upsert suspend fun upsert(routine: RoutineEntity): Long
    @Query("DELETE FROM routines WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM routines") suspend fun deleteAll()
    @Query("SELECT * FROM routines WHERE id = :id") suspend fun getById(id: Long): RoutineEntity?

    @Query("SELECT * FROM routines ORDER BY nextDueMillis ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE nextDueMillis IS NOT NULL AND nextDueMillis <= :upper ORDER BY nextDueMillis ASC")
    suspend fun dueBefore(upper: Long): List<RoutineEntity>

    @Query("UPDATE routines SET lastCompletedMillis = :now, streakCount = streakCount + 1, nextDueMillis = :nextDue WHERE id = :id")
    suspend fun markCompleted(id: Long, now: Long, nextDue: Long?)

    @Query("SELECT * FROM routines ORDER BY createdAt DESC") suspend fun all(): List<RoutineEntity>
}
