package com.smartcess.app.data.repository

import com.smartcess.app.data.local.dao.AppointmentDao
import com.smartcess.app.data.local.entity.AppointmentEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppointmentRepository @Inject constructor(private val dao: AppointmentDao) {

    fun observeAll(): Flow<List<AppointmentEntity>> = dao.observeAll()
    suspend fun getById(id: Long): AppointmentEntity? = dao.getById(id)

    suspend fun todayAndTomorrow(): List<AppointmentEntity> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return dao.between(start, end)
    }

    suspend fun save(appointment: AppointmentEntity): SaveOutcome {
        val result = validateAppointment(
            appointment.title,
            appointment.startMillis,
            appointment.endMillis,
            appointment.confidence,
        )
        if (result.hasErrors) return SaveOutcome(null, result)
        return SaveOutcome(dao.upsert(appointment), result)
    }

    suspend fun setCalendarEvent(id: Long, eventId: Long) = dao.setCalendarEvent(id, eventId)
    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
