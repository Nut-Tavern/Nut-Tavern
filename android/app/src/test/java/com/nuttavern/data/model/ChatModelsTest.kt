package com.nuttavern.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatModelsTest {
    @Test
    fun thinkingLevel_labelsUseReadableChinese() {
        assertEquals("低", ThinkingLevel.LOW.label)
        assertEquals("中", ThinkingLevel.MEDIUM.label)
        assertEquals("高", ThinkingLevel.HIGH.label)
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
