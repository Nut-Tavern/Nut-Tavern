package com.nuttavern.lorebook

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.SelectiveLogic
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书激活引擎。对齐酒馆 `checkWorldInfo`(world-info.js:4500-5070)。
 *
 * 职责:
 * 1. 接收聊天历史 + 全局/角色绑定/角色内嵌世界书
 * 2. 按关键词匹配 + 激活逻辑筛选条目
 * 3. 按 token 预算裁剪
 * 4. 递归扫描
 * 5. 按注入位置分桶返回结果
 *
 * 当前不实现:向量搜索(vectorized)、角色过滤器(characterFilter)、时效效果(sticky/cooldown/delay)、
 * outlet 位置、triggers 过滤。这些字段存盘但引擎不消费。
 */
@Singleton
class LorebookEngine @Inject constructor() {

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
    )

    data class DepthEntry(
        val depth: Int,
        val role: Int,
        val content: String,
    )

    /**
     * 执行世界书激活扫描。
     *
     * @param messages 聊天历史,index 0 = 最新消息(倒序)
     * @param lorebooks 参与激活的所有世界书(全局选中 + 角色绑定 + 角色内嵌)
     * @param wiFormat 预设的世界书格式模板(如 `[{0}]`),{0} 替换为条目内容
     */
    fun activate(
        messages: List<String>,
        lorebooks: List<Lorebook>,
        wiFormat: String = "",
    ): ActivationResult {
        if (lorebooks.isEmpty()) return ActivationResult()

        // 合并所有条目,保留来源书的全局设置用于条目级覆盖
        val allCandidates = lorebooks.flatMap { book ->
            book.entries
                .filter { !it.disable }
                .map { CandidateEntry(it, book) }
        }
        if (allCandidates.isEmpty()) return ActivationResult()

        // 第一轮扫描
        val activated = mutableSetOf<CandidateEntry>()
        val activatedContent = mutableListOf<String>()
        scanAndActivate(messages, allCandidates, activated, activatedContent)

        // 递归扫描
        val recursiveBooks = lorebooks.filter { it.recursiveScanning }
        if (recursiveBooks.isNotEmpty()) {
            var recursionStep = 0
            val maxSteps = lorebooks.maxOf { it.maxRecursionSteps }.let { if (it <= 0) 10 else it }
            var newActivations = true
            while (newActivations && recursionStep < maxSteps) {
                recursionStep++
                val beforeSize = activated.size
                val recursiveCandidates = allCandidates.filter { candidate ->
                    candidate !in activated && !candidate.entry.excludeRecursion
                }
                // 把已激活内容加入扫描缓冲
                val extendedMessages = activatedContent + messages
                scanAndActivate(extendedMessages, recursiveCandidates, activated, activatedContent)
                newActivations = activated.size > beforeSize
            }
        }

        // 互斥组处理
        val afterGrouping = resolveGroups(activated.toList())

        // 按 order 从高到低排序
        val sorted = afterGrouping.sortedByDescending { it.entry.order }

        // 构建结果(暂不做 token 预算裁剪,后续接 tokenizer 再加)
        return buildResult(sorted, wiFormat)
    }

    private fun scanAndActivate(
        messages: List<String>,
        candidates: List<CandidateEntry>,
        activated: MutableSet<CandidateEntry>,
        activatedContent: MutableList<String>,
    ) {
        for (candidate in candidates) {
            if (candidate in activated) continue
            val entry = candidate.entry
            val book = candidate.book

            val scanDepth = entry.entryScanDepth ?: book.scanDepth
            val caseSensitive = entry.entryCaseSensitive ?: book.caseSensitive
            val matchWholeWords = entry.entryMatchWholeWords ?: book.matchWholeWords

            // 常驻条目直接激活
            if (entry.constant) {
                activated.add(candidate)
                if (!entry.preventRecursion) activatedContent.add(entry.content)
                continue
            }

            // 构建扫描文本
            val textToScan = messages.take(scanDepth).joinToString("\n")
            if (textToScan.isBlank()) continue

            // 主关键词匹配
            val primaryMatch = entry.key.any { key ->
                key.isNotBlank() && matchKey(textToScan, key, caseSensitive, matchWholeWords)
            }
            if (!primaryMatch) continue

            // 次要关键词逻辑
            if (entry.keysecondary.isNotEmpty()) {
                val secondaryResult = checkSecondaryKeys(
                    textToScan, entry.keysecondary, entry.selectiveLogic, caseSensitive, matchWholeWords,
                )
                if (!secondaryResult) continue
            }

            // 概率判断
            if (entry.useProbability && entry.probability < 100) {
                if ((1..100).random() > entry.probability) continue
            }

            activated.add(candidate)
            if (!entry.preventRecursion) activatedContent.add(entry.content)
        }
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

    private fun resolveGroups(candidates: List<CandidateEntry>): List<CandidateEntry> {
        val grouped = candidates.groupBy { it.entry.group }
        val result = mutableListOf<CandidateEntry>()
        for ((group, entries) in grouped) {
            if (group.isBlank()) {
                // 无组的全部保留
                result.addAll(entries)
            } else {
                // 同组内:groupOverride 的强制保留,否则只保留 groupWeight 最高的
                val overrides = entries.filter { it.entry.groupOverride }
                if (overrides.isNotEmpty()) {
                    result.addAll(overrides)
                } else {
                    entries.maxByOrNull { it.entry.groupWeight }?.let { result.add(it) }
                }
            }
        }
        return result
    }

    private fun buildResult(sorted: List<CandidateEntry>, wiFormat: String): ActivationResult {
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
