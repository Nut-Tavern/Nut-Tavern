package com.nuttavern.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsSettingsTest {
    @Test
    fun defaultSettingsEnableSafeTimeTool() {
        val settings = LocalToolsSettings()

        assertEquals(true, settings.defaultEnabled)
        assertEquals(setOf("get_current_time"), settings.enabledToolIds)
        assertTrue(settings.approvalRequiredToolIds.isEmpty())
        assertEquals(true, settings.isToolEnabledByDefault("get_current_time"))
        assertEquals(false, settings.isApprovalRequiredForTool("get_current_time"))
    }

    @Test
    fun explicitEmptyToolSetMeansNoToolEnabled() {
        val settings = LocalToolsSettings(enabledToolIds = emptySet())

        assertTrue(settings.enabledToolIds.isEmpty())
    }

    @Test
    fun conversationToolModeDoesNotFollowGlobalDefaultAtRuntime() {
        assertEquals(true, ConversationToolMode.FOLLOW_GLOBAL.resolveToolsEnabled())
        assertEquals(true, ConversationToolMode.FORCE_ON.resolveToolsEnabled())
        assertEquals(false, ConversationToolMode.FORCE_OFF.resolveToolsEnabled())
    }
}
