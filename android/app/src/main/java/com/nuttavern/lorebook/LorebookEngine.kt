package com.nuttavern.lorebook

import com.nuttavern.data.lorebook.CharacterFilter
import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.SelectiveLogic
import com.nuttavern.data.lorebook.WiCharacterStrategy
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import com.nuttavern.prompt.TokenCounter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 世界书激活引擎。对齐 SillyTavern `checkWorldInfo` 的核心运行语义。
 *
 * 当前不消费 vectorized、automationId(slash command)、characterFilter.tags、triggers。
 */
@Singleton
class LorebookEngine @Inject constructor(
    private val tokenCounter: TokenCounter,
) {

    data class ActivationResult(
        val worldInfoBefore: String = "",
        val worldInfoAfter: String = "",
        val depthEntries: List<DepthEntry> = emptyList(),
        val exampleTop: String = "",
        val exampleBottom: String = "",
        val activatedEntries: List<LorebookEntry> = emptyList(),
        val budgetOverflowed: Boolean = false,
        val nextTimedEffects: LorebookTimedEffectState = LorebookTimedEffectState.Empty,
    )

    data class DepthEntry(
        val depth: Int,
        val role: Int,
        val content: String,
    )

    data class ScanContext(
        val currentCharacterId: String? = null,
        val personaDescription: String = "",
        val characterDescription: String = "",
        val characterPersonality: String = "",
        val characterDepthPrompt: String = "",
        val scenario: String = "",
        val creatorNotes: String = "",
        val maxContextTokens: Int = 4096,
    )

    /**
     * 执行世界书激活。
     *
     * @param contentRegexHook 对每个被激活 entry 的 content 跑一次 WORLD_INFO placement 正则。
     *   对齐酒馆 `world-info.js:5085-5086`:只对 entry.content 跑,不对 comment / wiFormat 包装跑;
     *   返回空字符串则跳过该 entry(对齐 5088 行 `if (!content) return`)。默认 identity(不变换)。
     *
     *   **hook 调用契约**(必须满足,否则世界书正则行为偏离酒馆):
     *   - 调用方需以 `isPrompt=true` 调 RegexEngine.getRegexedString(对齐 5086 行
     *     `{ isMarkdown: false, isPrompt: true }`),否则默认脚本会错误介入世界书阶段;
     *   - 调用方需按 entry.position 计算 regexDepth:`position == AT_DEPTH ? entry.depth : null`
     *     (对齐 5085 行),否则带 minDepth/maxDepth 限定的脚本无法按 entry.depth 过滤;
     *   - hook 抛异常时引擎自身会兜原文继续(见下方实现),保证单 entry 异常不打断整次激活。
     *
     *   钩子在 selectEntriesWithinBudget 之前一次性预跑,保证预算 token 计算与最终注入用同一份内容。
     */
    fun activate(
        messages: List<String>,
        messageNames: List<String> = emptyList(),
        lorebooks: List<TaggedLorebook>,
        wiFormat: String = "",
        scanContext: ScanContext = ScanContext(),
        messageCount: Int = messages.size,
        timedEffects: LorebookTimedEffectState = LorebookTimedEffectState.Empty,
        isDryRun: Boolean = false,
        contentRegexHook: (entryContent: String, regexDepth: Int?) -> String = { content, _ -> content },
    ): ActivationResult {
        val strategy = lorebooks.firstOrNull()?.book?.characterStrategy ?: WiCharacterStrategy.CHARACTER_FIRST
        val candidates = buildCandidatesByStrategy(lorebooks, strategy)
        if (candidates.isEmpty()) {
            val activeTimedEffects = resolveActiveTimedEffects(
                candidates = emptyList(),
                currentState = timedEffects,
                messageCount = messageCount,
                isDryRun = isDryRun,
            )
            return ActivationResult(nextTimedEffects = activeTimedEffects.nextState)
        }

        val activeTimedEffects = resolveActiveTimedEffects(
            candidates = candidates,
            currentState = timedEffects,
            messageCount = messageCount,
            isDryRun = isDryRun,
        )

        val scanState = ActivationScanState(
            originalMessages = messages,
            originalMessageNames = messageNames,
            scanContext = scanContext,
            activeTimedEffects = activeTimedEffects,
            messageCount = messageCount,
        )

        val initialScan = scanCandidates(
            candidates = candidates,
            scanState = scanState,
            scanMode = ScanMode.NORMAL,
            depthOverride = null,
            recursionDelayLevel = 0,
        )
        scanState.pendingRecursionContent.addAll(initialScan.recursionContent)

        applyMinActivations(candidates, lorebooks, scanState)
        applyRecursiveScanning(candidates, lorebooks, scanState)

        val afterGrouping = resolveGroups(
            candidates = scanState.activated.values.toList(),
            scores = scanState.scores,
            globalUseGroupScoring = lorebooks.any { it.book.useGroupScoring },
            activeTimedEffects = activeTimedEffects,
        )

        val sorted = afterGrouping.sortedByDescending { it.entry.order }
        // 对齐酒馆 world-info.js:5085-5086:对 entry.content 跑 WORLD_INFO 正则;
        // regexDepth 仅在 entry.position == AT_DEPTH 时取 entry.depth,其余位置取 null。
        // hook 抛异常时兜原文继续,保证单 entry 异常不打断整次激活(对齐酒馆 forEach 的健壮性,
        // engine.js 单脚本异常不冒泡);CancellationException 必须透传,避免破坏协程结构性并发。
        val processedContents: Map<Int, String> = sorted.associate { candidate ->
            val regexDepth = if (candidate.entry.position == WiPosition.AT_DEPTH) candidate.entry.depth else null
            val processed = try {
                contentRegexHook(candidate.entry.content, regexDepth)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (hookError: Throwable) {
                // 不调 android.util.Log:它在 unit test 里默认 throw(参考 PlaceholderResolver.kt:60-66 同款约定),
                // 会让"hook 异常兜底"测试本身崩在 Log 上。改写 stderr 保留 entry uid + 异常 message 便于排查。
                System.err.println(
                    "LorebookEngine: contentRegexHook threw, fallback to raw content (uid=${candidate.entry.uid}): ${hookError.message}",
                )
                candidate.entry.content
            }
            candidate.entryHash to processed
        }
        val withinBudget = selectEntriesWithinBudget(sorted, lorebooks, scanContext, wiFormat, processedContents)
        val nextTimedEffects = if (isDryRun) {
            activeTimedEffects.nextState
        } else {
            applyNewTimedEffects(withinBudget.entries, activeTimedEffects.nextState, messageCount)
        }

        return buildResult(
            sorted = withinBudget.entries,
            wiFormat = wiFormat,
            overflowed = withinBudget.overflowed,
            nextTimedEffects = nextTimedEffects,
            processedContents = processedContents,
        )
    }

    private fun buildCandidatesByStrategy(
        lorebooks: List<TaggedLorebook>,
        strategy: Int,
    ): List<CandidateEntry> {
        // AN_TOP / AN_BOTTOM 是酒馆"环绕 author's note 文本"的两档位置(world-info.js:5076-5152),
        // 本仓库 author's note 模块未落地(见 AGENTS.md "Author's Note 模块未落地"待办),
        // 这两档没有有效注入通道。在候选入口与 disable 一同过滤,运行时完全等价于"该条目不存在":
        // - 不参与扫描 / 递归 / group 决议 / 时效状态 / 预算 / 输出注入;
        // - 不进 activatedEntries;
        // - 数据层枚举常量与 codec round-trip 完整保留,导入导出不丢字段。
        // 过滤位置选这里(早于 resolveGroups)是为了避免 AN 条目以高 groupWeight 在互斥组中胜出后
        // 又被剔除,导致同组非 AN 条目被连带压制(reviewer 双审计 R2 标记的潜在 bug)。
        fun LorebookEntry.isRuntimeInjectable(): Boolean =
            !disable && position != WiPosition.AN_TOP && position != WiPosition.AN_BOTTOM

        val characterCandidates = lorebooks
            .filter { it.isCharacterSource }
            .flatMap { tagged -> tagged.book.entries.filter { it.isRuntimeInjectable() }.map { tagged.toCandidate(it) } }

        val globalCandidates = lorebooks
            .filter { !it.isCharacterSource }
            .flatMap { tagged -> tagged.book.entries.filter { it.isRuntimeInjectable() }.map { tagged.toCandidate(it) } }

        return when (strategy) {
            WiCharacterStrategy.EVENLY -> (globalCandidates + characterCandidates).sortedByDescending { it.entry.order }
            WiCharacterStrategy.CHARACTER_FIRST -> {
                characterCandidates.sortedByDescending { it.entry.order } +
                    globalCandidates.sortedByDescending { it.entry.order }
            }
            WiCharacterStrategy.GLOBAL_FIRST -> {
                globalCandidates.sortedByDescending { it.entry.order } +
                    characterCandidates.sortedByDescending { it.entry.order }
            }
            else -> characterCandidates + globalCandidates
        }
    }

    private fun TaggedLorebook.toCandidate(entry: LorebookEntry): CandidateEntry {
        val source = sourceKey.ifBlank { book.id }
        val key = "$source.${entry.uid}"
        return CandidateEntry(
            entry = entry,
            book = book,
            sourceKey = source,
            timedEffectKey = key,
            entryHash = hashEntry(source, entry),
        )
    }

    private fun applyMinActivations(
        candidates: List<CandidateEntry>,
        lorebooks: List<TaggedLorebook>,
        scanState: ActivationScanState,
    ) {
        val minActivations = lorebooks.maxOf { it.book.minActivations }
        if (minActivations <= 0 || scanState.activated.size >= minActivations) return

        val minActivationsDepthMax = lorebooks.maxOf { it.book.minActivationsDepthMax }
        var currentDepth = lorebooks.maxOf { it.book.scanDepth }
        while (scanState.activated.size < minActivations && currentDepth < scanState.originalMessages.size) {
            if (minActivationsDepthMax > 0 && currentDepth >= minActivationsDepthMax) break
            currentDepth++
            val result = scanCandidates(
                candidates = candidates,
                scanState = scanState,
                scanMode = ScanMode.MIN_ACTIVATIONS,
                depthOverride = currentDepth,
                recursionDelayLevel = 0,
            )
            scanState.pendingRecursionContent.addAll(result.recursionContent)
            if (!result.changed && candidates.all { it.timedEffectKey in scanState.activated }) break
        }
    }

    private fun applyRecursiveScanning(
        candidates: List<CandidateEntry>,
        lorebooks: List<TaggedLorebook>,
        scanState: ActivationScanState,
    ) {
        if (lorebooks.none { it.book.recursiveScanning }) return

        val delayLevels = candidates
            .map { it.entry.delayUntilRecursion }
            .filter { it > 0 }
            .distinct()
            .sorted()
        val firstDelayLevel = delayLevels.firstOrNull() ?: 0
        val remainingDelayLevels = if (delayLevels.isEmpty()) emptyList() else delayLevels.drop(1)
        val maxSteps = lorebooks.maxOf { it.book.maxRecursionSteps }.let { if (it <= 0) 10 else it }
        var recursionStep = 0

        if (scanState.pendingRecursionContent.isNotEmpty()) {
            scanState.recursionBuffer.add(scanState.pendingRecursionContent.joinToString("\n"))
            scanState.pendingRecursionContent.clear()
        }

        while (recursionStep < maxSteps && scanState.recursionBuffer.isNotEmpty()) {
            recursionStep++
            val result = scanCandidates(
                candidates = candidates,
                scanState = scanState,
                scanMode = ScanMode.RECURSION,
                depthOverride = null,
                recursionDelayLevel = firstDelayLevel,
            )
            if (!result.changed) break
            if (result.recursionContent.isNotEmpty()) {
                scanState.recursionBuffer.add(result.recursionContent.joinToString("\n"))
            }
        }

        for (delayLevel in remainingDelayLevels) {
            val result = scanCandidates(
                candidates = candidates,
                scanState = scanState,
                scanMode = ScanMode.RECURSION,
                depthOverride = null,
                recursionDelayLevel = delayLevel,
            )
            if (result.recursionContent.isNotEmpty()) {
                scanState.recursionBuffer.add(result.recursionContent.joinToString("\n"))
            }
        }
    }

    private fun scanCandidates(
        candidates: List<CandidateEntry>,
        scanState: ActivationScanState,
        scanMode: ScanMode,
        depthOverride: Int?,
        recursionDelayLevel: Int,
    ): ScanPassResult {
        scanState.currentScanMode = scanMode
        var changed = false
        val recursionContent = mutableListOf<String>()
        for (candidate in candidates) {
            if (candidate.timedEffectKey in scanState.activated) continue
            val entry = candidate.entry

            if (!passesCharacterFilter(entry.characterFilter, scanState.scanContext.currentCharacterId)) continue

            val isSticky = candidate.timedEffectKey in scanState.activeTimedEffects.stickyKeys
            val isCooldown = candidate.timedEffectKey in scanState.activeTimedEffects.cooldownKeys
            val isDelay = entry.delay?.let { scanState.messageCount < it } == true

            if (isDelay) continue
            if (isCooldown && !isSticky) continue
            if (scanMode != ScanMode.RECURSION && entry.delayUntilRecursion > 0 && !isSticky) continue
            if (scanMode == ScanMode.RECURSION && entry.delayUntilRecursion > recursionDelayLevel && !isSticky) continue
            if (scanMode == ScanMode.RECURSION && entry.excludeRecursion && !isSticky) continue

            val score = when {
                entry.constant -> Int.MAX_VALUE
                isSticky -> Int.MAX_VALUE
                else -> matchEntry(candidate, scanState, depthOverride) ?: continue
            }

            if (!isSticky && entry.useProbability && entry.probability < 100) {
                if ((1..100).random() > entry.probability) continue
            }

            scanState.activated[candidate.timedEffectKey] = candidate
            scanState.scores[candidate] = score
            if (!entry.preventRecursion) {
                recursionContent.add(entry.content)
            }
            changed = true
        }
        return ScanPassResult(changed = changed, recursionContent = recursionContent)
    }

    private fun matchEntry(
        candidate: CandidateEntry,
        scanState: ActivationScanState,
        depthOverride: Int?,
    ): Int? {
        val entry = candidate.entry
        val book = candidate.book
        val scanDepth = depthOverride ?: (entry.entryScanDepth ?: book.scanDepth)
        val caseSensitive = entry.entryCaseSensitive ?: book.caseSensitive
        val matchWholeWords = entry.entryMatchWholeWords ?: book.matchWholeWords
        val shouldUseRecursionBuffer = scanState.recursionBuffer.isNotEmpty() && scanState.currentScanMode != ScanMode.MIN_ACTIVATIONS
        val baseTextToScan = buildScanText(
            messages = scanState.originalMessages,
            messageNames = scanState.originalMessageNames,
            scanDepth = scanDepth,
            includeNames = book.includeNames,
            entry = entry,
            scanContext = scanState.scanContext,
        )
        val textToScan = if (shouldUseRecursionBuffer) {
            listOf(baseTextToScan, scanState.recursionBuffer.joinToString("\n"))
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } else {
            baseTextToScan
        }
        if (textToScan.isBlank()) return null

        var score = 0
        val primaryMatched = entry.key.any { key ->
            if (key.isBlank()) return@any false
            val matched = matchKey(textToScan, key, caseSensitive, matchWholeWords)
            if (matched) score++
            matched
        }
        if (!primaryMatched) return null

        if (entry.selective && entry.keysecondary.isNotEmpty()) {
            val secondaryMatched = checkSecondaryKeys(
                text = textToScan,
                secondaryKeys = entry.keysecondary,
                logic = entry.selectiveLogic,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
            )
            if (!secondaryMatched) return null
            for (key in entry.keysecondary) {
                if (key.isNotBlank() && matchKey(textToScan, key, caseSensitive, matchWholeWords)) {
                    score++
                }
            }
        }

        return score
    }

    private fun resolveActiveTimedEffects(
        candidates: List<CandidateEntry>,
        currentState: LorebookTimedEffectState,
        messageCount: Int,
        isDryRun: Boolean,
    ): ActiveTimedEffects {
        if (isDryRun) {
            return ActiveTimedEffects(
                stickyKeys = emptySet(),
                cooldownKeys = emptySet(),
                nextState = currentState,
            )
        }

        val candidatesByKey = candidates.associateBy { it.timedEffectKey }
        val activeStickyKeys = mutableSetOf<String>()
        val activeCooldownKeys = mutableSetOf<String>()
        val nextSticky = mutableMapOf<String, LorebookTimedEffect>()
        val nextCooldown = mutableMapOf<String, LorebookTimedEffect>()

        for ((key, effect) in currentState.sticky) {
            val candidate = candidatesByKey[key]
            if (candidate == null) {
                if (messageCount < effect.endMessageCount) {
                    nextSticky[key] = effect
                }
                continue
            }
            if (shouldRemoveEffect(effect, candidate, messageCount, EffectType.STICKY)) continue
            if (messageCount >= effect.endMessageCount) {
                val cooldown = candidate.entry.cooldown
                if (cooldown != null) {
                    val cooldownEffect = createTimedEffect(candidate, EffectType.COOLDOWN, messageCount, protectedEffect = true)
                    nextCooldown[key] = cooldownEffect
                    activeCooldownKeys.add(key)
                }
                continue
            }
            nextSticky[key] = effect
            activeStickyKeys.add(key)
        }

        for ((key, effect) in currentState.cooldown) {
            if (key in nextCooldown) continue
            val candidate = candidatesByKey[key]
            if (candidate == null) {
                if (messageCount < effect.endMessageCount) {
                    nextCooldown[key] = effect
                }
                continue
            }
            if (shouldRemoveEffect(effect, candidate, messageCount, EffectType.COOLDOWN)) continue
            if (messageCount >= effect.endMessageCount) continue
            nextCooldown[key] = effect
            activeCooldownKeys.add(key)
        }

        return ActiveTimedEffects(
            stickyKeys = activeStickyKeys,
            cooldownKeys = activeCooldownKeys,
            nextState = LorebookTimedEffectState(sticky = nextSticky, cooldown = nextCooldown),
        )
    }

    private fun shouldRemoveEffect(
        effect: LorebookTimedEffect,
        candidate: CandidateEntry,
        messageCount: Int,
        effectType: EffectType,
    ): Boolean {
        if (messageCount <= effect.startMessageCount && !effect.protectedEffect) return true
        if (candidate.entryHash != effect.entryHash) return true
        return when (effectType) {
            EffectType.STICKY -> candidate.entry.sticky == null
            EffectType.COOLDOWN -> candidate.entry.cooldown == null
        }
    }

    private fun applyNewTimedEffects(
        activatedEntries: List<CandidateEntry>,
        currentState: LorebookTimedEffectState,
        messageCount: Int,
    ): LorebookTimedEffectState {
        val nextSticky = currentState.sticky.toMutableMap()
        val nextCooldown = currentState.cooldown.toMutableMap()
        for (candidate in activatedEntries) {
            if (candidate.entry.sticky != null && candidate.timedEffectKey !in nextSticky) {
                nextSticky[candidate.timedEffectKey] = createTimedEffect(candidate, EffectType.STICKY, messageCount, false)
            }
            if (candidate.entry.cooldown != null && candidate.timedEffectKey !in nextCooldown) {
                nextCooldown[candidate.timedEffectKey] = createTimedEffect(candidate, EffectType.COOLDOWN, messageCount, false)
            }
        }
        return LorebookTimedEffectState(sticky = nextSticky, cooldown = nextCooldown)
    }

    private fun createTimedEffect(
        candidate: CandidateEntry,
        effectType: EffectType,
        messageCount: Int,
        protectedEffect: Boolean,
    ): LorebookTimedEffect {
        val duration = when (effectType) {
            EffectType.STICKY -> candidate.entry.sticky
            EffectType.COOLDOWN -> candidate.entry.cooldown
        } ?: 0
        return LorebookTimedEffect(
            entryHash = candidate.entryHash,
            startMessageCount = messageCount,
            endMessageCount = messageCount + duration,
            protectedEffect = protectedEffect,
        )
    }

    private fun buildScanText(
        messages: List<String>,
        messageNames: List<String>,
        scanDepth: Int,
        includeNames: Boolean,
        entry: LorebookEntry,
        scanContext: ScanContext,
    ): String {
        val chatText = messages.take(scanDepth).mapIndexed { index, message ->
            val name = messageNames.getOrNull(index).orEmpty()
            if (includeNames && name.isNotBlank()) "$name: $message" else message
        }.joinToString("\n")

        val extraText = buildString {
            if (entry.matchPersonaDescription && scanContext.personaDescription.isNotBlank()) {
                append("\n").append(scanContext.personaDescription)
            }
            if (entry.matchCharacterDescription && scanContext.characterDescription.isNotBlank()) {
                append("\n").append(scanContext.characterDescription)
            }
            if (entry.matchCharacterPersonality && scanContext.characterPersonality.isNotBlank()) {
                append("\n").append(scanContext.characterPersonality)
            }
            if (entry.matchCharacterDepthPrompt && scanContext.characterDepthPrompt.isNotBlank()) {
                append("\n").append(scanContext.characterDepthPrompt)
            }
            if (entry.matchScenario && scanContext.scenario.isNotBlank()) {
                append("\n").append(scanContext.scenario)
            }
            if (entry.matchCreatorNotes && scanContext.creatorNotes.isNotBlank()) {
                append("\n").append(scanContext.creatorNotes)
            }
        }

        return chatText + extraText
    }

    private fun matchKey(
        text: String,
        key: String,
        caseSensitive: Boolean,
        matchWholeWords: Boolean,
    ): Boolean {
        val searchText = if (caseSensitive) text else text.lowercase()
        val searchKey = if (caseSensitive) key else key.lowercase()
        if (!matchWholeWords) return searchText.contains(searchKey)

        val pattern = try {
            Regex("\\b${Regex.escape(searchKey)}\\b", if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (_: Throwable) {
            return searchText.contains(searchKey)
        }
        return pattern.containsMatchIn(text)
    }

    private fun checkSecondaryKeys(
        text: String,
        secondaryKeys: List<String>,
        logic: Int,
        caseSensitive: Boolean,
        matchWholeWords: Boolean,
    ): Boolean {
        var hasAny = false
        var hasAll = true
        for (key in secondaryKeys) {
            if (key.isBlank()) continue
            val matched = matchKey(text, key, caseSensitive, matchWholeWords)
            if (matched) hasAny = true
            if (!matched) hasAll = false

            when (logic) {
                SelectiveLogic.AND_ANY -> if (matched) return true
                SelectiveLogic.NOT_ALL -> if (!matched) return true
            }
        }
        return when (logic) {
            SelectiveLogic.AND_ANY -> hasAny
            SelectiveLogic.NOT_ALL -> !hasAll
            SelectiveLogic.NOT_ANY -> !hasAny
            SelectiveLogic.AND_ALL -> hasAll
            else -> false
        }
    }

    private fun passesCharacterFilter(filter: CharacterFilter?, currentCharacterId: String?): Boolean {
        if (filter == null) return true
        if (currentCharacterId == null) return true
        if (filter.names.isEmpty()) return true

        val nameIncluded = filter.names.contains(currentCharacterId)
        return if (filter.isExclude) !nameIncluded else nameIncluded
    }

    private fun resolveGroups(
        candidates: List<CandidateEntry>,
        scores: Map<CandidateEntry, Int>,
        globalUseGroupScoring: Boolean,
        activeTimedEffects: ActiveTimedEffects,
    ): List<CandidateEntry> {
        val grouped = candidates.groupBy { it.entry.group }
        val result = mutableListOf<CandidateEntry>()
        for ((group, entries) in grouped) {
            if (group.isBlank()) {
                result.addAll(entries)
                continue
            }

            val stickyEntries = entries.filter { it.timedEffectKey in activeTimedEffects.stickyKeys }
            if (stickyEntries.isNotEmpty()) {
                result.addAll(stickyEntries)
                continue
            }

            val activeEntries = entries.filterNot { candidate ->
                candidate.timedEffectKey in activeTimedEffects.cooldownKeys
            }
            if (activeEntries.isEmpty()) continue

            val overrides = activeEntries.filter { it.entry.groupOverride }
            if (overrides.isNotEmpty()) {
                result.addAll(overrides)
                continue
            }

            val useScoring = globalUseGroupScoring || activeEntries.any { it.entry.entryUseGroupScoring == true }
            if (useScoring) {
                val maxScore = activeEntries.maxOfOrNull { scores[it] ?: 0 } ?: 0
                val topScored = activeEntries.filter { (scores[it] ?: 0) >= maxScore }
                topScored.maxByOrNull { it.entry.groupWeight }?.let { result.add(it) }
            } else {
                activeEntries.maxByOrNull { it.entry.groupWeight }?.let { result.add(it) }
            }
        }
        return result
    }

    private fun selectEntriesWithinBudget(
        sorted: List<CandidateEntry>,
        lorebooks: List<TaggedLorebook>,
        scanContext: ScanContext,
        wiFormat: String,
        processedContents: Map<Int, String>,
    ): BudgetSelection {
        val percentBudget = lorebooks.maxOf { book ->
            (book.book.tokenBudget * scanContext.maxContextTokens / 100).coerceAtLeast(1)
        }
        val budgetCap = lorebooks.maxOf { it.book.budgetCap }
        val effectiveBudget = if (budgetCap > 0) minOf(percentBudget, budgetCap) else percentBudget

        var usedTokens = 0
        var overflowed = false
        val withinBudget = mutableListOf<CandidateEntry>()
        for (candidate in sorted) {
            val processedContent = processedContents[candidate.entryHash] ?: candidate.entry.content
            // 对齐酒馆 world-info.js:5086-5088:WORLD_INFO 正则把非空 entry.content 替换成空 → skip。
            // 不在这里覆盖"entry.content 原本就空但 addMemo+comment 拼接出非空注入"的现有行为
            // (后者是 LorebookEngine 与酒馆原版的另一处偏差,见 docs/modules/lorebook.md 已知坑)。
            if (candidate.entry.content.isNotBlank() && processedContent.isBlank()) continue
            if (candidate.entry.ignoreBudget) {
                withinBudget.add(candidate)
                continue
            }
            val tokens = tokenCounter.countTokens(formatContent(candidate.entry, wiFormat, processedContent))
            if (usedTokens + tokens <= effectiveBudget) {
                usedTokens += tokens
                withinBudget.add(candidate)
            } else {
                overflowed = true
            }
        }
        return BudgetSelection(entries = withinBudget, overflowed = overflowed)
    }

    private fun buildResult(
        sorted: List<CandidateEntry>,
        wiFormat: String,
        overflowed: Boolean,
        nextTimedEffects: LorebookTimedEffectState,
        processedContents: Map<Int, String>,
    ): ActivationResult {
        val beforeEntries = mutableListOf<String>()
        val afterEntries = mutableListOf<String>()
        val depthMap = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
        val emTopEntries = mutableListOf<String>()
        val emBottomEntries = mutableListOf<String>()

        for (candidate in sorted) {
            val entry = candidate.entry
            val processedContent = processedContents[candidate.entryHash] ?: entry.content
            val content = formatContent(entry, wiFormat, processedContent)
            if (content.isBlank()) continue

            when (entry.position) {
                WiPosition.BEFORE -> beforeEntries.add(content)
                WiPosition.AFTER -> afterEntries.add(content)
                WiPosition.AT_DEPTH -> {
                    val key = entry.depth to entry.role
                    depthMap.getOrPut(key) { mutableListOf() }.add(content)
                }
                WiPosition.EM_TOP -> emTopEntries.add(content)
                WiPosition.EM_BOTTOM -> emBottomEntries.add(content)
                // AN_TOP / AN_BOTTOM 已在 buildCandidatesByStrategy 入口被过滤,这里不会出现;
                // 历史上曾被错误降级到 beforeEntries/afterEntries(污染 system 段),已删除。
                // when (Int) 不强制穷尽,无需补 else。
            }
        }

        val depthEntries = depthMap.map { (key, contents) ->
            DepthEntry(depth = key.first, role = key.second, content = contents.joinToString("\n"))
        }

        return ActivationResult(
            worldInfoBefore = beforeEntries.joinToString("\n"),
            worldInfoAfter = afterEntries.joinToString("\n"),
            depthEntries = depthEntries,
            exampleTop = emTopEntries.joinToString("\n"),
            exampleBottom = emBottomEntries.joinToString("\n"),
            activatedEntries = sorted.map { it.entry },
            budgetOverflowed = overflowed,
            nextTimedEffects = nextTimedEffects,
        )
    }

    /**
     * 拼接 entry 的最终注入文本。
     *
     * @param entryContent entry.content 正则后的内容(对齐酒馆 world-info.js:5086 只对 content 跑 WORLD_INFO 正则,
     *   不跑 comment / wiFormat)。无正则需求时传 entry.content 即可。
     */
    private fun formatContent(entry: LorebookEntry, wiFormat: String, entryContent: String): String {
        val raw = buildString {
            if (entry.addMemo && entry.comment.isNotBlank()) {
                append(entry.comment)
                append("\n")
            }
            append(entryContent)
        }.trim()

        if (raw.isBlank()) return ""
        if (wiFormat.isBlank() || wiFormat == "{0}") return raw
        return wiFormat.replace("{0}", raw)
    }

    private fun hashEntry(sourceKey: String, entry: LorebookEntry): Int {
        return "$sourceKey:${entryJson.encodeToString(entry)}".hashCode()
    }

    private data class CandidateEntry(
        val entry: LorebookEntry,
        val book: Lorebook,
        val sourceKey: String,
        val timedEffectKey: String,
        val entryHash: Int,
    )

    private data class ActiveTimedEffects(
        val stickyKeys: Set<String>,
        val cooldownKeys: Set<String>,
        val nextState: LorebookTimedEffectState,
    )

    private data class ActivationScanState(
        val originalMessages: List<String>,
        val originalMessageNames: List<String>,
        val scanContext: ScanContext,
        val activeTimedEffects: ActiveTimedEffects,
        val messageCount: Int,
        val activated: MutableMap<String, CandidateEntry> = linkedMapOf(),
        val recursionBuffer: MutableList<String> = mutableListOf(),
        val pendingRecursionContent: MutableList<String> = mutableListOf(),
        val scores: MutableMap<CandidateEntry, Int> = mutableMapOf(),
        var currentScanMode: ScanMode = ScanMode.NORMAL,
    )

    private data class BudgetSelection(
        val entries: List<CandidateEntry>,
        val overflowed: Boolean,
    )

    private data class ScanPassResult(
        val changed: Boolean,
        val recursionContent: List<String>,
    )

    private enum class EffectType {
        STICKY,
        COOLDOWN,
    }

    private enum class ScanMode {
        NORMAL,
        MIN_ACTIVATIONS,
        RECURSION,
    }

    private companion object {
        val entryJson = Json { encodeDefaults = true }
    }
}

data class TaggedLorebook(
    val book: Lorebook,
    val isCharacterSource: Boolean,
    val sourceKey: String = book.id,
)
