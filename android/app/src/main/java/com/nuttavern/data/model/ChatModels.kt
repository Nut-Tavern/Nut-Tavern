package com.nuttavern.data.model

data class ConversationSummary(
    val id: String,
    val title: String,
    val lastMessageTime: String,
    val assistantId: String,
    val groupLabel: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /**
     * 当前会话绑定的角色卡 id。null 表示未绑定,聊天走默认助手 system prompt。
     * 角色被删除时不自动清空,加载时按 id 查找,查不到则按"无角色"语义。
     */
    val characterId: String? = null,
    /**
     * 当前会话锁定的用户身份 id。会话创建那一刻取当前默认身份写入,
     * 后续抽屉切身份直接覆盖。null 表示"无身份",拼接管线跳过用户身份块。
     * 身份被删除时不自动清空,加载时按 id 查找,查不到则按"无身份"语义。
     */
    val personaId: String? = null,
    /**
     * 当前会话锁定的预设 id。会话创建那一刻取当前默认预设写入,
     * 后续抽屉切预设直接覆盖。null 表示"未锁定预设",拼接管线退化为全局默认预设。
     * 预设被删除时不自动清空,加载时按 id 查找,查不到则退化为全局默认预设。
     */
    val presetId: String? = null,
    /**
     * 当前会话引用的正则组 id 列表(JSON 数组字符串)。
     * 会话创建时从用户级当前启用的组 id 快照写入。设置页改用户级启用不影响此字段。
     * null 表示"不引用任何组"。
     */
    val enabledRegexGroupIds: String? = null,
    /**
     * 当前会话引用的散规则 id 列表(JSON 数组字符串)。
     * 语义与 [enabledRegexGroupIds] 完全一致,只是对应散规则而非组。
     */
    val enabledOrphanRegexIds: String? = null,
)

data class Message(
    val id: String,
    val role: String,
    val content: String,
    val reasoningContent: String = "",
    val reasoningDurationMillis: Long = 0L,
)

enum class ThinkingLevel(val label: String) {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
}

enum class ChatRunMode(val label: String) {
    CHAT("Chat"),
    AGENTS("Agents"),
}

enum class WorkspaceAccessMode(
    val label: String,
    val description: String,
    val riskNote: String,
) {
    NO_WORKSPACE(
        label = "无工作区",
        description = "不读取或写入工作区",
        riskNote = "适合普通聊天，不会启用工作区工具。",
    ),
    READ_ONLY(
        label = "只读",
        description = "仅允许读取工作区内容",
        riskNote = "当前只是界面状态，不会实际读取文件。",
    ),
    READ_WRITE(
        label = "读写",
        description = "允许读取和修改工作区内容",
        riskNote = "这是高风险能力占位，当前不会实际修改工作区。",
    ),
}

data class SearchSetting(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

data class McpItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

data class SkillItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

val defaultSearchSettings = listOf(
    SearchSetting("web-search", "联网搜索", true),
    SearchSetting("knowledge-base", "知识库搜索", false),
    SearchSetting("local-index", "本地索引搜索", false),
)

val defaultMcpItems = listOf(
    McpItem("filesystem", "文件系统", true),
    McpItem("browser", "浏览器", true),
    McpItem("search", "搜索 MCP", false),
)

val defaultSkillItems = listOf(
    SkillItem("summarize", "总结整理", true),
    SkillItem("rewrite", "润色改写", true),
    SkillItem("planner", "任务拆解", false),
)
