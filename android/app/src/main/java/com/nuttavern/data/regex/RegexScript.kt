package com.nuttavern.data.regex

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 正则脚本。**字段全集对齐酒馆 `extensions/regex/engine.js` SCRIPT_TYPES**。
 *
 * 序列化形态保持与酒馆原 JSON 兼容:
 * - [placement] 用 Int 列表,值映射详见 [RegexPlacement.value];
 * - [substituteRegex] 用 Int,值映射详见 [SubstituteRegex.value];
 * - flags 直接编码进 [findRegex]("/pattern/flags" 形式,与酒馆一致)。
 *
 * 三作用域共用本类型:
 * - [RegexScope.GLOBAL]:[RegexScriptRepository.expandedEnabledScripts];
 * - [RegexScope.SCOPED]:[com.nuttavern.data.character.Character.regexScripts];
 * - [RegexScope.PRESET]:[com.nuttavern.data.preset.Preset.extensions] 的 `regex_scripts` 节点。
 */
@Serializable
data class RegexScript(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("scriptName") val scriptName: String = "",
    @SerialName("findRegex") val findRegex: String = "",
    @SerialName("replaceString") val replaceString: String = "",
    @SerialName("trimStrings") val trimStrings: List<String> = emptyList(),
    /** 适用阶段 int 列表。值见 [RegexPlacement.value]。 */
    @SerialName("placement") val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    @SerialName("markdownOnly") val markdownOnly: Boolean = false,
    @SerialName("promptOnly") val promptOnly: Boolean = false,
    @SerialName("runOnEdit") val runOnEdit: Boolean = false,
    /** 替换串预处理模式。值见 [SubstituteRegex.value]。 */
    @SerialName("substituteRegex") val substituteRegex: Int = SubstituteRegex.NONE.value,
    @SerialName("minDepth") val minDepth: Int? = null,
    @SerialName("maxDepth") val maxDepth: Int? = null,
)

/**
 * 正则适用阶段。对齐酒馆 `regex_placement`:
 *
 * | 名称 | int 值 | 说明 |
 * |---|---|---|
 * | [USER_INPUT] | 1 | 用户输入文本(发送前) |
 * | [AI_OUTPUT] | 2 | 模型回复文本(流式完成后) |
 * | [SLASH_COMMAND] | 3 | slash command 输出(本仓库未规划,不消费) |
 * | [WORLD_INFO] | 5 | 世界书条目展开后(等 Lorebook 落地) |
 * | [REASONING] | 6 | reasoning 字段渲染前 |
 *
 * 酒馆 `MD_DISPLAY = 0` 已 deprecated,本仓库不实现。未知 int 值通过 [fromValue] 返回 null,
 * 由调用方按"无效条目"处理(运行时跳过,不抛异常)。
 */
enum class RegexPlacement(val value: Int) {
    USER_INPUT(1),
    AI_OUTPUT(2),
    SLASH_COMMAND(3),
    WORLD_INFO(5),
    REASONING(6),
    ;

    companion object {
        fun fromValue(value: Int): RegexPlacement? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 替换串预处理模式。对齐酒馆 `substitute_find_regex`:
 *
 * | 名称 | int 值 | 行为 |
 * |---|---|---|
 * | [NONE] | 0 | 替换串原样使用 |
 * | [RAW] | 1 | 替换串先做占位符替换({{user}} 等),不做 regex 元字符转义 |
 * | [ESCAPED] | 2 | 替换串先做占位符替换 + 元字符转义,确保替换文本里的 `$1` / `\$&` 不被当作分组引用 |
 */
enum class SubstituteRegex(val value: Int) {
    NONE(0),
    RAW(1),
    ESCAPED(2),
    ;

    companion object {
        fun fromValue(value: Int): SubstituteRegex = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/**
 * 正则作用域。运行时按 GLOBAL → SCOPED → PRESET 顺序执行,与酒馆一致。
 *
 * - [GLOBAL]:用户全局正则,跨角色 / 预设;
 * - [SCOPED]:角色卡内嵌正则,只对当前会话锁定的角色生效;
 * - [PRESET]:预设内嵌正则,只对当前预设生效。
 */
enum class RegexScope {
    GLOBAL,
    SCOPED,
    PRESET,
}
