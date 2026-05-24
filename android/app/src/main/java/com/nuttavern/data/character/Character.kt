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
    @SerialName("character_book") val characterBook: CharacterBook? = null,
    @SerialName("regex_scripts") val regexScripts: List<RegexScript> = emptyList(),
    val avatarPath: String? = null,
    /**
     * 回复长度档位。对齐酒馆 verbosity_levels(auto / low / medium / high)。
     * 空字符串 = auto = 不发送字段。允许传自定义字符串(如 minimal / max),由 ChatApiClient
     * 透传给后端,无效值由后端拒绝。详见类 KDoc 顶部"非 V3 spec 字段"段。
     */
    val verbosity: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    companion object {
        val EMPTY_JSON_OBJECT: JsonObject = buildJsonObject { }

        /** verbosity 档位预设值。空字符串("")等价于 auto。自定义值通过 UI 文本框输入。 */
        val VERBOSITY_PRESETS: List<String> = listOf("", "low", "medium", "high")
    }
}
