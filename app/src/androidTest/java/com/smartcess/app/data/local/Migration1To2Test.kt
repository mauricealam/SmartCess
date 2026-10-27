package com.smartcess.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration v1 -> v2 (spec: data model fields, tested migration).
 * Runs on an emulator via the nightly workflow; compile-checked by
 * :app:assembleDebugAndroidTest on every build.
 */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmartCessDatabase::class.java,
    )

    @Test
    fun migrate1To2_addsColumnsWithDefaults() {
        val name = "migration-1-2"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO user_preferences (id, ttsRate) VALUES (0, 1.0)")
            execSQL("INSERT INTO bills (provider, amount, currency, dueDate, requiredAction, confidence, missingFields, summary) VALUES ('T', 1.0, 'MYR', '2026-11-01', 'review_bill', 0.9, '[]', 's')")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).apply {
            query("SELECT hapticFeedbackEnabled, defaultReminderTime, emailConnectionStatus, calendarConnectionStatus FROM user_preferences WHERE id = 0").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(540, cursor.getInt(1))
                assertEquals("demo", cursor.getString(2))
                assertEquals("local", cursor.getString(3))
            }
            query("SELECT periodStart, periodEnd FROM bills").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
            query("SELECT hasAttachment FROM email_items").use { cursor ->
                assertTrue(cursor.getColumnIndex("hasAttachment") >= 0)
            }
            close()
        }
    }
}
