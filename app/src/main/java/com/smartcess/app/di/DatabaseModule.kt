package com.smartcess.app.di

import android.content.Context
import androidx.room.Room
import com.smartcess.app.data.local.DATABASE_NAME
import com.smartcess.app.data.local.SmartCessDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SmartCessDatabase =
        Room.databaseBuilder(context, SmartCessDatabase::class.java, DATABASE_NAME)
            .addMigrations(com.smartcess.app.data.local.MIGRATION_1_2)
            .build()

    @Provides fun provideBillDao(db: SmartCessDatabase) = db.billDao()
    @Provides fun provideReminderDao(db: SmartCessDatabase) = db.reminderDao()
    @Provides fun provideDocumentDao(db: SmartCessDatabase) = db.documentDao()
    @Provides fun provideEmailItemDao(db: SmartCessDatabase) = db.emailItemDao()
    @Provides fun provideAppointmentDao(db: SmartCessDatabase) = db.appointmentDao()
    @Provides fun provideRoutineDao(db: SmartCessDatabase) = db.routineDao()
    @Provides fun provideConversationTurnDao(db: SmartCessDatabase) = db.conversationTurnDao()
    @Provides fun provideUserPreferencesDao(db: SmartCessDatabase) = db.userPreferencesDao()
}
