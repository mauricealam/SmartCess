package com.smartcess.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the 12-tool registry: names, approval gates, banned actions. */
class ToolRegistryTest {

    @Test
    fun registry_hasTwelveTools() {
        assertEquals(12, ToolRegistry.all.size)
    }

    @Test
    fun readOnlyTools_needNoApproval() {
        assertEquals(3, ToolRegistry.readOnlyTools.size)
        assertTrue(ToolRegistry.readOnlyTools.none { it.requiresApproval })
    }

    @Test
    fun writeTools_requireApproval() {
        assertEquals(9, ToolRegistry.approvalGatedTools.size)
        assertTrue(ToolRegistry.approvalGatedTools.all { it.requiresApproval })
    }

    @Test
    fun bannedActions_blocked() {
        listOf("pay_bill", "send_email", "submit_form", "navigate", "make_call", "call").forEach {
            assertTrue(ToolRegistry.isBanned(it))
        }
        assertFalse(ToolRegistry.isBanned("save_bill"))
    }

    @Test
    fun confirmationTool_exists() {
        assertEquals(true, ToolRegistry.find("ask_user_for_confirmation")?.requiresApproval)
    }
}
