package com.nuttavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReasoningBlockTest {
    @Test
    fun formatReasoningDuration_returnsTimingPlaceholderWhileStreamingWithoutDuration() {
        assertEquals("计时中", formatReasoningDuration(durationMillis = 0L, isStreaming = true))
    }

    @Test
    fun formatReasoningDuration_hidesMissingHistoricalDuration() {
        assertEquals("", formatReasoningDuration(durationMillis = 0L, isStreaming = false))
    }

    @Test
    fun formatReasoningDuration_roundsUpToOneTenthSecond() {
        assertEquals("0.1秒", formatReasoningDuration(durationMillis = 1L, isStreaming = false))
        assertEquals("0.1秒", formatReasoningDuration(durationMillis = 100L, isStreaming = false))
        assertEquals("0.2秒", formatReasoningDuration(durationMillis = 101L, isStreaming = false))
    }

    @Test
    fun formatReasoningDuration_formatsSecondsWithOneDecimalPlace() {
        assertEquals("2.4秒", formatReasoningDuration(durationMillis = 2400L, isStreaming = false))
    }

    @Test
    fun formatReasoningDuration_formatsMinutesWithTenths() {
        assertEquals("1分3.2秒", formatReasoningDuration(durationMillis = 63_200L, isStreaming = false))
    }
}
