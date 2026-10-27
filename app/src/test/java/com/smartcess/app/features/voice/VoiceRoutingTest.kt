package com.smartcess.app.features.voice

import com.smartcess.app.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for voice intent routing (spec voice/touch parity table). */
class VoiceRoutingTest {

    @Test
    fun briefingIntent_routesToBriefing() {
        assertEquals(Routes.BRIEFING, VoiceViewModel.routeForIntent("daily_briefing"))
    }

    @Test
    fun scanIntent_routesToCamera() {
        assertEquals(Routes.CAMERA, VoiceViewModel.routeForIntent("scan_bill"))
    }

    @Test
    fun moduleIntents_routeToOwningModule() {
        assertEquals(Routes.DOCUMENTS, VoiceViewModel.routeForIntent("read_document"))
        assertEquals(Routes.EMAIL, VoiceViewModel.routeForIntent("email_briefing"))
        assertEquals(Routes.APPOINTMENTS, VoiceViewModel.routeForIntent("add_appointment"))
        assertEquals(Routes.ROUTINES, VoiceViewModel.routeForIntent("add_routine"))
    }

    @Test
    fun billIntents_landOnBillList() {
        assertEquals(Routes.BILLS, VoiceViewModel.routeForIntent("ask_bill_question"))
        assertEquals(Routes.BILLS, VoiceViewModel.routeForIntent("create_reminder"))
        assertEquals(Routes.BILLS, VoiceViewModel.routeForIntent("cancel_reminder"))
        assertEquals(Routes.BILLS, VoiceViewModel.routeForIntent("archive_bill"))
    }

    @Test
    fun generalQuestion_staysAndHelps() {
        assertNull(VoiceViewModel.routeForIntent("general_question"))
        assertNull(VoiceViewModel.routeForIntent("unknown_intent"))
    }
}
