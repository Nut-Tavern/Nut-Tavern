package com.nuttavern.data.preset

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 预设(对齐酒馆 chat completion preset)。
 *
 * Nut Tavern 是**类酒馆**客户端,字段与酒馆 OpenAI preset JSON 一一对应,只裁剪 text completion
 * 路径独有的字段(instruct mode / context template / story string 等),以及由 Provider 模块
 * 单独管理的连接信息(各家 model_select / api key / base url / proxy / azure / vertex 配置等)。
 *
 * 字段命名:酒馆原始字段名是 snake_case,这里通过 [SerialName] 适配 Kotlin 的 camelCase,
 * 序列化形态保持与酒馆 JSON 一致,导入导出零成本。
 *
 * # 字段分组
 *
 * 1. **元信息**:[id] / [name] / [description] / [createdAt] / [updatedAt]。
 * 2. **生成参数**:temperature / topP / topK / topA / minP / freqPenalty / presPenalty /
 *    repetitionPenalty / openaiMaxContext / openaiMaxTokens / streamEnabled / seed / n。
 * 3. **提示词条目**:[prompts] + [promptOrder],配合 marker 与 injection 字段实现完整酒馆拼接管线。
 * 4. **拼接控制**:sendIfEmpty / impersonationPrompt / newChatPrompt / newGroupChatPrompt /
 *    newExampleChatPrompt / continueNudgePrompt / wiFormat / scenarioFormat /
 *    personalityFormat / groupNudgePrompt。
 * 5. **API 行为(消息格式化相关)**:namesBehavior / continuePostfix / continuePrefill /
 *    squashSystemMessages / useSysprompt / mediaInlining /
 *    assistantPrefill / assistantImpersonation。
 * 6. **逻辑偏置**:[biasPresetSelected] + [biasPresets]。
 * 7. **扩展槽**:[extensions] 任意 JSON 透传,服务于第三方扩展。
 *
 * # 不进 Preset 的字段(与酒馆 default_settings 但不在 Default.json 的字段)
 *
 * 这些字段酒馆放 default_settings,Default.json 不写,实质是"全局生成行为 / 连接配置",
 * 跨预设通用,不应跟随预设切换:
 *
 * - 工具 / 推理类 → 见 [com.nuttavern.data.tools.ToolsSettings](`tool_call_recurse_limit` /
 *   `tool_reasoning_mode`);剩余 `function_calling` / `enable_web_search` / `show_thoughts` /
 *   `reasoning_effort` 由其他模块承担(MCP / Provider / 会话级 thinkingLevel / UI 默认行为)。
 * - 输出长度 [Verbosity] → 移入 [com.nuttavern.data.character.Character.verbosity],
 *   每个角色独立绑定。
 * - 图片输出 (`request_images` / `request_image_aspect_ratio` / `request_image_resolution`)
 *   → 留待 Provider 模块图片生成功能上线时落地。
 * - Provider 后处理 (`custom_prompt_post_processing`) → 进 [com.nuttavern.data.model.Provider]。
 * - 连接级开关 (`show_external_models` / `bypass_status_check` / `bind_preset_to_connection`)
 *   → Nut Tavern 的 Preset 与 Provider 完全独立,这些字段直接删除,无对齐价值。
 *
 * # 默认预设
 *
 * 仓库首次启动写入 `Preset.default()`,id 固定为 [DEFAULT_PRESET_ID]。默认预设可改可删除回退,
 * 但若用户删完所有预设,仓库会自动重新塞回默认预设,保证拼接管线永远有可用预设。
 */
@Serializable
data class Preset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,

    // 生成参数(对齐酒馆 temp_openai / freq_pen_openai 等)
    @SerialName("temperature") val temperature: Double = 1.0,
    @SerialName("frequency_penalty") val frequencyPenalty: Double = 0.0,
    @SerialName("presence_penalty") val presencePenalty: Double = 0.0,
    @SerialName("top_p") val topP: Double = 1.0,
    @SerialName("top_k") val topK: Int = 0,
    @SerialName("top_a") val topA: Double = 0.0,
    @SerialName("min_p") val minP: Double = 0.0,
    @SerialName("repetition_penalty") val repetitionPenalty: Double = 1.0,
    @SerialName("openai_max_context") val openaiMaxContext: Int = 4095,
    @SerialName("openai_max_tokens") val openaiMaxTokens: Int = 300,
    @SerialName("max_context_unlocked") val maxContextUnlocked: Boolean = false,
    @SerialName("stream_openai") val streamEnabled: Boolean = true,
    @SerialName("seed") val seed: Int = -1,
    @SerialName("n") val n: Int = 1,

    // 提示词条目
    @SerialName("prompts") val prompts: List<PromptEntry> = emptyList(),
    @SerialName("prompt_order") val promptOrder: List<PromptOrderForCharacter> = emptyList(),

    // 拼接控制
    @SerialName("send_if_empty") val sendIfEmpty: String = "",
    @SerialName("impersonation_prompt") val impersonationPrompt: String = DEFAULT_IMPERSONATION_PROMPT,
    @SerialName("new_chat_prompt") val newChatPrompt: String = DEFAULT_NEW_CHAT_PROMPT,
    @SerialName("new_group_chat_prompt") val newGroupChatPrompt: String = DEFAULT_NEW_GROUP_CHAT_PROMPT,
    @SerialName("new_example_chat_prompt") val newExampleChatPrompt: String = DEFAULT_NEW_EXAMPLE_CHAT_PROMPT,
    @SerialName("continue_nudge_prompt") val continueNudgePrompt: String = DEFAULT_CONTINUE_NUDGE_PROMPT,
    @SerialName("wi_format") val wiFormat: String = DEFAULT_WI_FORMAT,
    @SerialName("scenario_format") val scenarioFormat: String = DEFAULT_SCENARIO_FORMAT,
    @SerialName("personality_format") val personalityFormat: String = DEFAULT_PERSONALITY_FORMAT,
    @SerialName("group_nudge_prompt") val groupNudgePrompt: String = DEFAULT_GROUP_NUDGE_PROMPT,

    // API 行为(只保留与"提示词拼接 / 消息格式化"直接相关的字段)
    @SerialName("names_behavior") val namesBehavior: NamesBehavior = NamesBehavior.DEFAULT,
    @SerialName("continue_postfix") val continuePostfix: ContinuePostfix = ContinuePostfix.SPACE,
    @SerialName("continue_prefill") val continuePrefill: Boolean = false,
    @SerialName("squash_system_messages") val squashSystemMessages: Boolean = false,
    @SerialName("use_sysprompt") val useSysprompt: Boolean = false,
    @SerialName("media_inlining") val mediaInlining: Boolean = true,
    @SerialName("assistant_prefill") val assistantPrefill: String = "",
    @SerialName("assistant_impersonation") val assistantImpersonation: String = "",

    // 逻辑偏置(LogitBias)
    @SerialName("bias_preset_selected") val biasPresetSelected: String = DEFAULT_BIAS_NAME,
    @SerialName("bias_presets") val biasPresets: Map<String, List<LogitBiasEntry>> = mapOf(
        DEFAULT_BIAS_NAME to emptyList(),
    ),

    // 扩展槽(任意 JSON 透传)
    @SerialName("extensions") val extensions: kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(emptyMap()),
) {
    /**
     * 是否是仓库内置的"默认预设"。删完所有预设时仓库会自动重新塞回这一份。
     */
    val isBuiltInDefault: Boolean get() = id == DEFAULT_PRESET_ID

    companion object {
        const val DEFAULT_PRESET_ID = "preset-default"
        const val DEFAULT_BIAS_NAME = "Default (none)"

        const val DEFAULT_IMPERSONATION_PROMPT =
            "[Write your next reply from the point of view of {{user}}, using the chat history so far as a guideline for the writing style of {{user}}. Don't write as {{char}} or system. Don't describe actions of {{char}}.]"
        const val DEFAULT_NEW_CHAT_PROMPT = "[Start a new Chat]"
        const val DEFAULT_NEW_GROUP_CHAT_PROMPT = "[Start a new group chat. Group members: {{group}}]"
        const val DEFAULT_NEW_EXAMPLE_CHAT_PROMPT = "[Example Chat]"
        const val DEFAULT_CONTINUE_NUDGE_PROMPT =
            "[Continue your last message without repeating its original content.]"
        const val DEFAULT_WI_FORMAT = "{0}"
        const val DEFAULT_SCENARIO_FORMAT = "{{scenario}}"
        const val DEFAULT_PERSONALITY_FORMAT = "{{personality}}"
        const val DEFAULT_GROUP_NUDGE_PROMPT = "[Write the next reply only as {{char}}.]"

        const val DEFAULT_MAIN_PROMPT =
            "Write {{char}}'s next reply in a fictional chat between {{charIfNotGroup}} and {{user}}."
        const val DEFAULT_ENHANCE_DEFINITIONS_PROMPT =
            "If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute."

        /**
         * 仓库内置默认预设。结构对齐酒馆 `default/content/presets/openai/Default.json`。
         */
        fun default(now: Long = System.currentTimeMillis()): Preset = Preset(
            id = DEFAULT_PRESET_ID,
            name = "Default",
            description = "",
            createdAt = now,
            updatedAt = now,
            prompts = defaultPromptCatalog(),
            promptOrder = listOf(
                PromptOrderForCharacter(
                    characterId = PromptOrderForCharacter.GLOBAL_CHARACTER_ID,
                    order = defaultPromptOrder(),
                ),
                // 群聊样板:数据 schema 一次到位,群聊模块接入时直接消费。
                // 默认顺序与全局一致;接入后用户可自定义群聊专属顺序。
                PromptOrderForCharacter(
                    characterId = PromptOrderForCharacter.GROUP_CHARACTER_ID,
                    order = defaultPromptOrder(),
                ),
            ),
        )

        private fun defaultPromptCatalog(): List<PromptEntry> = listOf(
            PromptEntry(
                identifier = "main",
                name = "Main Prompt",
                role = PromptRole.SYSTEM,
                content = DEFAULT_MAIN_PROMPT,
                systemPrompt = true,
            ),
            PromptEntry(
                identifier = "nsfw",
                name = "Auxiliary Prompt",
                role = PromptRole.SYSTEM,
                content = "",
                systemPrompt = true,
            ),
            PromptEntry(
                identifier = "dialogueExamples",
                name = "Chat Examples",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "jailbreak",
                name = "Post-History Instructions",
                role = PromptRole.SYSTEM,
                content = "",
                systemPrompt = true,
            ),
            PromptEntry(
                identifier = "chatHistory",
                name = "Chat History",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "worldInfoAfter",
                name = "World Info (after)",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "worldInfoBefore",
                name = "World Info (before)",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "enhanceDefinitions",
                name = "Enhance Definitions",
                role = PromptRole.SYSTEM,
                content = DEFAULT_ENHANCE_DEFINITIONS_PROMPT,
                systemPrompt = true,
                marker = false,
            ),
            PromptEntry(
                identifier = "charDescription",
                name = "Char Description",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "charPersonality",
                name = "Char Personality",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "scenario",
                name = "Scenario",
                systemPrompt = true,
                marker = true,
            ),
            PromptEntry(
                identifier = "personaDescription",
                name = "Persona Description",
                systemPrompt = true,
                marker = true,
            ),
        )

        private fun defaultPromptOrder(): List<PromptOrderEntry> = listOf(
            "main",
            "worldInfoBefore",
            "personaDescription",
            "charDescription",
            "charPersonality",
            "scenario",
            "enhanceDefinitions",
            "nsfw",
            "worldInfoAfter",
            "dialogueExamples",
            "chatHistory",
            "jailbreak",
        ).map { id ->
            PromptOrderEntry(
                identifier = id,
                enabled = id != "enhanceDefinitions",
            )
        }
    }
}

/**
 * 单条提示词。对齐酒馆 PromptManager.js Prompt 类。
 *
 * - [identifier]:稳定 id,引用 [PromptOrderEntry.identifier] 的就是它。系统条目用固定字符串
 *   ("main" / "nsfw" / "jailbreak" / "charDescription" 等),用户自定义条目用 UUID。
 * - [marker]:true 表示这条是占位条目,真正内容由拼接管线运行时填充(角色描述、世界书、聊天历史等)。
 * - [systemPrompt]:true 表示是"系统级"条目(可被角色卡的 `system_prompt` / `post_history_instructions` 覆盖),
 *   false 表示用户自定义条目(角色卡覆盖会被忽略)。
 * - [injectionPosition] / [injectionDepth] / [injectionOrder] / [injectionTrigger]:见
 *   [InjectionPosition] / [GenerationType] 注释。
 * - [forbidOverrides]:true 时禁止角色卡 system_prompt / post_history_instructions 覆盖该条目内容。
 * - [extension]:true 表示由扩展程序注入,本仓库当前没有扩展系统,字段保留待扩展模块上线。
 */
@Serializable
data class PromptEntry(
    val identifier: String = UUID.randomUUID().toString(),
    val name: String = "",
    val role: PromptRole = PromptRole.SYSTEM,
    val content: String = "",
    @SerialName("system_prompt") val systemPrompt: Boolean = false,
    val marker: Boolean = false,
    @SerialName("injection_position") val injectionPosition: InjectionPosition = InjectionPosition.RELATIVE,
    @SerialName("injection_depth") val injectionDepth: Int = DEFAULT_INJECTION_DEPTH,
    @SerialName("injection_order") val injectionOrder: Int = DEFAULT_INJECTION_ORDER,
    @SerialName("injection_trigger") val injectionTrigger: List<GenerationType> = emptyList(),
    @SerialName("forbid_overrides") val forbidOverrides: Boolean = false,
    val extension: Boolean = false,
) {
    companion object {
        /** 酒馆 PromptManager 默认 depth = 4。 */
        const val DEFAULT_INJECTION_DEPTH = 4

        /** 酒馆 PromptManager 默认 order = 100,可调到 99 / 101 等控制同 depth 内插入顺序。 */
        const val DEFAULT_INJECTION_ORDER = 100
    }
}

/**
 * 单角色的 prompt 排序。对齐酒馆 prompt_order 数组的一项。
 *
 * 酒馆 character_id 使用全局唯一 number(默认 100000 = 全局, 100001 = 群组示例),Nut Tavern
 * 角色 id 是 String UUID,这里直接用字符串。"全局排序"用 [GLOBAL_CHARACTER_ID] 标识,
 * "群聊样板排序"用 [GROUP_CHARACTER_ID]。
 *
 * 当前阶段所有会话都使用全局排序;群聊样板默认与全局一致,接入群聊模块时会读取该样板初始化
 * 群聊会话的 prompt_order。后续接入"按角色调整 prompt 顺序"时,会读取角色 UUID 对应的项。
 */
@Serializable
data class PromptOrderForCharacter(
    @SerialName("character_id") val characterId: String,
    @SerialName("order") val order: List<PromptOrderEntry> = emptyList(),
) {
    companion object {
        /** 全局排序的 character_id 标识。对齐酒馆 100000(`prompt_order[0]`)。 */
        const val GLOBAL_CHARACTER_ID = "__global__"

        /**
         * 群聊样板排序的 character_id 标识。对齐酒馆 100001(`prompt_order[1]` 群组示例)。
         *
         * 群聊接入时新群会话用本样板初始化 prompt_order;之后群会话可继续在 UI 上调整,
         * 不影响样板。schema 一次到位避免后续做迁移,与"类酒馆完整对齐"原则一致。
         */
        const val GROUP_CHARACTER_ID = "__group__"
    }
}

/**
 * 单条排序项。对齐酒馆 `prompt_order[].order[]`。
 *
 * - [identifier]:引用 [PromptEntry.identifier];
 * - [enabled]:false 时该条目跳过(不进入拼接结果)。
 */
@Serializable
data class PromptOrderEntry(
    val identifier: String,
    val enabled: Boolean = true,
)

/**
 * 单条 logit bias。对齐酒馆 `bias_presets[name][]`。
 *
 * - [text]:要偏置的文本片段;
 * - [value]:偏置强度,正负皆可。OpenAI 的 logit_bias 接受 -100 到 100。
 */
@Serializable
data class LogitBiasEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val value: Int = 0,
)

/**
 * 提示词角色。对齐酒馆 prompt.role 枚举。
 */
@Serializable
enum class PromptRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

/**
 * 注入位置。对齐酒馆 INJECTION_POSITION:
 *
 * - [RELATIVE](`0`):按 `prompt_order` 中的相对位置拼到 system 段;
 * - [ABSOLUTE](`1`):按 [PromptEntry.injectionDepth] 在历史里倒数第 N 条之前插入,
 *   role 由 [PromptEntry.role] 决定。
 */
@Serializable
enum class InjectionPosition {
    @SerialName("0") RELATIVE,
    @SerialName("1") ABSOLUTE,
}

/**
 * 注入触发器。对齐酒馆 generation type:**只有当当前生成属于这些类型时**,该条目才会注入。
 * 空列表 = 任意生成类型都触发。
 */
@Serializable
enum class GenerationType {
    @SerialName("normal") NORMAL,
    @SerialName("impersonate") IMPERSONATE,
    @SerialName("continue") CONTINUE,
    @SerialName("swipe") SWIPE,
    @SerialName("regenerate") REGENERATE,
    @SerialName("quiet") QUIET,
}

/**
 * 在 messages 中显示用户名的方式。对齐酒馆 character_names_behavior。
 *
 * - [NONE](-1):完全不带名字;
 * - [DEFAULT](0):仅群聊或 force_avatar 时带;
 * - [COMPLETION](1):走 OpenAI `name` 字段;
 * - [CONTENT](2):始终拼到 content 头部("Alice: ...")。
 */
@Serializable
enum class NamesBehavior {
    @SerialName("-1") NONE,
    @SerialName("0") DEFAULT,
    @SerialName("1") COMPLETION,
    @SerialName("2") CONTENT,
}

/** Continue 操作末尾追加的字符。对齐酒馆 continue_postfix_types。 */
@Serializable
enum class ContinuePostfix(val value: String) {
    @SerialName("") NONE(""),
    @SerialName(" ") SPACE(" "),
    @SerialName("\n") NEWLINE("\n"),
    @SerialName("\n\n") DOUBLE_NEWLINE("\n\n"),
}

/** 预设内嵌正则在 [Preset.extensions] 里的键名,对齐酒馆 `preset.extensions.regex_scripts`。 */
private const val PRESET_REGEX_SCRIPTS_KEY = "regex_scripts"

/** 解析 / 写回 [Preset.extensions] 的 `regex_scripts` 节点用,容忍酒馆 JSON 的未知字段。 */
private val PRESET_REGEX_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val PRESET_REGEX_LIST_SERIALIZER =
    kotlinx.serialization.builtins.ListSerializer(com.nuttavern.data.regex.RegexScript.serializer())

/**
 * 从 [Preset.extensions] 解析预设内嵌正则(PRESET 作用域)。对齐酒馆
 * `preset.extensions.regex_scripts`(index.js:1685 `readPresetExtensionField`)。
 *
 * 节点不存在 / 解析失败返回空列表,不抛异常。PromptComposer、ChatViewModel、预设编辑页
 * 三处共用同一口径,避免重复实现漂移。
 */
fun Preset.presetRegexScripts(): List<com.nuttavern.data.regex.RegexScript> {
    val node = extensions[PRESET_REGEX_SCRIPTS_KEY] ?: return emptyList()
    return runCatching {
        PRESET_REGEX_JSON.decodeFromJsonElement(PRESET_REGEX_LIST_SERIALIZER, node)
    }.getOrDefault(emptyList())
}

/**
 * 把预设内嵌正则写回 [Preset.extensions] 的 `regex_scripts` 节点,**保留 extensions 其他键**。
 *
 * 空列表时移除 `regex_scripts` 键(避免存空数组脏数据),与酒馆"无脚本则不写该字段"一致。
 */
fun Preset.withPresetRegexScripts(scripts: List<com.nuttavern.data.regex.RegexScript>): Preset {
    val mutated = extensions.toMutableMap()
    if (scripts.isEmpty()) {
        mutated.remove(PRESET_REGEX_SCRIPTS_KEY)
    } else {
        mutated[PRESET_REGEX_SCRIPTS_KEY] =
            PRESET_REGEX_JSON.encodeToJsonElement(PRESET_REGEX_LIST_SERIALIZER, scripts)
    }
    return copy(extensions = kotlinx.serialization.json.JsonObject(mutated))
}
