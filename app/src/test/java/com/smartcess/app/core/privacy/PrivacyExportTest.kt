package com.smartcess.app.core.privacy

import com.smartcess.app.core.serialization.SmartCessJson
import com.smartcess.app.data.local.entity.ConversationTurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** H2: voice turns are readable via export (JSON round-trip + CSV rows). */
class PrivacyExportTest {

    private val json = SmartCessJson.instance

    @Test
    fun exportJson_roundTripsTurns() {
        val export = SmartCessExport(
            version = PrivacyManager.CURRENT_EXPORT_VERSION,
            exportedAt = "2026-10-28T00:00:00Z",
            bills = emptyList(),
            reminders = emptyList(),
            documents = emptyList(),
            emailItems = emptyList(),
            appointments = emptyList(),
            routines = emptyList(),
            turns = listOf(
                ConversationTurnEntity(role = "USER", text = "scan my bill", intent = "scan_bill"),
            ),
        )
        val decoded = json.decodeFromString(
            SmartCessExport.serializer(),
            json.encodeToString(SmartCessExport.serializer(), export),
        )
        assertEquals(1, decoded.turns.size)
        assertEquals("scan my bill", decoded.turns.single().text)
    }

    @Test
    fun exportJson_withoutTurns_decodesToEmpty() {
        val legacy = """{"version":1,"exportedAt":"2026-10-28T00:00:00Z","bills":[],"reminders":[],"documents":[],"emailItems":[],"appointments":[],"routines":[]}"""
        val decoded = json.decodeFromString(SmartCessExport.serializer(), legacy)
        assertTrue(decoded.turns.isEmpty())
    }

    @Test
    fun turnCsvRows_mapsColumns() {
        val rows = PrivacyManager.turnCsvRows(
            listOf(ConversationTurnEntity(role = "ASSISTANT", text = "help", intent = "general_question", createdAt = 0)),
        )
        assertEquals(listOf(listOf("ASSISTANT", "help", "general_question", "1970-01-01T00:00:00Z")), rows)
    }
}
