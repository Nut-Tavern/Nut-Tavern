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
    val lorebookTimedEffectsJson: String = "{}",
    /**
     * 当前会话的思考量(reasoning effort)。会话级:切会话保留各自档位,关 app 不丢。
     * null 等价 [ThinkingLevel.Auto]("自动",不向后端发送思考字段)。
     */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.Auto,
    /** 当前会话的内置工具总开关。新会话创建时由全局默认固化,切会话各自保留。 */
    val toolMode: com.nuttavern.data.tools.ConversationToolMode =
        com.nuttavern.data.tools.ConversationToolMode.FOLLOW_GLOBAL,
)

data class Message(
    val id: String,
    val role: String,
    val content: String,
    val reasoningContent: String = "",
    val reasoningDurationMillis: Long = 0L,
    /**
     * 用户随消息发送的图片附件。空 = 纯文本消息(老消息、assistant 回复)。
     * 二进制落 [filesDir],这里只引用路径,发请求时按 [ImageAttachment.path] 读文件转 base64。
     */
    val attachments: List<ImageAttachment> = emptyList(),
)

/**
 * 一张随消息发送的图片附件。
 *
 * 二进制**不进数据库**:文件落 `filesDir/chat-images/{id}.{ext}`,这里只存路径 + mime,
 * 发请求时读文件转 base64 拼进各家图片块。设计对齐角色头像([com.nuttavern.data.character.CharacterRepository.saveAvatarBytes])。
 */
@kotlinx.serialization.Serializable
data class ImageAttachment(
    /** 附件唯一 id,同时用作落盘文件名(不含扩展名)。 */
    val id: String,
    /** 图片文件绝对路径([filesDir] 下)。 */
    val path: String,
    /** MIME 类型,如 `image/jpeg` / `image/png` / `image/webp`。发请求时各家要用。 */
    val mimeType: String,
)


/**
 * 思考量(reasoning effort)。聊天页 8 个选项分别映射到这几种状态:
 *
 * - 关闭([Off])    → 显式不思考(各家用各自"关闭"写法)。
 * - 自动([Auto])   → 不向后端发送任何思考字段,由模型自定(默认)。
 * - 极低/低/中/高/极高([Effort]) → 努力度档位,映射到三家 reasoning 参数。
 * - 自定义([Budget]) → 用户填具体 token 预算。
 *
 * 不对齐酒馆(酒馆无此粒度),参考其他客户端的思考量分档。各 Provider 的具体映射见
 * [com.nuttavern.network.ThinkingLevelMapping]。
 *
 * # 持久化
 *
 * 会话级,落到 `conversations.thinkingLevel`(用 [serialize] / [parse] 在 String 间转换)。
 * sealed 而非 enum,是因为"自定义 token"必须携带一个整数,enum 无法表达。
 */
sealed interface ThinkingLevel {
    /** 关闭:显式要求不思考。 */
    data object Off : ThinkingLevel

    /** 自动:不发送思考字段,模型自行决定(默认值)。 */
    data object Auto : ThinkingLevel

    /** 努力度档位。 */
    data class Effort(val tier: EffortTier) : ThinkingLevel

    /** 自定义 token 预算。[tokens] 由 UI 校验为正整数。 */
    data class Budget(val tokens: Int) : ThinkingLevel

    companion object {
        val Default: ThinkingLevel = Auto

        /** 自定义 token 预算下限。UI 校验、反序列化共用,保证两处口径一致。 */
        const val MIN_BUDGET_TOKENS = 128

        /** 自定义 token 预算上限。 */
        const val MAX_BUDGET_TOKENS = 65_536

        /**
         * 序列化成持久化字符串。格式:
         * - `off` / `auto`
         * - `effort:LOW` 等(枚举 name)
         * - `budget:4096`
         */
        fun serialize(level: ThinkingLevel): String = when (level) {
            Off -> "off"
            Auto -> "auto"
            is Effort -> "effort:${level.tier.name}"
            is Budget -> "budget:${level.tokens}"
        }

        /** 反序列化;无法识别(含 null / 空 / 越界 budget)时退回 [Default]。 */
        fun parse(raw: String?): ThinkingLevel {
            val value = raw?.trim().orEmpty()
            return when {
                value == "off" -> Off
                value == "auto" -> Auto
                value.startsWith("effort:") -> {
                    val tier = EffortTier.entries.firstOrNull { it.name == value.removePrefix("effort:") }
                    tier?.let(ThinkingLevel::Effort) ?: Default
                }
                value.startsWith("budget:") -> {
                    // 与 UI 校验同口径:越界(含被外部篡改的超大值)退回 Default,不下发非法预算。
                    val tokens = value.removePrefix("budget:").toIntOrNull()
                        ?.takeIf { it in MIN_BUDGET_TOKENS..MAX_BUDGET_TOKENS }
                    tokens?.let(ThinkingLevel::Budget) ?: Default
                }
                else -> Default
            }
        }
    }
}

/** 思考量努力度档位。5 档对齐聊天页选项"极低 / 低 / 中 / 高 / 极高"。 */
enum class EffortTier(val label: String) {
    MINIMAL("极低"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    MAX("极高"),
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
