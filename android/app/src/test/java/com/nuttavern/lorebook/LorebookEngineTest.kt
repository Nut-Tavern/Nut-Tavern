package com.nuttavern.lorebook

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.prompt.TokenCounter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookEngineTest {

    private val engine = LorebookEngine(TokenCounter())
    private val json = Json { encodeDefaults = true }

    @Test
    fun delaySuppressesEntryBeforeMessageCountReachesDelay() {
        val entry = entry(content = "delayed", key = listOf("key"), delay = 3)
        val early = activate(entry, messages = listOf("key"), messageCount = 2)
        val ready = activate(entry, messages = listOf("key"), messageCount = 3)

        assertEquals(emptyList<LorebookEntry>(), early.activatedEntries)
        assertEquals(listOf(entry), ready.activatedEntries)
    }

    @Test
    fun stickyKeepsEntryActiveWithoutKeyword() {
        val entry = entry(content = "sticky", key = listOf("key"), sticky = 2)
        val first = activate(entry, messages = listOf("key"), messageCount = 3)
        val second = activate(
            entry = entry,
            messages = listOf("no match"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(listOf(entry), first.activatedEntries)
        assertEquals(listOf(entry), second.activatedEntries)
    }

    @Test
    fun stickyDoesNotRerollProbability() {
        val entry = entry(content = "sticky", key = listOf("key"), sticky = 2, probability = 0)
        val initialState = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(entry, messageCount = 3, endMessageCount = 5)),
        )
        val result = activate(entry, messages = listOf("no match"), messageCount = 4, state = initialState)

        assertEquals(listOf(entry), result.activatedEntries)
    }

    @Test
    fun cooldownSuppressesKeywordMatch() {
        val entry = entry(content = "cooldown", key = listOf("key"), cooldown = 2)
        val first = activate(entry, messages = listOf("key"), messageCount = 3)
        val second = activate(
            entry = entry,
            messages = listOf("key again"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(listOf(entry), first.activatedEntries)
        assertEquals(emptyList<LorebookEntry>(), second.activatedEntries)
    }

    @Test
    fun cooldownStartsWhenStickyEnds() {
        val entry = entry(content = "timed", key = listOf("key"), sticky = 1, cooldown = 2)
        val first = activate(entry, messages = listOf("key"), messageCount = 3)
        val afterSticky = activate(
            entry = entry,
            messages = listOf("key"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(emptyList<LorebookEntry>(), afterSticky.activatedEntries)
        assertFalse(afterSticky.nextTimedEffects.sticky.containsKey("book.1"))
        assertTrue(afterSticky.nextTimedEffects.cooldown.containsKey("book.1"))
    }

    @Test
    fun stickyWinsWithinGroup() {
        val stickyEntry = entry(uid = 1, content = "sticky", key = listOf("sticky"), group = "same", sticky = 2)
        val otherEntry = entry(uid = 2, content = "other", key = listOf("other"), group = "same", groupWeight = 999)
        val first = activate(listOf(stickyEntry), messages = listOf("sticky"), messageCount = 3)
        val second = activate(
            entries = listOf(stickyEntry, otherEntry),
            messages = listOf("other"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(listOf(stickyEntry), second.activatedEntries)
    }

    @Test
    fun delayUntilRecursionActivatesOnlyAtConfiguredLevel() {
        val first = entry(uid = 1, content = "alpha", key = listOf("alpha"))
        val second = entry(uid = 2, content = "beta", key = listOf("alpha"), delayUntilRecursion = 1)
        val third = entry(uid = 3, content = "gamma", key = listOf("beta"), delayUntilRecursion = 2)

        val result = activate(
            entries = listOf(first, second, third),
            messages = listOf("alpha"),
            recursiveScanning = true,
            messageCount = 3,
        )

        assertEquals(listOf(first, second, third), result.activatedEntries)
    }

    @Test
    fun recursionDoesNotStartWithoutSuccessfulActivation() {
        val delayed = entry(uid = 1, content = "delayed", key = listOf("alpha"), delayUntilRecursion = 1)

        val result = activate(
            entries = listOf(delayed),
            messages = listOf("alpha"),
            recursiveScanning = true,
            messageCount = 3,
        )

        assertEquals(emptyList<LorebookEntry>(), result.activatedEntries)
    }

    @Test
    fun sameScanPassDoesNotTriggerAnotherEntryByNewContent() {
        val first = entry(uid = 1, content = "beta", key = listOf("alpha"))
        val second = entry(uid = 2, content = "gamma", key = listOf("beta"))

        val result = activate(
            entries = listOf(first, second),
            messages = listOf("alpha"),
            recursiveScanning = false,
            messageCount = 3,
        )

        assertEquals(listOf(first), result.activatedEntries)
    }

    @Test
    fun messageCountRollbackRemovesUnprotectedEffect() {
        val entry = entry(content = "sticky", key = listOf("key"), sticky = 2)
        val state = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(entry, messageCount = 3, endMessageCount = 5)),
        )
        val result = activate(entry, messages = listOf("no match"), messageCount = 3, state = state)

        assertEquals(emptyList<LorebookEntry>(), result.activatedEntries)
        assertEquals(emptyMap<String, LorebookTimedEffect>(), result.nextTimedEffects.sticky)
    }

    @Test
    fun missingEntryStateIsKeptUntilIntervalPasses() {
        val missing = entry(content = "missing", key = listOf("key"), sticky = 3)
        val other = entry(uid = 2, content = "other", key = listOf("other"))
        val state = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(missing, messageCount = 3, endMessageCount = 6)),
        )

        val result = activate(other, messages = listOf("other"), messageCount = 4, state = state)

        assertTrue(result.nextTimedEffects.sticky.containsKey("book.1"))
    }

    @Test
    fun hashMismatchRemovesOldEffect() {
        val original = entry(content = "old", key = listOf("key"), sticky = 2)
        val changed = entry(content = "new", key = listOf("key"), sticky = 2)
        val state = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(original, messageCount = 3, endMessageCount = 5)),
        )
        val result = activate(changed, messages = listOf("no match"), messageCount = 4, state = state)

        assertEquals(emptyList<LorebookEntry>(), result.activatedEntries)
        assertEquals(emptyMap<String, LorebookTimedEffect>(), result.nextTimedEffects.sticky)
    }

    private fun activate(
        entry: LorebookEntry,
        messages: List<String>,
        messageCount: Int,
        state: LorebookTimedEffectState = LorebookTimedEffectState.Empty,
    ): LorebookEngine.ActivationResult {
        return activate(listOf(entry), messages, messageCount = messageCount, state = state)
    }

    private fun activate(
        entries: List<LorebookEntry>,
        messages: List<String>,
        recursiveScanning: Boolean = false,
        messageCount: Int,
        state: LorebookTimedEffectState = LorebookTimedEffectState.Empty,
    ): LorebookEngine.ActivationResult {
        return engine.activate(
            messages = messages,
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(
                        id = "book",
                        name = "book",
                        recursiveScanning = recursiveScanning,
                        entries = entries,
                    ),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = messageCount,
            timedEffects = state,
        )
    }

    private fun entry(
        uid: Int = 1,
        content: String,
        key: List<String>,
        group: String = "",
        groupWeight: Int = LorebookEntry.DEFAULT_WEIGHT,
        sticky: Int? = null,
        cooldown: Int? = null,
        delay: Int? = null,
        probability: Int = 100,
        delayUntilRecursion: Int = 0,
    ): LorebookEntry {
        return LorebookEntry(
            uid = uid,
            key = key,
            content = content,
            group = group,
            groupWeight = groupWeight,
            sticky = sticky,
            cooldown = cooldown,
            delay = delay,
            probability = probability,
            delayUntilRecursion = delayUntilRecursion,
        )
    }

    private fun timedEffect(
        entry: LorebookEntry,
        messageCount: Int,
        endMessageCount: Int,
    ): LorebookTimedEffect {
        return LorebookTimedEffect(
            entryHash = "book:${json.encodeToString(entry)}".hashCode(),
            startMessageCount = messageCount,
            endMessageCount = endMessageCount,
        )
    }

    // ─── contentRegexHook(WORLD_INFO 阶段正则)对齐酒馆 world-info.js:5086 ─────────

    @Test
    fun contentRegexHookDefaultIsIdentity() {
        // 不传 hook 时,行为与未引入正则一致(回归保护)。
        val entry = entry(content = "alpha", key = listOf("alpha"))
        val result = activate(entry, messages = listOf("alpha"), messageCount = 1)
        assertEquals("alpha", result.worldInfoBefore)
    }

    @Test
    fun contentRegexHookReplacesEntryContent() {
        val entry = entry(content = "alpha", key = listOf("alpha"))
        val result = engine.activate(
            messages = listOf("alpha"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(entry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            contentRegexHook = { raw, _ -> raw.replace("alpha", "BETA") },
        )
        assertEquals("BETA", result.worldInfoBefore)
        // entry.content 仍是原值,正则只影响注入文本(对齐酒馆:不修改 entry 数据)。
        assertEquals(listOf(entry), result.activatedEntries)
    }

    @Test
    fun contentRegexHookEmptyReturnSkipsEntry() {
        // 对齐酒馆 world-info.js:5088:正则把 entry.content 替换成空 → skip(不进 worldInfoBefore)。
        val entry = entry(content = "secret", key = listOf("secret"))
        val result = engine.activate(
            messages = listOf("secret"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(entry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            contentRegexHook = { _, _ -> "" },
        )
        assertEquals("", result.worldInfoBefore)
    }

    @Test
    fun contentRegexHookOnlyTouchesEntryContentNotComment() {
        // 对齐酒馆 world-info.js:5086:正则只跑 entry.content,不跑 comment。
        val entry = LorebookEntry(
            uid = 1,
            key = listOf("alpha"),
            content = "alpha",
            comment = "alpha-memo",
            addMemo = true,
        )
        val result = engine.activate(
            messages = listOf("alpha"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(entry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            contentRegexHook = { raw, _ -> raw.replace("alpha", "BETA") },
        )
        // comment 保留 "alpha-memo"(未跑正则);content 替换为 "BETA"。
        assertTrue(
            "expected comment unchanged + content replaced, got: ${result.worldInfoBefore}",
            result.worldInfoBefore.contains("alpha-memo") && result.worldInfoBefore.contains("BETA"),
        )
        assertFalse(
            "comment 不应被正则改动",
            result.worldInfoBefore.contains("BETA-memo"),
        )
    }

    @Test
    fun contentRegexHookExceptionFallsBackToRawContent() {
        // hook 抛异常时,LorebookEngine 应兜原文继续,保证单 entry 异常不打断整次激活。
        val entry = entry(content = "raw content", key = listOf("raw"))
        val result = engine.activate(
            messages = listOf("raw"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(entry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            contentRegexHook = { _, _ -> throw IllegalStateException("hook failure") },
        )
        assertEquals("raw content", result.worldInfoBefore)
    }

    @Test
    fun contentRegexHookReceivesRegexDepthOnlyForAtDepthEntries() {
        // 对齐酒馆 world-info.js:5085:regexDepth 仅在 position == AT_DEPTH 时取 entry.depth,其余位置传 null。
        val capturedDepths = mutableListOf<Pair<String, Int?>>()
        val beforeEntry = LorebookEntry(
            uid = 1,
            key = listOf("before"),
            content = "before-content",
            position = WiPosition.BEFORE,
            depth = 99, // AT_DEPTH 之外的 position 设了 depth 也应被忽略。
        )
        val atDepthEntry = LorebookEntry(
            uid = 2,
            key = listOf("atdepth"),
            content = "atdepth-content",
            position = WiPosition.AT_DEPTH,
            depth = 7,
        )
        engine.activate(
            messages = listOf("before atdepth"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(beforeEntry, atDepthEntry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            contentRegexHook = { content, regexDepth ->
                capturedDepths += content to regexDepth
                content
            },
        )
        // 锁住 hook 在每个 entry 上仅被调用一次,避免预跑/递归扫描误重复调。
        assertEquals(2, capturedDepths.size)
        // 按 entry 内容精确锁定 regexDepth 因果对应:
        // BEFORE 位置 → null(即使 entry.depth=99 也忽略);AT_DEPTH 位置 → entry.depth=7。
        assertEquals(null, capturedDepths.first { it.first == "before-content" }.second)
        assertEquals(7, capturedDepths.first { it.first == "atdepth-content" }.second)
    }

    @Test
    fun contentRegexHookPassesZeroDepthForAtDepthEntry() {
        // entry.depth=0 是合法值(对齐酒馆 world_info_position.atDepth 允许 depth=0 → 直接插当前消息层)。
        // 不应被等价为 null 或被 ?? DEFAULT_DEPTH 兜成 4。
        val capturedDepths = mutableListOf<Int?>()
        val entry = LorebookEntry(
            uid = 1,
            key = listOf("zero"),
            content = "zero-depth",
            position = WiPosition.AT_DEPTH,
            depth = 0,
        )
        engine.activate(
            messages = listOf("zero"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(entry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            contentRegexHook = { content, regexDepth ->
                capturedDepths += regexDepth
                content
            },
        )
        assertEquals(1, capturedDepths.size)
        assertEquals(0, capturedDepths.first())
    }

    @Test
    fun emptyEntryContentWithAddMemoStillInjectsMemo() {
        // 锁住 docs/modules/lorebook.md "已知坑"中登记的旧偏差行为:
        // entry.content 原本就空 + addMemo=true + comment 非空 → 注入文本仍包含 comment。
        // 对齐酒馆是另一回事(酒馆 5086 行只对 content 跑正则后判 if (!content) 跳过,
        // 本仓库 formatContent 会把 comment 拼进 raw,见 docs/modules/lorebook.md "已知坑")。
        // 此测试防止未来重构正则跳过逻辑时误把"原 content 空 + comment 非空"也跳过。
        val entry = LorebookEntry(
            uid = 1,
            key = listOf("alpha"),
            content = "",
            comment = "memo-only",
            addMemo = true,
        )
        val result = engine.activate(
            messages = listOf("alpha"),
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(id = "book", name = "book", entries = listOf(entry)),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = 1,
            // hook 不动 content(空进空出),不应触发跳过分支(因为原 content 就是空,不是被正则跑空的)。
            contentRegexHook = { content, _ -> content },
        )
        assertEquals("memo-only", result.worldInfoBefore)
    }
}
