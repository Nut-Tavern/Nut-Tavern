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

/**
 * 世界书激活引擎。对齐酒馆 `checkWorldInfo`(world-info.js:4500-5070)。
 *
 * 职责:
 * 1. 接收聊天历史 + 全局/角色绑定/角色内嵌世界书
 * 2. 按 characterStrategy 合并排序条目来源
 * 3. characterFilter 硬过滤(按当前角色)
 * 4. 按关键词匹配 + match* 扩展扫描范围 + 激活逻辑筛选条目
 * 5. minActivations 扩展深度
 * 6. 互斥组处理(含 useGroupScoring 评分模式)
 * 7. Token 预算裁剪(budgetCap)
 * 8. 递归扫描
 * 9. 按注入位置分桶返回结果
 *
 * 当前不消费:vectorized(向量搜索)、automationId(slash command)、
 * characterFilter.tags(无 tag 系统)、sticky/cooldown/delay(时效效果)、
 * triggers(生成类型触发器)。这些字段存盘但引擎跳过。
 */
@Singleton
class LorebookEngine @Inject constructor(
    private val tokenCounter: TokenCounter,
) {

    /**
     * 激活结果。按注入位置分桶。
     */
    data class ActivationResult(
        /** position=before 的条目内容,已按 order 排序拼接 */
        val worldInfoBefore: String = "",
        /** position=after 的条目内容 */
        val worldInfoAfter: String = "",
        /** position=atDepth 的条目,按 (depth, role) 分组 */
        val depthEntries: List<DepthEntry> = emptyList(),
        /** position=EMTop 的条目内容 */
        val exampleTop: String = "",
        /** position=EMBottom 的条目内容 */
        val exampleBottom: String = "",
        /** 所有被激活的条目(用于调试/日志) */
        val activatedEntries: List<LorebookEntry> = emptyList(),
        /** 是否发生预算溢出(有条目匹配但因预算不足未注入) */
        val budgetOverflowed: Boolean = false,
    )

    data class DepthEntry(
        val depth: Int,
        val role: Int,
        val content: String,
    )

    /**
     * 全局扫描上下文。由调用方(ChatViewModel)组装传入。
     */
    data class ScanContext(
        /** 当前对话角色的 id(用于 characterFilter 过滤) */
        val currentCharacterId: String? = null,
        /** 用户身份描述(matchPersonaDescription 时追加到扫描文本) */
        val personaDescription: String = "",
        /** 角色 description 字段 */
        val characterDescription: String = "",
        /** 角色 personality 字段 */
        val characterPersonality: String = "",
        /** 角色 depth prompt */
        val characterDepthPrompt: String = "",
        /** 角色 scenario 字段 */
        val scenario: String = "",
        /** 角色 creator_notes 字段 */
        val creatorNotes: String = "",
        /** 预设的上下文窗口大小(用于计算百分比预算) */
        val maxContextTokens: Int = 4096,
    )

    /**
     * 执行世界书激活扫描。
     *
     * @param messages 聊天历史文本,index 0 = 最新消息(倒序)
     * @param messageNames 每条消息的发言者名称(与 messages 一一对应)
     * @param lorebooks 参与激活的所有世界书,按来源分:
     *   - isCharacterSource=true 的是角色来源(角色内嵌 + 角色绑定)
     *   - isCharacterSource=false 的是全局来源
     * @param wiFormat 预设的世界书格式模板(如 `[{0}]`),{0} 替换为条目内容
     * @param scanContext 全局扫描上下文
     */
    fun activate(
        messages: List<String>,
        messageNames: List<String> = emptyList(),
        lorebooks: List<TaggedLorebook>,
        wiFormat: String = "",
        scanContext: ScanContext = ScanContext(),
    ): ActivationResult {
        if (lorebooks.isEmpty()) return ActivationResult()

        // 按 characterStrategy 决定条目合并顺序
        val strategy = lorebooks.firstOrNull()?.book?.characterStrategy ?: WiCharacterStrategy.CHARACTER_FIRST
        val allCandidates = buildCandidatesByStrategy(lorebooks, strategy)
        if (allCandidates.isEmpty()) return ActivationResult()

        // 第一轮扫描
        val activated = mutableSetOf<CandidateEntry>()
        val activatedContent = mutableListOf<String>()
        val scores = mutableMapOf<CandidateEntry, Int>()
        val baseScanDepth = lorebooks.maxOf { it.book.scanDepth }

        scanAndActivate(
            messages = messages,
            messageNames = messageNames,
            candidates = allCandidates,
            activated = activated,
            activatedContent = activatedContent,
            scores = scores,
            scanContext = scanContext,
            depthOverride = null,
        )

        // minActivations 扩展深度
        val minActivations = lorebooks.maxOf { it.book.minActivations }
        val minActivationsDepthMax = lorebooks.maxOf { it.book.minActivationsDepthMax }
        if (minActivations > 0 && activated.size < minActivations) {
            var currentDepth = baseScanDepth
            while (activated.size < minActivations && currentDepth < messages.size) {
                if (minActivationsDepthMax > 0 && currentDepth >= minActivationsDepthMax) break
                currentDepth++
                val remaining = allCandidates.filter { it !in activated }
                if (remaining.isEmpty()) break
                scanAndActivate(
                    messages = messages,
                    messageNames = messageNames,
                    candidates = remaining,
                    activated = activated,
                    activatedContent = activatedContent,
                    scores = scores,
                    scanContext = scanContext,
                    depthOverride = currentDepth,
                )
            }
        }

        // 递归扫描
        val recursiveBooks = lorebooks.filter { it.book.recursiveScanning }
        if (recursiveBooks.isNotEmpty()) {
            var recursionStep = 0
            val maxSteps = lorebooks.maxOf { it.book.maxRecursionSteps }.let { if (it <= 0) 10 else it }
            var newActivations = true
            while (newActivations && recursionStep < maxSteps) {
                recursionStep++
                val beforeSize = activated.size
                val recursiveCandidates = allCandidates.filter { candidate ->
                    candidate !in activated && !candidate.entry.excludeRecursion
                }
                if (recursiveCandidates.isEmpty()) break
                // 把已激活内容加入扫描缓冲
                val extendedMessages = activatedContent + messages
                val extendedNames = List(activatedContent.size) { "" } + messageNames
                scanAndActivate(
                    messages = extendedMessages,
                    messageNames = extendedNames,
                    candidates = recursiveCandidates,
                    activated = activated,
                    activatedContent = activatedContent,
                    scores = scores,
                    scanContext = scanContext,
                    depthOverride = null,
                )
                newActivations = activated.size > beforeSize
            }
        }

        // 互斥组处理
        val globalUseGroupScoring = lorebooks.any { it.book.useGroupScoring }
        val afterGrouping = resolveGroups(activated.toList(), scores, globalUseGroupScoring)

        // 按 order 从高到低排序
        val sorted = afterGrouping.sortedByDescending { it.entry.order }

        // Token 预算裁剪
        val percentBudget = lorebooks.maxOf { book ->
            (book.book.tokenBudget * scanContext.maxContextTokens / 100).coerceAtLeast(1)
        }
        val budgetCap = lorebooks.maxOf { it.book.budgetCap }
        val effectiveBudget = if (budgetCap > 0) minOf(percentBudget, budgetCap) else percentBudget

        var usedTokens = 0
        var overflowed = false
        val withinBudget = mutableListOf<CandidateEntry>()
        for (candidate in sorted) {
            if (candidate.entry.ignoreBudget) {
                withinBudget.add(candidate)
                continue
            }
            val content = formatContent(candidate.entry, wiFormat)
            val tokens = tokenCounter.countTokens(content)
            if (usedTokens + tokens <= effectiveBudget) {
                usedTokens += tokens
                withinBudget.add(candidate)
            } else {
                overflowed = true
            }
        }

        return buildResult(withinBudget, wiFormat, overflowed)
    }

    /**
     * 按 characterStrategy 合并条目来源。
     */
    private fun buildCandidatesByStrategy(
        lorebooks: List<TaggedLorebook>,
        strategy: Int,
    ): List<CandidateEntry> {
        val characterCandidates = lorebooks
            .filter { it.isCharacterSource }
            .flatMap { tagged -> tagged.book.entries.filter { !it.disable }.map { CandidateEntry(it, tagged.book) } }

        val globalCandidates = lorebooks
            .filter { !it.isCharacterSource }
            .flatMap { tagged -> tagged.book.entries.filter { !it.disable }.map { CandidateEntry(it, tagged.book) } }

        return when (strategy) {
            WiCharacterStrategy.EVENLY -> {
                (globalCandidates + characterCandidates).sortedByDescending { it.entry.order }
            }
            WiCharacterStrategy.CHARACTER_FIRST -> {
                characterCandidates.sortedByDescending { it.entry.order } +
                    globalCandidates.sortedByDescending { it.entry.order }
            }
            WiCharacterStrategy.GLOBAL_FIRST -> {
                globalCandidates.sortedByDescending { it.entry.order } +
                    characterCandidates.sortedByDescending { it.entry.order }
            }
            else -> (characterCandidates + globalCandidates)
        }
    }

    private fun scanAndActivate(
        messages: List<String>,
        messageNames: List<String>,
        candidates: List<CandidateEntry>,
        activated: MutableSet<CandidateEntry>,
        activatedContent: MutableList<String>,
        scores: MutableMap<CandidateEntry, Int>,
        scanContext: ScanContext,
        depthOverride: Int?,
    ) {
        for (candidate in candidates) {
            if (candidate in activated) continue
            val entry = candidate.entry
            val book = candidate.book

            // characterFilter 硬过滤
            if (!passesCharacterFilter(entry.characterFilter, scanContext.currentCharacterId)) continue

            val scanDepth = depthOverride ?: (entry.entryScanDepth ?: book.scanDepth)
            val caseSensitive = entry.entryCaseSensitive ?: book.caseSensitive
            val matchWholeWords = entry.entryMatchWholeWords ?: book.matchWholeWords
            val includeNames = book.includeNames

            // 常驻条目直接激活
            if (entry.constant) {
                activated.add(candidate)
                scores[candidate] = Int.MAX_VALUE
                if (!entry.preventRecursion) activatedContent.add(entry.content)
                continue
            }

            // 构建扫描文本
            val textToScan = buildScanText(messages, messageNames, scanDepth, includeNames, entry, scanContext)
            if (textToScan.isBlank()) continue

            // 主关键词匹配 + 计分
            var score = 0
            val primaryMatch = entry.key.any { key ->
                if (key.isBlank()) return@any false
                val matched = matchKey(textToScan, key, caseSensitive, matchWholeWords)
                if (matched) score++
                matched
            }
            if (!primaryMatch) continue

            // 次要关键词逻辑
            if (entry.selective && entry.keysecondary.isNotEmpty()) {
                val secondaryResult = checkSecondaryKeys(
                    textToScan, entry.keysecondary, entry.selectiveLogic, caseSensitive, matchWholeWords,
                )
                if (!secondaryResult) continue
                // 次要关键词命中也计入 score
                for (key in entry.keysecondary) {
                    if (key.isNotBlank() && matchKey(textToScan, key, caseSensitive, matchWholeWords)) {
                        score++
                    }
                }
            }

            // 概率判断
            if (entry.useProbability && entry.probability < 100) {
                if ((1..100).random() > entry.probability) continue
            }

            activated.add(candidate)
            scores[candidate] = score
            if (!entry.preventRecursion) activatedContent.add(entry.content)
        }
    }

    /**
     * 构建扫描文本:聊天消息 + match* 扩展文本。
     */
    private fun buildScanText(
        messages: List<String>,
        messageNames: List<String>,
        scanDepth: Int,
        includeNames: Boolean,
        entry: LorebookEntry,
        scanContext: ScanContext,
    ): String {
        val chatText = messages.take(scanDepth).mapIndexed { i, msg ->
            val name = messageNames.getOrNull(i).orEmpty()
            if (includeNames && name.isNotBlank()) "$name: $msg" else msg
        }.joinToString("\n")

        val extra = buildString {
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

        return chatText + extra
    }

    private fun matchKey(
        text: String,
        key: String,
        caseSensitive: Boolean,
        matchWholeWords: Boolean,
    ): Boolean {
        val searchText = if (caseSensitive) text else text.lowercase()
        val searchKey = if (caseSensitive) key else key.lowercase()

        if (!matchWholeWords) {
            return searchText.contains(searchKey)
        }

        // 整词匹配:用正则 \b 边界
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

            // 短路优化
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

    /**
     * characterFilter 过滤。返回 true 表示通过(不被过滤)。
     */
    private fun passesCharacterFilter(filter: CharacterFilter?, currentCharacterId: String?): Boolean {
        if (filter == null) return true
        if (currentCharacterId == null) return true
        if (filter.names.isEmpty()) return true

        val nameIncluded = filter.names.contains(currentCharacterId)
        return if (filter.isExclude) !nameIncluded else nameIncluded
    }

    /**
     * 互斥组处理。支持 useGroupScoring 评分模式。
     */
    private fun resolveGroups(
        candidates: List<CandidateEntry>,
        scores: Map<CandidateEntry, Int>,
        globalUseGroupScoring: Boolean,
    ): List<CandidateEntry> {
        val grouped = candidates.groupBy { it.entry.group }
        val result = mutableListOf<CandidateEntry>()
        for ((group, entries) in grouped) {
            if (group.isBlank()) {
                result.addAll(entries)
                continue
            }

            // groupOverride 的强制保留
            val overrides = entries.filter { it.entry.groupOverride }
            if (overrides.isNotEmpty()) {
                result.addAll(overrides)
                continue
            }

            // 评分模式:按关键词命中数筛选
            val useScoring = globalUseGroupScoring || entries.any { it.entry.entryUseGroupScoring == true }
            if (useScoring) {
                val maxScore = entries.maxOfOrNull { scores[it] ?: 0 } ?: 0
                val topScored = entries.filter { (scores[it] ?: 0) >= maxScore }
                if (topScored.size == 1) {
                    result.add(topScored.first())
                } else {
                    // 同分时按 groupWeight 选最高
                    topScored.maxByOrNull { it.entry.groupWeight }?.let { result.add(it) }
                }
            } else {
                // 默认模式:按 groupWeight 选最高
                entries.maxByOrNull { it.entry.groupWeight }?.let { result.add(it) }
            }
        }
        return result
    }

    private fun buildResult(
        sorted: List<CandidateEntry>,
        wiFormat: String,
        overflowed: Boolean,
    ): ActivationResult {
        val beforeEntries = mutableListOf<String>()
        val afterEntries = mutableListOf<String>()
        val depthMap = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
        val emTopEntries = mutableListOf<String>()
        val emBottomEntries = mutableListOf<String>()

        for (candidate in sorted) {
            val entry = candidate.entry
            val content = formatContent(entry, wiFormat)
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
                // AN_TOP / AN_BOTTOM 暂时归到 before/after(无 AN 模块)
                WiPosition.AN_TOP -> beforeEntries.add(content)
                WiPosition.AN_BOTTOM -> afterEntries.add(content)
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
        )
    }

    private fun formatContent(entry: LorebookEntry, wiFormat: String): String {
        val raw = buildString {
            if (entry.addMemo && entry.comment.isNotBlank()) {
                append(entry.comment)
                append("\n")
            }
            append(entry.content)
        }.trim()

        if (raw.isBlank()) return ""
        if (wiFormat.isBlank() || wiFormat == "{0}") return raw
        return wiFormat.replace("{0}", raw)
    }

    private data class CandidateEntry(
        val entry: LorebookEntry,
        val book: Lorebook,
    )
}

/**
 * 带来源标记的世界书。用于区分角色来源和全局来源,供 characterStrategy 排序使用。
 */
data class TaggedLorebook(
    val book: Lorebook,
    /** true = 角色来源(角色内嵌 / 角色绑定),false = 全局来源 */
    val isCharacterSource: Boolean,
)
