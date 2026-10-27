package com.smartcess.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations. Every schema change ships one; Migration1To2Test covers v1->v2.
 *
 * v2: spec data-model fields — user preference flags (haptics, default reminder
 * time, email/calendar connection status), bill period bounds, email attachments.
 * All additive; no user data is lost or rewritten.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE user_preferences ADD COLUMN hapticFeedbackEnabled INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            "ALTER TABLE user_preferences ADD COLUMN defaultReminderTime INTEGER NOT NULL DEFAULT 540",
        )
        db.execSQL(
            "ALTER TABLE user_preferences ADD COLUMN emailConnectionStatus TEXT NOT NULL DEFAULT 'demo'",
        )
        db.execSQL(
            "ALTER TABLE user_preferences ADD COLUMN calendarConnectionStatus TEXT NOT NULL DEFAULT 'local'",
        )
        db.execSQL("ALTER TABLE bills ADD COLUMN periodStart TEXT")
        db.execSQL("ALTER TABLE bills ADD COLUMN periodEnd TEXT")
        db.execSQL("ALTER TABLE email_items ADD COLUMN hasAttachment INTEGER NOT NULL DEFAULT 0")
    }
}
