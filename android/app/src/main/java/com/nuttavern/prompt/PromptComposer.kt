package com.nuttavern.prompt

import com.nuttavern.data.character.Character
import com.nuttavern.data.persona.PersonaPosition
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.data.preset.GenerationType
import com.nuttavern.data.preset.InjectionPosition
import com.nuttavern.data.preset.Preset
import com.nuttavern.data.preset.PromptEntry
import com.nuttavern.data.preset.PromptOrderEntry
import com.nuttavern.data.preset.PromptOrderForCharacter
import com.nuttavern.data.preset.PromptRole
import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.network.ChatMessage
import com.nuttavern.network.GenerationParams
import com.nuttavern.regex.RegexEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prompt 拼接管线。**以预设 [Preset] 为骨架**驱动整个流程,对齐酒馆 chat completion 拼接策略。
 *
 * # 总体流程
 *
 * ```
 * A. 准备阶段
 *    1. 取出预设的 prompt_order(全局或当前角色)→ 决定 prompts 遍历顺序;
 *    2. 应用角色卡 system_prompt / post_history_instructions 覆盖到 main / jailbreak 条目内容
 *       (除非条目 forbid_overrides=true);
 *    3. 把 marker 条目的运行时内容生成出来(角色字段、persona 描述、世界书占位、history 占位等)。
 *
 * B. 拼接阶段(按 prompt_order 顺序遍历)
 *    1. 对每条 enabled 的 PromptEntry:
 *       a. 若 injection_trigger 不空且当前生成类型不在其中 → 跳过;
 *       b. 若 marker → 按 identifier 走对应 marker handler:
 *          - chatHistory:占位,在 B 段结束后实际追加历史 + 用户消息;
 *          - dialogueExamples:占位,B 段结束后实际追加 mes_example;
 *          - charDescription / charPersonality / scenario / personaDescription /
 *            worldInfoBefore / worldInfoAfter:对应文本拼到 system 段;
 *          - 其它未知 marker:跳过(留给后续模块,如 worldInfo 接入时填回);
 *       c. 若非 marker:
 *          - injection_position == RELATIVE:按 role 拼到 system 段(role==system 直接拼,
 *            role==user/assistant 各自起一条独立 message,插在 history 之前);
 *          - injection_position == ABSOLUTE:存到 absolute 注入清单,B 段结束后按 depth/order 注入。
 *
 * C. messages 组装
 *    1. system 段(B 段累积的 system_text)→ 第一条 system message;
 *    2. RELATIVE 模式下产生的 user / assistant message → 紧跟在 system 之后;
 *    3. mes_example messages(若 dialogueExamples 在 prompt_order 里启用) → 紧接其后;
 *    4. history → 接在 mes_example 之后;
 *    5. 用户当前消息(若 [PromptComposerInput.userMessage] 非空) → 末尾;
 *    6. post_history_instructions → 拼到最后一条 user 消息内容尾部(沿用酒馆 PHI 行为)。
 *
 * D. ABSOLUTE 注入
 *    把 B 段收集的 ABSOLUTE 条目按 (depth 倒序, order 升序) 插入 messages,
 *    depth=N 表示倒数第 N 条之前。同时执行 persona AT_DEPTH 注入(并入同一个清单)。
 *
 * E. 占位符替换
 *    全量扫描 system_text + messages.content,调 [PlaceholderResolver]。
 *
 * F. 输出 generationParams
 *    把预设的生成参数(temperature / max_tokens / 各 penalty / seed / n 等)映射到
 *    [GenerationParams],随 [PromptComposerOutput] 一起返回,供 ChatApiClient 透传到请求体。
 *    `verbosity` 来自当前 [Character.verbosity];reasoning 走会话级
 *    [com.nuttavern.ui.viewmodel.ChatViewModel.draftThinkingLevel](由 ChatViewModel 注入到
 *    [GenerationParams.thinkingLevelOverride]);`customPostProcessing` 来自当前 Provider,
 *    由 ChatViewModel 在调用 ChatApiClient 前注入。
 * ```
 *
 * # 节点失败兜底
 *
 * 任何条目处理抛异常时,跳过该条目并记录到 diagnostics,不让整条管线崩溃;同时缺省字段(预设
 * 为 null / character 为 null / persona 为 null)走 null-safe 路径,从拼接结果里直接缺席。
 *
 * # 当前与酒馆的有意偏差
 *
 * - **prompt_order 当前只读全局排序**(`character_id == GLOBAL_CHARACTER_ID`)。"按角色调整 prompt 顺序"
 *   是后续 UI 接入项,数据 schema 已就位([PromptOrderForCharacter.characterId] 用 String UUID),
 *   等 UI 暴露后只需要按当前角色 id 查 [Preset.promptOrder] 即可,不改 PromptComposer 主流程。
 * - **persona AT_DEPTH 仍走自身 [PersonaPosition] 字段**,与预设的 ABSOLUTE 条目共享同一注入清单。
 *   这意味着 persona 永远会被注入,不受 prompt_order 控制 — 与酒馆 personaDescription marker
 *   行为有差异(酒馆是把 personaDescription 也作为一条 marker 由 prompt_order 决定),
 *   这是有意保留:Nut Tavern 的 persona 模块独立于预设,有自己的开关("无"伪卡 / position=NONE)。
 */
@Singleton
class PromptComposer @Inject constructor(
    private val placeholderResolver: PlaceholderResolver,
    private val regexEngine: RegexEngine,
) {
    fun compose(input: PromptComposerInput): PromptComposerOutput {
        val diagnostics = mutableListOf<String>()
        val preset = input.preset
        val character = input.character
        val persona = input.userPersona
        val generationType = input.generationType

        // A0. 用户输入先跑 USER_INPUT 阶段正则。**对齐酒馆 sendMessageAsUser**:
        // 正则在占位符替换之前应用,这样 {{user}} / {{char}} 不会被正则脚本里的 `\b\w+\b`
        // 类规则误匹配。占位符替换在 E. 阶段统一做。
        //
        // isPrompt=true:本节点只改 prompt,不动用户输入的聊天文件;只跑 promptOnly=true 的脚本。
        // 对齐 RegexEngine "三场景门控"中"仅 prompt 拼接(短暂)"分支。改聊天文件那一档由
        // ChatViewModel.applyUserInputRegexForChatFile 处理(两个 only 都 false 的脚本)。
        val placeholderContext = buildPlaceholderContext(input)
        val processedUserMessage = input.userMessage?.let { raw ->
            runNode("regex.userInput", diagnostics) {
                regexEngine.getRegexedString(
                    raw = raw,
                    placement = RegexPlacement.USER_INPUT,
                    globalScripts = input.globalRegexScripts,
                    scopedScripts = character?.regexScripts.orEmpty(),
                    presetScripts = extractPresetRegexScripts(preset),
                    characterAllowed = input.characterAllowedRegex,
                    presetAllowed = input.presetAllowedRegex,
                    isPrompt = true,
                    placeholderContext = placeholderContext,
                )
            } ?: raw
        }

        // 应用角色卡覆盖到 main / jailbreak 条目(除非 forbid_overrides)。
        val effectivePrompts = applyCharacterOverrides(preset.prompts, character)
        val orderedItems = resolvePromptOrder(preset, effectivePrompts, character?.id)

        val systemBuilder = StringBuilder()
        val preHistoryMessages = mutableListOf<ChatMessage>()
        val absoluteInjections = mutableListOf<AbsoluteInjection>()
        var includeMesExamples = false
        var includeChatHistory = true
        // 追踪 personaDescription marker 是否真的被消费(出现在 prompt_order 里 + enabled)。
        // 没消费时 persona system 段在 B 段结束后兜底拼上,与 personaSystemBlock 的 KDoc 一致 —
        // Nut Tavern 的 persona 模块独立于预设,默认应该工作,预设禁用了 marker 也不能让 persona 消失。
        var personaMarkerConsumed = false

        // B. 遍历 prompt_order。
        orderedItems.forEach { item ->
            val entry = item.entry
            val orderEntry = item.orderEntry
            if (!orderEntry.enabled) return@forEach
            // injection_trigger 过滤:空列表 = 任意触发都通过。
            if (entry.injectionTrigger.isNotEmpty() && generationType != null &&
                generationType !in entry.injectionTrigger
            ) {
                return@forEach
            }

            try {
                if (entry.marker) {
                    when (entry.identifier) {
                        ID_CHAT_HISTORY -> includeChatHistory = true
                        ID_DIALOGUE_EXAMPLES -> includeMesExamples = true
                        ID_CHAR_DESCRIPTION -> character?.description
                            ?.takeIf { it.isNotBlank() }?.let { systemBuilder.appendBlock(it) }
                        ID_CHAR_PERSONALITY -> character?.personality
                            ?.takeIf { it.isNotBlank() }?.let { raw ->
                                val formatted = applyFormat(preset.personalityFormat, raw, "personality")
                                systemBuilder.appendBlock(formatted)
                            }
                        ID_SCENARIO -> character?.scenario
                            ?.takeIf { it.isNotBlank() }?.let { raw ->
                                val formatted = applyFormat(preset.scenarioFormat, raw, "scenario")
                                systemBuilder.appendBlock(formatted)
                            }
                        ID_PERSONA_DESCRIPTION -> {
                            personaMarkerConsumed = true
                            personaSystemBlock(persona)?.let { systemBuilder.appendBlock(it) }
                        }
                        ID_WORLD_INFO_BEFORE,
                        ID_WORLD_INFO_AFTER -> {
                            // 世界书 marker 占位,Lorebook 模块上线后填回。
                        }
                        // 未识别 marker 留给扩展模块,跳过。
                    }
                } else {
                    val content = entry.content.trim()
                    if (content.isBlank()) return@forEach
                    when (entry.injectionPosition) {
                        InjectionPosition.RELATIVE -> appendRelativeEntry(entry, content, systemBuilder, preHistoryMessages)
                        InjectionPosition.ABSOLUTE -> absoluteInjections += AbsoluteInjection(
                            depth = entry.injectionDepth,
                            order = entry.injectionOrder,
                            role = entry.role,
                            content = content,
                        )
                    }
                }
            } catch (error: Throwable) {
                diagnostics += "prompt '${entry.identifier}' skipped: ${error.message ?: error::class.simpleName}"
            }
        }

        // personaDescription marker 兜底:prompt_order 里没有(或被禁用)时,system 类 persona
        // (IN_PROMPT / TOP_AN / BOTTOM_AN)仍应拼到 system 段。AT_DEPTH 走自己的 absoluteInjections
        // 路径,不在这里兜底。
        if (!personaMarkerConsumed) {
            runNode("persona.systemFallback", diagnostics) {
                personaSystemBlock(persona)?.let { systemBuilder.appendBlock(it) }
            }
        }

        // C. messages 组装。
        val composedMessages = mutableListOf<ChatMessage>()
        composedMessages += preHistoryMessages
        if (includeMesExamples) {
            composedMessages += parseMessageExamples(character?.messageExample)
        }
        if (includeChatHistory) {
            input.history.forEach { msg ->
                composedMessages += ChatMessage(role = msg.role, content = msg.content)
            }
            // 用户输入可空:重试 / regenerate 路径不带新用户消息。
            processedUserMessage?.takeIf { it.isNotBlank() }?.let {
                composedMessages += ChatMessage(role = "user", content = it)
            }
        }

        // PHI:对齐酒馆 jailbreak 行为 — 拼到最后一条 user 消息尾部;无 user 时退化成独立 system。
        runNode("character.postHistoryInstructions", diagnostics) {
            val phi = character?.postHistoryInstructions?.takeIf { it.isNotBlank() } ?: return@runNode
            // 若 jailbreak 已通过 prompt_order 注入(content == phi)就不重复拼;
            // 这里覆盖 jailbreak 的处理已经在 applyCharacterOverrides 完成,jailbreak 走条目路径,
            // 不会重复;PHI 仍保留兜底以防角色卡有 PHI 但预设里 jailbreak 条目被禁用。
            val mainEntry = effectivePrompts.firstOrNull { it.identifier == ID_JAILBREAK }
            val jailbreakEnabled = orderedItems.any {
                it.entry.identifier == ID_JAILBREAK && it.orderEntry.enabled
            }
            if (mainEntry != null && jailbreakEnabled && !mainEntry.forbidOverrides) return@runNode

            val lastUserIndex = composedMessages.indexOfLast { it.role == "user" }
            if (lastUserIndex >= 0) {
                val original = composedMessages[lastUserIndex]
                composedMessages[lastUserIndex] = original.copy(
                    content = original.content + "\n\n" + phi,
                )
            } else {
                composedMessages += ChatMessage(role = "system", content = phi)
            }
        }

        // D. ABSOLUTE 注入(预设条目 + persona AT_DEPTH 共用清单)。
        runNode("persona.atDepth", diagnostics) {
            personaAbsoluteInjection(persona)?.let { absoluteInjections += it }
        }
        applyAbsoluteInjections(absoluteInjections, composedMessages)

        // E. 占位符替换。
        val resolvedSystemPrompt = runNode("placeholder.system", diagnostics) {
            placeholderResolver.resolve(systemBuilder.toString(), placeholderContext)
        } ?: systemBuilder.toString()

        val resolvedMessages = composedMessages.map { message ->
            val resolvedContent = runNode("placeholder.message", diagnostics) {
                placeholderResolver.resolve(message.content, placeholderContext)
            } ?: message.content
            message.copy(content = resolvedContent)
        }

        // F. 生成参数。
        val generationParams = mapPresetToGenerationParams(preset, input.character)

        return PromptComposerOutput(
            messages = resolvedMessages,
            systemPrompt = resolvedSystemPrompt.takeIf { it.isNotBlank() },
            generationParams = generationParams,
            diagnostics = diagnostics.toList(),
        )
    }

    private fun appendRelativeEntry(
        entry: PromptEntry,
        content: String,
        systemBuilder: StringBuilder,
        preHistoryMessages: MutableList<ChatMessage>,
    ) {
        when (entry.role) {
            PromptRole.SYSTEM -> systemBuilder.appendBlock(content)
            PromptRole.USER -> preHistoryMessages += ChatMessage(role = "user", content = content)
            PromptRole.ASSISTANT -> preHistoryMessages += ChatMessage(role = "assistant", content = content)
        }
    }

    /**
     * 把角色卡的 systemPrompt / postHistoryInstructions 覆盖到预设的 main / jailbreak 条目内容上。
     *
     * forbidOverrides=true 的条目 / 空覆盖文本一律不动。返回新的 prompts 列表(原列表不变)。
     */
    private fun applyCharacterOverrides(
        prompts: List<PromptEntry>,
        character: Character?,
    ): List<PromptEntry> {
        if (character == null) return prompts
        return prompts.map { entry ->
            when (entry.identifier) {
                ID_MAIN -> {
                    val override = character.systemPrompt.takeIf { it.isNotBlank() }
                    if (override != null && !entry.forbidOverrides) entry.copy(content = override) else entry
                }
                ID_JAILBREAK -> {
                    val override = character.postHistoryInstructions.takeIf { it.isNotBlank() }
                    if (override != null && !entry.forbidOverrides) entry.copy(content = override) else entry
                }
                else -> entry
            }
        }
    }

    /**
     * 解析当前应使用的 prompt 排序。
     *
     * 优先级:角色专属顺序(若存在) > 全局顺序 > 顺序里没出现的 prompts 全部追加。
     * 当前 UI 还没暴露"角色专属顺序"开关,实际只走全局顺序;角色专属顺序的字段已就位,
     * 后续接入只改这一个 helper 不动主流程。
     */
    private fun resolvePromptOrder(
        preset: Preset,
        prompts: List<PromptEntry>,
        characterId: String?,
    ): List<OrderedItem> {
        val byId = prompts.associateBy { it.identifier }
        val perCharacter = characterId?.let { id ->
            preset.promptOrder.firstOrNull { it.characterId == id }
        }
        val global = preset.promptOrder.firstOrNull {
            it.characterId == PromptOrderForCharacter.GLOBAL_CHARACTER_ID
        }
        val order = perCharacter?.order ?: global?.order ?: emptyList()
        val ordered = order.mapNotNull { entry ->
            byId[entry.identifier]?.let { OrderedItem(it, entry) }
        }
        // 严格:不在 prompt_order 里的条目 = 未链接,不参与拼接。
        return ordered
    }

    private fun applyFormat(format: String, value: String, key: String): String {
        if (format.isBlank() || format == "{{${key}}}") return value
        return format.replace("{{${key}}}", value).replace("{0}", value)
    }

    /**
     * 用户身份 system 段块。处理 [PersonaPosition.IN_PROMPT] / [PersonaPosition.TOP_AN] /
     * [PersonaPosition.BOTTOM_AN]。AT_DEPTH 走 [personaAbsoluteInjection];NONE / "无"伪卡返回 null。
     *
     * 注意 personaDescription marker 与本方法**互补**:
     * - 预设里 personaDescription 启用 + persona.position 是 system 类(IN_PROMPT/AN) → 走 marker 路径;
     * - 预设里 personaDescription 禁用 → 仍然由本方法兜底拼到 system 段。
     *
     * 这避免了"预设禁用 personaDescription 后 persona 完全消失"的反直觉行为 —— Nut Tavern persona
     * 模块独立,默认应该工作。
     */
    private fun personaSystemBlock(persona: UserPersona?): String? {
        if (persona == null || persona.isNonePersona) return null
        if (persona.position == PersonaPosition.NONE) return null
        if (persona.position == PersonaPosition.AT_DEPTH) return null
        return personaText(persona)
    }

    private fun personaAbsoluteInjection(persona: UserPersona?): AbsoluteInjection? {
        if (persona == null || persona.isNonePersona) return null
        if (persona.position != PersonaPosition.AT_DEPTH) return null
        val text = personaText(persona) ?: return null
        val role = when (persona.role) {
            com.nuttavern.data.persona.PersonaRole.SYSTEM -> PromptRole.SYSTEM
            com.nuttavern.data.persona.PersonaRole.USER -> PromptRole.USER
            com.nuttavern.data.persona.PersonaRole.ASSISTANT -> PromptRole.ASSISTANT
        }
        return AbsoluteInjection(
            depth = persona.depth,
            order = PromptEntry.DEFAULT_INJECTION_ORDER,
            role = role,
            content = text,
        )
    }

    /**
     * 应用 ABSOLUTE 注入清单到 messages。
     *
     * 排序:depth 大的先插(让小 depth 在更靠后位置不被挤偏);depth 相等时 order 小的先插。
     * 这与酒馆 setExtensionPrompt(depth=N) 的"depth=0 = 最末尾,depth=N = 倒数第 N 条之前"语义一致。
     */
    private fun applyAbsoluteInjections(
        injections: List<AbsoluteInjection>,
        messages: MutableList<ChatMessage>,
    ) {
        if (injections.isEmpty()) return
        val sorted = injections.sortedWith(
            compareByDescending<AbsoluteInjection> { it.depth }.thenBy { it.order },
        )
        sorted.forEach { injection ->
            val role = when (injection.role) {
                PromptRole.SYSTEM -> "system"
                PromptRole.USER -> "user"
                PromptRole.ASSISTANT -> "assistant"
            }
            val insertIndex = (messages.size - injection.depth).coerceIn(0, messages.size)
            messages.add(insertIndex, ChatMessage(role = role, content = injection.content))
        }
    }

    /**
     * 用户身份描述文本。优先 description,name 单独存在时用 "I am {{name}}." 兜底,
     * 两者都空返回 null(让节点跳过整段)。
     */
    private fun personaText(persona: UserPersona): String? {
        val description = persona.description.trim()
        val name = persona.name.trim()
        return when {
            description.isNotBlank() -> description
            name.isNotBlank() -> "I am $name."
            else -> null
        }
    }

    /**
     * 解析 mes_example 为 ChatMessage 列表。每段(以 `<START>` 分隔)作为一条 system 消息。
     *
     * 不做"逐行拆 user/assistant"的工作:那一步与预设的格式化模板耦合,等预设接入再做。
     */
    private fun parseMessageExamples(raw: String?): List<ChatMessage> {
        val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
        return EXAMPLE_DELIMITER.split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { ChatMessage(role = "system", content = it) }
    }

    private fun buildPlaceholderContext(input: PromptComposerInput): PlaceholderContext {
        val character = input.character
        val persona = input.userPersona

        val charName = character?.name?.takeIf { it.isNotBlank() }
        val userName = persona?.takeIf { !it.isNonePersona }?.name?.takeIf { it.isNotBlank() }
        val personaDescription = persona?.takeIf { !it.isNonePersona }?.description?.takeIf { it.isNotBlank() }

        val historyMessages = input.history
        val lastMessage = historyMessages.lastOrNull()?.content
        val lastUserMessage = historyMessages.lastOrNull { it.role == "user" }?.content
        val lastCharMessage = historyMessages.lastOrNull { it.role == "assistant" }?.content
        val totalMessages = historyMessages.size

        val chatStats = ChatStats(
            lastMessage = lastMessage,
            lastUserMessage = lastUserMessage,
            lastCharMessage = lastCharMessage,
            lastMessageId = if (totalMessages > 0) totalMessages - 1 else null,
            totalMessageCount = totalMessages,
        )

        return PlaceholderContext(
            user = userName,
            char = charName,
            description = character?.description?.takeIf { it.isNotBlank() },
            personality = character?.personality?.takeIf { it.isNotBlank() },
            scenario = character?.scenario?.takeIf { it.isNotBlank() },
            persona = personaDescription,
            charPrompt = character?.systemPrompt?.takeIf { it.isNotBlank() },
            charJailbreak = character?.postHistoryInstructions?.takeIf { it.isNotBlank() },
            mesExamplesRaw = character?.messageExample?.takeIf { it.isNotBlank() },
            charVersion = character?.characterVersion?.takeIf { it.isNotBlank() },
            creatorNotes = character?.creatorNotes?.takeIf { it.isNotBlank() },
            chatStats = chatStats,
        )
    }

    /**
     * 把预设字段映射到网络层 [GenerationParams]。仅对预设字段做语义转换,不做模型 / Provider 识别;
     * 兼容性裁剪在 [com.nuttavern.network.ChatApiClient] 的各 Provider build 函数里完成。
     */
    /**
     * 把预设映射成 [GenerationParams]。各家 Provider 在 ChatApiClient 里按支持度裁剪。
     *
     * 兼容性裁剪在 [com.nuttavern.network.ChatApiClient] 的各 Provider build 函数里完成。
     *
     * 字段来源说明:
     * - 数值参数(temperature / maxTokens / topP 等)/ 流式 / 偏置 → 来自 [preset];
     * - [GenerationParams.verbosityRawValue] → 来自 [character] 的 verbosity 字段;
     * - [GenerationParams.customPostProcessing] → 在 ChatViewModel 阶段从当前 Provider 单独
     *   写入,这里留空(不在 PromptComposer 里持有 Provider 引用)。
     */
    private fun mapPresetToGenerationParams(
        preset: Preset,
        character: Character?,
    ): GenerationParams {
        return GenerationParams(
            temperature = preset.temperature,
            maxTokens = preset.openaiMaxTokens.takeIf { it > 0 },
            topP = preset.topP,
            topK = preset.topK,
            topA = preset.topA,
            minP = preset.minP,
            frequencyPenalty = preset.frequencyPenalty,
            presencePenalty = preset.presencePenalty,
            repetitionPenalty = preset.repetitionPenalty,
            seed = preset.seed,
            n = preset.n,
            streamEnabled = preset.streamEnabled,
            verbosityRawValue = character?.verbosity.orEmpty(),
            logitBias = collectLogitBias(preset),
            stop = emptyList(),
            customPostProcessing = null, // ChatViewModel 注入当前 Provider 的字段
            thinkingLevelOverride = null,
        )
    }

    private fun collectLogitBias(preset: Preset): Map<String, Int> {
        val selected = preset.biasPresetSelected
        val entries = preset.biasPresets[selected].orEmpty()
        if (entries.isEmpty()) return emptyMap()
        // OpenAI logit_bias 期望 token id (string) → bias map。
        // 当前未做 tokenizer,直接把"text → bias"作为字符串 key 透传;支持 token id 的中转能识别。
        return entries.associate { it.text to it.value }
            .filterKeys { it.isNotBlank() }
    }

    /**
     * 从 [Preset.extensions] 取 `regex_scripts` 节点。**对齐酒馆**:
     * 预设里的正则脚本不进 Preset 顶层 schema,挂在 extensions 里,导入导出 round-trip 时
     * 与酒馆 JSON 兼容。解析失败 / 节点不存在返回空列表,不抛异常。
     */
    private fun extractPresetRegexScripts(preset: Preset): List<RegexScript> {
        val node = preset.extensions["regex_scripts"] ?: return emptyList()
        return runCatching {
            PRESET_REGEX_JSON.decodeFromJsonElement(REGEX_SCRIPT_LIST_SERIALIZER, node)
        }.getOrDefault(emptyList())
    }

    private inline fun <T> runNode(name: String, diagnostics: MutableList<String>, block: () -> T): T? {
        return try {
            block()
        } catch (error: Throwable) {
            diagnostics.add("node '$name' skipped: ${error.message ?: error::class.simpleName}")
            null
        }
    }

    private fun StringBuilder.appendBlock(text: String) {
        if (text.isBlank()) return
        if (isNotEmpty()) append("\n\n")
        append(text.trim())
    }

    /**
     * 一条 ABSOLUTE 注入。预设里的 ABSOLUTE 条目和 persona AT_DEPTH 都用这个数据袋。
     */
    private data class AbsoluteInjection(
        val depth: Int,
        val order: Int,
        val role: PromptRole,
        val content: String,
    )

    /** 顺序遍历时的视图项,把 PromptEntry 与其在 prompt_order 中的开关绑在一起。 */
    private data class OrderedItem(
        val entry: PromptEntry,
        val orderEntry: PromptOrderEntry,
    )

    private companion object {
        /** 与酒馆 mes_example 解析一致:`<START>` 大小写不敏感。 */
        val EXAMPLE_DELIMITER = Regex("<START>", RegexOption.IGNORE_CASE)

        /** 解析 [Preset.extensions] 里的 `regex_scripts` 节点用,容忍未知字段。 */
        val PRESET_REGEX_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val REGEX_SCRIPT_LIST_SERIALIZER =
            kotlinx.serialization.builtins.ListSerializer(RegexScript.serializer())

        // 酒馆系统 marker / 系统条目的 identifier 常量,与 Default.json 对齐。
        const val ID_MAIN = "main"
        const val ID_JAILBREAK = "jailbreak"
        const val ID_CHAT_HISTORY = "chatHistory"
        const val ID_DIALOGUE_EXAMPLES = "dialogueExamples"
        const val ID_CHAR_DESCRIPTION = "charDescription"
        const val ID_CHAR_PERSONALITY = "charPersonality"
        const val ID_SCENARIO = "scenario"
        const val ID_PERSONA_DESCRIPTION = "personaDescription"
        const val ID_WORLD_INFO_BEFORE = "worldInfoBefore"
        const val ID_WORLD_INFO_AFTER = "worldInfoAfter"
    }
}

/**
 * PromptComposer 输入。
 *
 * - [userMessage]:用户刚发的消息文本。重试 / regenerate 路径传 null,只用历史最后一条。
 * - [history]:当前会话的历史消息(按时间正序),不含用户当前正在发的消息。
 * - [character]:当前角色(可空)。null 时跳过所有角色字段节点。
 * - [userPersona]:当前生效用户身份(可空 / 伪卡)。
 * - [preset]:当前会话锁定的预设。**生产路径永远非空且为真实预设**(由调用方保证,
 *   仓库默认预设兜底)。单测便利期默认走 [Preset.default],等价于"用户首次启动的默认预设"。
 * - [generationType]:本次生成的类型(影响 injection_trigger 过滤)。null 表示不过滤。
 * - [globalRegexScripts]:用户全局正则脚本(GLOBAL 作用域)。空列表 = 没启用任何全局正则。
 * - [characterAllowedRegex]:用户级总开关,关闭后角色卡内嵌正则(SCOPED)不参与执行。
 * - [presetAllowedRegex]:用户级总开关,关闭后预设内嵌正则(PRESET)不参与执行。
 */
data class PromptComposerInput(
    val userMessage: String?,
    val history: List<HistoryMessage>,
    val character: Character?,
    val userPersona: UserPersona?,
    val preset: Preset = Preset.default(),
    val generationType: GenerationType? = GenerationType.NORMAL,
    val globalRegexScripts: List<RegexScript> = emptyList(),
    val characterAllowedRegex: Boolean = true,
    val presetAllowedRegex: Boolean = true,
)

/**
 * 历史消息的最小数据袋。和 [com.nuttavern.data.model.Message] 字段对齐,但不带 reasoning,
 * 让 PromptComposer 不依赖 ChatViewModel 的 Message 类型。
 */
data class HistoryMessage(
    val role: String,
    val content: String,
)

/**
 * PromptComposer 输出。
 *
 * - [systemPrompt]:第一条 system 消息的内容。空时调用方应跳过 system 消息。
 * - [messages]:user / assistant / system 交替的消息列表,直接送给 [com.nuttavern.network.ChatApiClient]。
 * - [generationParams]:从预设映射出的生成参数,由 ChatApiClient 透传到请求体。
 * - [diagnostics]:节点跳过 / 失败的诊断信息。生产侧可丢弃,debug 时可打印。
 */
data class PromptComposerOutput(
    val messages: List<ChatMessage>,
    val systemPrompt: String?,
    val generationParams: GenerationParams,
    val diagnostics: List<String> = emptyList(),
)
