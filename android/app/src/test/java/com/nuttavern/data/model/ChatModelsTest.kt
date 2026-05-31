package com.nuttavern.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatModelsTest {
    @Test
    fun effortTier_labelsUseReadableChinese() {
        assertEquals("极低", EffortTier.MINIMAL.label)
        assertEquals("低", EffortTier.LOW.label)
        assertEquals("中", EffortTier.MEDIUM.label)
        assertEquals("高", EffortTier.HIGH.label)
        assertEquals("极高", EffortTier.MAX.label)
    }

    @Test
    fun thinkingLevel_serializeRoundTrip() {
        val levels = listOf(
            ThinkingLevel.Off,
            ThinkingLevel.Auto,
            ThinkingLevel.Effort(EffortTier.MINIMAL),
            ThinkingLevel.Effort(EffortTier.MAX),
            ThinkingLevel.Budget(4096),
        )
        levels.forEach { level ->
            assertEquals(level, ThinkingLevel.parse(ThinkingLevel.serialize(level)))
        }
    }

    @Test
    fun thinkingLevel_parseFallsBackToDefaultOnGarbage() {
        assertEquals(ThinkingLevel.Default, ThinkingLevel.parse(null))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.parse(""))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.parse("nonsense"))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.parse("effort:UNKNOWN"))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.parse("budget:0"))
        assertEquals(ThinkingLevel.Default, ThinkingLevel.parse("budget:-5"))
    }

    @Test
    fun workspaceAccessMode_describesUiOnlyPermissionBoundary() {
        assertEquals("无工作区", WorkspaceAccessMode.NO_WORKSPACE.label)
        assertEquals("只读", WorkspaceAccessMode.READ_ONLY.label)
        assertEquals("读写", WorkspaceAccessMode.READ_WRITE.label)
        assertEquals("仅允许读取工作区内容", WorkspaceAccessMode.READ_ONLY.description)
    }

    @Test
    fun chatRunMode_labelsMatchComposerPills() {
        assertEquals("Chat", ChatRunMode.CHAT.label)
        assertEquals("Agents", ChatRunMode.AGENTS.label)
    }
}
