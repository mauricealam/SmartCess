package com.smartcess.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.smartcess.app.data.local.dao.AppointmentDao
import com.smartcess.app.data.local.dao.BillDao
import com.smartcess.app.data.local.dao.ConversationTurnDao
import com.smartcess.app.data.local.dao.DocumentDao
import com.smartcess.app.data.local.dao.EmailItemDao
import com.smartcess.app.data.local.dao.ReminderDao
import com.smartcess.app.data.local.dao.RoutineDao
import com.smartcess.app.data.local.dao.UserPreferencesDao
import com.smartcess.app.data.local.entity.AppointmentEntity
import com.smartcess.app.data.local.entity.BillEntity
import com.smartcess.app.data.local.entity.ConversationTurnEntity
import com.smartcess.app.data.local.entity.DocumentEntity
import com.smartcess.app.data.local.entity.EmailItemEntity
import com.smartcess.app.data.local.entity.ReminderEntity
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.data.local.entity.UserPreferencesEntity

internal const val DATABASE_NAME = "smartcess.db"

/**
 * Local-first Room database. Schema exports to app/schemas (see ksp room.schemaLocation).
 * Version history: 1 = initial (bills, reminders, documents, email, appointments,
 * routines, conversation turns, user preferences).
 * 2 = spec data-model fields (haptics, default reminder time, connection statuses,
 * bill period bounds, email attachments). See MIGRATION_1_2.
 */
@Database(
    entities = [
        BillEntity::class,
        ReminderEntity::class,
        DocumentEntity::class,
        EmailItemEntity::class,
        AppointmentEntity::class,
        RoutineEntity::class,
        ConversationTurnEntity::class,
        UserPreferencesEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmartCessDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun reminderDao(): ReminderDao
    abstract fun documentDao(): DocumentDao
    abstract fun emailItemDao(): EmailItemDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun routineDao(): RoutineDao
    abstract fun conversationTurnDao(): ConversationTurnDao
    abstract fun userPreferencesDao(): UserPreferencesDao
}
