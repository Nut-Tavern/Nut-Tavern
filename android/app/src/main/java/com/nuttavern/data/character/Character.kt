package com.nuttavern.data.character

import com.nuttavern.data.regex.RegexScript
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * 角色卡运行时数据模型,对齐 SillyTavern V3 card 的 `data` 主体。
 *
 * 当前阶段只负责后端承载:字段完整建模、可序列化、可持久化。PromptComposer 会先消费基础文本
 * 字段;世界书、正则和 extensions 先无损存盘,等对应运行时模块上线后再消费。
 *
 * # 非 V3 spec 字段
 *
 * - [verbosity]:回复长度档位,对齐酒馆 `oai_settings.verbosity`。Nut Tavern 把这个字段挂在
 *   角色上而非预设(参考 RikkaHub 实现):它更像"这个角色想多话还是少话",与提示词组合无关,
 *   预设跨角色复用时不应改变长度风格。**该字段不参与导入导出**(V3 卡 spec 没有,导入导出统一
 *   推迟时再处理)。空字符串 = auto,等价于不发送 verbosity 参数;支持自定义档位字符串,
 *   兼容未来后端新枚举值(如 minimal / max)。
 */
@Serializable
data class Character(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMessage: String = "",
    @SerialName("mes_example") val messageExample: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    /**
     * V3 `extensions` 对象。当前不解释内容,避免提前绑定未落地扩展结构。
     */
    val extensions: JsonObject = EMPTY_JSON_OBJECT,
    /**
     * V3 `character_book` 内嵌世界书,导入导出携带位。
     *
     * 运行时**不再单独消费**:角色世界书重构后,对话激活只读 [characterLorebookId](角色世界书)
     * 和 [lorebookIds](辅助世界书)指向的独立世界书。这个字段保留是为了角色卡导入导出
     * round-trip——导入带 character_book 的 V3 卡时由导入链路提取成独立世界书并写 [characterLorebookId],
     * 导出时再回填(对齐酒馆 importEmbeddedWorldInfo / convertWorldInfoToCharacterBook)。
     */
    @SerialName("character_book") val characterBook: CharacterBook? = null,
    @SerialName("regex_scripts") val regexScripts: List<RegexScript> = emptyList(),
    val avatarPath: String? = null,
    /**
     * 角色世界书 id(单选)。对齐酒馆 primary 世界书 `character.data.extensions.world`,随卡走。
     * 对话时这本世界书作为角色来源自动参与激活。null = 未选择。
     */
    val characterLorebookId: String? = null,
    /**
     * 辅助世界书 id 列表(多选)。对齐酒馆 additional 世界书(`world_info.charLore[].extraBooks`)。
     *
     * 酒馆 additional 是客户端全局设置按角色文件名匹配、不随卡走;本仓库角色用 UUID 无该匹配机制,
     * 改为随卡存储。导出 V3 卡时辅助世界书不写进卡 JSON(酒馆卡本就不含 additional),不影响 round-trip。
     * 对话时这些世界书作为角色来源自动参与激活(除全局选中外)。[characterLorebookId] 不重复出现在本列表。
     */
    val lorebookIds: List<String> = emptyList(),
    /**
     * 回复长度档位。对齐酒馆 verbosity_levels(auto / low / medium / high)。
     * 空字符串 = auto = 不发送字段。允许传自定义字符串(如 minimal / max),由 ChatApiClient
     * 透传给后端,无效值由后端拒绝。详见类 KDoc 顶部"非 V3 spec 字段"段。
     */
    val verbosity: String = "",
    /**
     * 导入 V3 卡时本仓库未建模的 data 顶层字段(如 `group_only_greetings` / `nickname` /
     * `source` / `creation_date` 等),原样保留以保证导出 round-trip 不丢字段。
     *
     * 只存"未建模"的键:已建模字段(name / description / extensions / character_book 等)
     * 不进这里,导出时由 [com.nuttavern.data.character.CharacterCardCodec] 用当前角色的
     * 已建模字段覆盖,再合并本字段里的未建模键。null = 没有未建模字段(新建角色 / 老数据)。
     */
    val rawCardData: JsonObject? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    companion object {
        val EMPTY_JSON_OBJECT: JsonObject = buildJsonObject { }

        /** verbosity 档位预设值。空字符串("")等价于 auto。自定义值通过 UI 文本框输入。 */
        val VERBOSITY_PRESETS: List<String> = listOf("", "low", "medium", "high")
    }
}
