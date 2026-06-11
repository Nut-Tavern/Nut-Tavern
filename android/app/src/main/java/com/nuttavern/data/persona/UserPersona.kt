package com.nuttavern.data.persona

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 用户身份(对齐酒馆 personas 概念)。
 *
 * 一个用户可以建多份身份,在不同会话 / 不同角色面前切换不同人格。
 * 例如:严肃职场身份、随意闲聊身份、玩游戏时的玩家身份。
 *
 * # 字段分层
 *
 * **基础字段**(编辑页主区暴露):
 * - [name]:发给 AI 的"我"叫什么;占位符 `{{user}}` 取值。**必填**。
 * - [description]:发给 AI 的"我"是谁,长文本人设描述;占位符 `{{persona}}` 取值。可空。
 * - [avatarPath]:头像本地路径。
 *
 * **高级字段**(编辑页折叠区暴露):
 * - [title]:仅在 UI 显示的备注,不发给 AI。例如"工作号 / 摸鱼号"。
 * - [position]:身份描述以什么方式注入到 prompt(系统消息内嵌 / 注释顶部 / 注释底部 /
 *   指定深度);[PersonaPosition.NONE] 表示只发 [name],不拼描述。
 * - [depth]:仅当 [position] = [PersonaPosition.AT_DEPTH] 时生效,从底向上数第几条消息后插入。
 * - [role]:注入消息使用的对话角色。
 *
 * **关联字段**:
 * - [lorebookId]:绑定的世界书 id;使用该身份时作为 persona 来源参与世界书激活。
 * - [characterConnections]:绑定到哪些角色;切到无历史会话的角色 / 新建角色会话时自动选择该 persona。
 *
 * # 序列化
 *
 * 使用 kotlinx.serialization,字段全部带默认值,后续加字段时旧数据反序列化不会因为缺字段抛错。
 * `ignoreUnknownKeys = true` 在 [com.nuttavern.data.persona.PersonaDataStore] 端开启。
 *
 * # 特殊伪卡
 *
 * 见 [NONE_PERSONA_ID]。表示"完全不拼接用户身份提示词"的兜底身份,在仓库层硬塞到列表头部,
 * 不入库、不可编辑、不可删除、可设默认。
 */
@Serializable
data class UserPersona(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val avatarPath: String? = null,
    val title: String = "",
    val position: PersonaPosition = PersonaPosition.IN_PROMPT,
    val depth: Int = DEFAULT_DEPTH,
    val role: PersonaRole = PersonaRole.SYSTEM,
    val lorebookId: String? = null,
    val characterConnections: List<String> = emptyList(),
) {
    /**
     * 是否是"无"伪卡。仓库消费方读取此标记决定是否拼接用户身份块。
     */
    val isNonePersona: Boolean get() = id == NONE_PERSONA_ID

    companion object {
        const val DEFAULT_DEPTH = 2
        const val MIN_DEPTH = 0
        const val MAX_DEPTH = 9999

        /**
         * "无"伪卡 id。固定字符串,任何持久化层都不应允许新建同 id 的 persona。
         */
        const val NONE_PERSONA_ID = "none"

        /**
         * "无"伪卡实例。仓库层硬塞到列表头部,UI 永远拿到这一份。
         *
         * 选中后:
         * - 拼接管线**完全跳过**用户身份块(连 `{{user}}` 占位符都不替换,与 PlaceholderResolver
         *   "未设值时保留原占位符"契约一致);
         * - 列表 / 抽屉中不可拖、不可编、不可删,但可设默认。
         */
        val None: UserPersona = UserPersona(
            id = NONE_PERSONA_ID,
            name = "",
            description = "",
            title = "无",
            position = PersonaPosition.NONE,
        )
    }
}

/**
 * 描述注入到 prompt 的位置。对齐酒馆 `persona_description_positions`。
 *
 * - [NONE]:只发 [UserPersona.name],不拼接 description;Nut Tavern 自加,与酒馆 `9` 对齐。
 * - [IN_PROMPT]:作为 system prompt 的一部分内嵌(默认,最常用)。
 * - [TOP_AN]:Author's Note 顶部。
 * - [BOTTOM_AN]:Author's Note 底部。
 * - [AT_DEPTH]:从底向上数第 N 条消息后插入,深度由 [UserPersona.depth] 控制。
 */
@Serializable
enum class PersonaPosition(val displayName: String, val description: String) {
    NONE("无", "只发用户身份名,不拼接身份描述"),
    IN_PROMPT("内嵌系统提示", "作为系统提示的一部分整体送出"),
    TOP_AN("作者注释顶部", "拼到作者注释段落最前"),
    BOTTOM_AN("作者注释底部", "拼到作者注释段落最后"),
    AT_DEPTH("指定深度", "从底向上数第 N 条消息后插入"),
}

/**
 * 注入消息使用的对话角色。对齐酒馆 `extension_prompt_roles`。
 */
@Serializable
enum class PersonaRole(val displayName: String, val description: String) {
    SYSTEM("系统", "以系统消息发出,优先级最高"),
    USER("用户", "以用户消息发出"),
    ASSISTANT("助手", "以助手消息发出"),
}
