package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.AppointmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Upsert suspend fun upsert(appointment: AppointmentEntity): Long
    @Query("DELETE FROM appointments WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM appointments") suspend fun deleteAll()
    @Query("SELECT * FROM appointments WHERE id = :id") suspend fun getById(id: Long): AppointmentEntity?

    @Query("SELECT * FROM appointments ORDER BY startMillis ASC")
    fun observeAll(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE startMillis BETWEEN :from AND :to ORDER BY startMillis ASC")
    suspend fun between(from: Long, to: Long): List<AppointmentEntity>

    @Query("UPDATE appointments SET calendarEventId = :eventId WHERE id = :id")
    suspend fun setCalendarEvent(id: Long, eventId: Long)

    @Query("SELECT * FROM appointments ORDER BY startMillis ASC") suspend fun all(): List<AppointmentEntity>
}
