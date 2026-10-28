package com.smartcess.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for notification deep-link routing (C2/M4/M10). */
class DeepLinkTest {

    @Test
    fun reminderId_routesToReminder() {
        assertEquals("reminder/42", deepLinkRoute(42, null))
    }

    @Test
    fun appointmentId_routesToAppointment() {
        assertEquals("appointment/7", deepLinkRoute(null, 7))
    }

    @Test
    fun reminderId_winsOverAppointment() {
        assertEquals("reminder/42", deepLinkRoute(42, 7))
    }

    @Test
    fun missingIds_yieldNull() {
        assertNull(deepLinkRoute(null, null))
    }

    @Test
    fun invalidIds_yieldNull() {
        assertNull(deepLinkRoute(0, -1))
        assertNull(deepLinkRoute(-5, 0))
    }
}
