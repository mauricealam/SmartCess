package com.smartcess.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row (id = 0) user preferences. Non-essential AI off by default. */
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey val id: Int = 0,
    val ttsRate: Float = 1.0f,
    val defaultBillReminderDays: Int = 3,
    val travelReminderMinutes: List<Int> = listOf(30, 60, 90),
    val localeTag: String = "en",
    val nonEssentialAiEnabled: Boolean = false,
    val samEnabled: Boolean = false,
    val imageGenEnabled: Boolean = false,
    val analyticsOptIn: Boolean = false,
    val crashOptIn: Boolean = false,
    val highContrast: Boolean = false,
    val largeText: Boolean = false,
    /** Spec data model: framing-guidance haptics on/off. */
    val hapticFeedbackEnabled: Boolean = true,
    /** Spec data model: default reminder time-of-day, minutes since midnight. */
    val defaultReminderTime: Int = 540,
    /** Spec data model: demo|connected. */
    val emailConnectionStatus: String = "demo",
    /** Spec data model: local|device. */
    val calendarConnectionStatus: String = "local",
)
