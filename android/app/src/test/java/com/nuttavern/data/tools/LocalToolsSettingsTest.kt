package com.nuttavern.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsSettingsTest {
    @Test
    fun defaultSettingsEnableSafeTimeTool() {
        val settings = LocalToolsSettings()

        assertEquals(true, settings.defaultEnabled)
        assertEquals(false, settings.requireApproval)
        assertEquals(setOf("get_current_time"), settings.enabledToolIds)
    }

    @Test
    fun explicitEmptyToolSetMeansNoToolEnabled() {
        val settings = LocalToolsSettings(enabledToolIds = emptySet())

        assertTrue(settings.enabledToolIds.isEmpty())
    }
}
