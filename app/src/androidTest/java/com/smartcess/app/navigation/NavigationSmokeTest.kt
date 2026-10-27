package com.smartcess.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Navigation stub test (Hilt-free): route helpers build the expected paths.
 * Full graph UI tests need Hilt test setup (hilt-android-testing) on the host;
 * extend per spec UI plan (nav, voice, camera live region, TalkBack-only flow).
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
class NavigationSmokeTest {

    @Test
    fun billResult_buildsPath() {
        assertEquals("bill_result/42", Routes.billResult(42L))
    }

    @Test
    fun comparison_buildsPath() {
        assertEquals("comparison/7", Routes.comparison(7L))
    }

    @Test
    fun docResult_buildsPath() {
        assertEquals("doc_result/3", Routes.docResult(3L))
    }

    @Test
    fun appointment_buildsPath() {
        assertEquals("appointment/9", Routes.appointment(9L))
    }

    @Test
    fun routine_buildsPath() {
        assertEquals("routine/11", Routes.routine(11L))
    }

    @Test
    fun reminder_buildsPath() {
        assertEquals("reminder/5", Routes.reminder(5L))
    }
}
