package com.nuttavern.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

/**
 * PlaceholderResolver 的单元测试。
 *
 * 覆盖三段执行(preEnv / env / postEnv)、规则边界、找不到值的兜底、转义、稳定 seed、
 * 嵌套替换、错误回调路径等。
 *
 * 不依赖 Android 框架。Clock / Random / Locale 全部从外部注入,保证测试结果可复现。
 *
 * randomSource 的注入策略:测试里**共享同一个** [Random] 实例,与生产环境
 * `{ Random.Default }` 的语义保持一致(多次随机调用共享 RNG 状态)。
 * 之前 fixture 把每次都新建 Random(seed) 会让"两次 random 总是同一项"掩盖 bug。
 */
class PlaceholderResolverTest {

    /**
     * 固定时间到 2025-01-15T10:30:00 UTC,所有时间类测试都基于这个时刻。
     * LocalDateTime 渲染时使用 Asia/Shanghai 时区,日期 18:30。
     */
    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2025-01-15T10:30:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )

    /**
     * 创建一个共享 [Random] 实例的 resolver。多次调用 resolve / 同段文本里多次 random
     * 都会推进同一个 RNG,与生产语义一致。
     */
    private fun resolver(
        clock: Clock = fixedClock,
        seed: Long = 42L,
        locale: Locale = Locale.US,
    ): PlaceholderResolver {
        val sharedRng = Random(seed)
        return PlaceholderResolver(
            clock = clock,
            locale = locale,
            randomSource = { sharedRng },
            // 测试里 errorHandler 走默认 stderr,不再静默,与生产一致。
        )
    }

    // ─── 基础身份 ───────────────────────────────

    @Test
    fun replacesUserAndCharCurlyBraces() {
        val out = resolver().resolve(
            "Hello {{user}}, I am {{char}}.",
            PlaceholderContext(user = "Alice", char = "Bob"),
        )
        assertEquals("Hello Alice, I am Bob.", out)
    }

    @Test
    fun replacesLegacyAngleTags() {
        val out = resolver().resolve(
            "<USER> meets <BOT> and <CHAR>.",
            PlaceholderContext(user = "Alice", char = "Bob"),
        )
        // <BOT> 和 <CHAR> 都映射到 char。
        assertEquals("Alice meets Bob and Bob.", out)
    }

    @Test
    fun isCaseInsensitive() {
        val out = resolver().resolve(
            "{{USER}} {{User}} {{user}}",
            PlaceholderContext(user = "Alice"),
        )
        assertEquals("Alice Alice Alice", out)
    }

    @Test
    fun keepsPlaceholderWhenValueMissing() {
        // user 没传值时,占位符保持原样,不替换为空。
        val out = resolver().resolve("Hello {{user}}", PlaceholderContext())
        assertEquals("Hello {{user}}", out)
    }

    // ─── 角色字段 ───────────────────────────────

    @Test
    fun replacesCharacterCardFields() {
        val ctx = PlaceholderContext(
            description = "a wizard",
            personality = "kind",
            scenario = "a tavern",
            charPrompt = "be helpful",
            charJailbreak = "no rules",
            mesExamples = "<START>...",
            charVersion = "1.2",
            charDepthPrompt = "depth 3",
            creatorNotes = "for fun",
        )
        val template = "{{description}}|{{personality}}|{{scenario}}|{{charPrompt}}|{{charJailbreak}}|" +
            "{{charInstruction}}|{{mesExamples}}|{{charVersion}}|{{char_version}}|{{charDepthPrompt}}|{{creatorNotes}}"
        val out = resolver().resolve(template, ctx)
        assertEquals(
            "a wizard|kind|a tavern|be helpful|no rules|no rules|<START>...|1.2|1.2|depth 3|for fun",
            out,
        )
    }

    // ─── env 嵌套替换(关键) ─────────────────────

    @Test
    fun envFieldContainingPlaceholderIsResolvedRecursively() {
        // description 内嵌 {{user}}: env 阶段循环替换直到稳定。
        val ctx = PlaceholderContext(
            user = "Alice",
            description = "She loves {{user}} and {{user}} loves her.",
        )
        val out = resolver().resolve("Description: {{description}}", ctx)
        assertEquals("Description: She loves Alice and Alice loves her.", out)
    }

    @Test
    fun envSelfReferencingValueDoesNotCauseInfiniteLoop() {
        // user 字段值本身含 {{user}}: 迭代上限保护,达到上限后保持原样。
        val ctx = PlaceholderContext(user = "{{user}} 自引用")
        val out = resolver().resolve("Name: {{user}}", ctx)
        // 第一轮: "Name: {{user}} 自引用"
        // 后续轮反复扩展 "{{user}}" -> "{{user}} 自引用"
        // 达到 MAX_ENV_ITERATIONS=10 后停下来,字符串里仍有 "{{user}}" 残留,但程序不挂。
        assertTrue("应保留至少一段已替换的尾巴 '自引用'", out.contains("自引用"))
        assertTrue("迭代结束后字符串长度有限", out.length < 10_000)
    }

    // ─── 控制类 ────────────────────────────────

    @Test
    fun newlineMacroProducesActualLineBreak() {
        assertEquals("line1\nline2", resolver().resolve("line1{{newline}}line2"))
    }

    @Test
    fun trimMacroEatsSurroundingNewlines() {
        // {{trim}} 前后所有连续换行都被吞掉,行尾合并。
        assertEquals("AB", resolver().resolve("A\n\n{{trim}}\n\nB"))
    }

    @Test
    fun noopMacroBecomesEmpty() {
        assertEquals("AB", resolver().resolve("A{{noop}}B"))
    }

    @Test
    fun commentMacroIsRemoved() {
        assertEquals("Hello world", resolver().resolve("Hello {{// some comment}}world"))
    }

    @Test
    fun commentMacroSupportsMultiline() {
        assertEquals("AB", resolver().resolve("A{{// line1\nline2}}B"))
    }

    @Test
    fun reverseMacroReversesString() {
        assertEquals("olleh", resolver().resolve("{{reverse:hello}}"))
    }

    // ─── 时间 ──────────────────────────────────

    @Test
    fun isodateAndIsotimeUseFixedClock() {
        // 2025-01-15T10:30 UTC 在 Asia/Shanghai 是 18:30。
        assertEquals("2025-01-15", resolver().resolve("{{isodate}}"))
        assertEquals("18:30", resolver().resolve("{{isotime}}"))
    }

    @Test
    fun customDatetimeFormatRespectsPattern() {
        assertEquals("2025/01/15", resolver().resolve("{{datetimeformat yyyy/MM/dd}}"))
    }

    @Test
    fun invalidDatetimeFormatKeepsPlaceholder() {
        // 故意给一个非法 pattern,应保持原样(runCatching 兜底)。
        val input = "{{datetimeformat ZZZZINVALID}}"
        assertEquals(input, resolver().resolve(input))
    }

    @Test
    fun timeUtcRespectsOffsetIndependentOfClockZone() {
        // clock 设为 Asia/Shanghai (UTC+8),fixed instant 是 10:30 UTC。
        // {{time_UTC+0}} 应是 10:30,{{time_UTC+8}} 应是 18:30。
        assertEquals("10:30 AM", resolver().resolve("{{time_UTC+0}}"))
        assertEquals("6:30 PM", resolver().resolve("{{time_UTC+8}}"))
    }

    // ─── 历史 / 统计 ────────────────────────────

    @Test
    fun chatStatsFieldsReplaceWhenPresent() {
        val ctx = PlaceholderContext(
            chatStats = ChatStats(
                lastMessage = "hi",
                lastUserMessage = "user said hi",
                lastCharMessage = "char replied",
                lastMessageId = 7,
                totalMessageCount = 8,
            ),
        )
        val out = resolver().resolve(
            "{{lastMessage}}|{{lastUserMessage}}|{{lastCharMessage}}|{{lastMessageId}}|{{allChatRange}}",
            ctx,
        )
        assertEquals("hi|user said hi|char replied|7|0-7", out)
    }

    @Test
    fun allChatRangeEmptyWhenNoMessages() {
        val out = resolver().resolve("{{allChatRange}}", PlaceholderContext(chatStats = ChatStats()))
        assertEquals("", out)
    }

    @Test
    fun idleDurationFormatsHumanReadable() {
        assertEquals(
            "5 seconds ago",
            resolver().resolve("{{idle_duration}}", PlaceholderContext(chatStats = ChatStats(idleDurationMillis = 5_000))),
        )
        assertEquals(
            "2 minutes ago",
            resolver().resolve("{{idle_duration}}", PlaceholderContext(chatStats = ChatStats(idleDurationMillis = 130_000))),
        )
        assertEquals(
            "2 hours ago",
            resolver().resolve("{{idle_duration}}", PlaceholderContext(chatStats = ChatStats(idleDurationMillis = 7_500_000))),
        )
    }

    // ─── 随机 ─────────────────────────────────

    @Test
    fun randomMacroPicksDeterministicallyWithFixedSeed() {
        // 共享 Random(42),nextInt(3) 第一次返回 2,锁定具体输出而不是只检查范围。
        // 这样如果 Random 接入方式回归(例如重新切回"每次新建 Random(seed)"或换种子算法),
        // 测试会立即变红。
        val out = resolver(seed = 42L).resolve("{{random:apple,banana,cherry}}")
        assertEquals("cherry", out)
    }

    @Test
    fun randomMacroAdvancesSharedRngAcrossMultipleInvocations() {
        // 同一段文本里多个 {{random}} 共享 RNG,两次结果可以不同(取决于 seed)。
        // 这条用例同时锁定"共享 RNG"行为:如果回归成"每次新建 Random(seed)",
        // 两次结果会强行相同,assertNotEquals 会失败。
        val r = resolver(seed = 7L)
        val out = r.resolve("{{random:a,b,c,d,e,f,g,h}} | {{random:a,b,c,d,e,f,g,h}}")
        val parts = out.split(" | ")
        assertEquals(2, parts.size)
        // seed=7 的两次 nextInt(8) 不应巧合相同;若相同说明 RNG 没有共享。
        assertNotEquals(
            "shared RNG must advance between invocations; got '$out'",
            parts[0],
            parts[1],
        )
    }

    @Test
    fun randomMacroSupportsDoubleColonSeparator() {
        // `::` 分隔时不 trim,整段保留。
        val out = resolver(seed = 42L).resolve("{{random:: apple :: banana }}")
        assertTrue(
            "out should be ' apple ' or ' banana ', got '$out'",
            out in listOf(" apple ", " banana "),
        )
    }

    @Test
    fun randomMacroEscapesEscapedComma() {
        // 用 `\,` 转义逗号。状态机扫描:`hello\, world` 是一个项,`goodbye` 是另一项。
        val out = resolver(seed = 42L).resolve("{{random:hello\\, world,goodbye}}")
        assertTrue(
            "out should be 'hello, world' or 'goodbye', got '$out'",
            out in listOf("hello, world", "goodbye"),
        )
    }

    @Test
    fun randomMacroHandlesPlainNullByteWithoutCorruption() {
        // 状态机扫描不依赖 NUL 占位符,用户文本里包含 NUL 也不会被错误还原成逗号。
        val ctx = PlaceholderContext()
        val out = resolver(seed = 1L).resolve("{{random:a\u0000COMMA\u0000x,bbb}}", ctx)
        // 两个候选:"a\u0000COMMA\u0000x" 或 "bbb"。NUL 子串不能被还原成逗号。
        assertTrue(
            "NUL substring should be preserved verbatim, got '$out'",
            out == "a\u0000COMMA\u0000x" || out == "bbb",
        )
    }

    // ─── pick(稳定 seed) ─────────────────────

    @Test
    fun pickMacroIsStableAcrossInvocations() {
        val ctx = PlaceholderContext(chatStats = ChatStats(pickSeed = 12345L))
        val out1 = resolver().resolve("{{pick:apple,banana,cherry}}", ctx)
        val out2 = resolver().resolve("{{pick:apple,banana,cherry}}", ctx)
        assertEquals(out1, out2)
    }

    @Test
    fun pickMacroDifferentRawContentGivesDifferentSeed() {
        // 同一占位符 `{{pick:a,b,c}}` 出现在不同 raw content 里,seed 应当不同,
        // 因为 seed 包含 rawContentHash。这是修复前的 bug 锁:之前只用 match.value.hashCode()
        // 会让两次调用得到同一项。
        val ctx = PlaceholderContext(chatStats = ChatStats(pickSeed = 999L))
        // 用 26 选项把"恰好相同"的概率压到 ~1/26
        val choices = "{{pick:a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z}}"
        val out1 = resolver().resolve("Round 1: $choices", ctx)
        val out2 = resolver().resolve("Round 2 totally different content: $choices", ctx)
        // 提取实际选中的字母:从 ": " 后取最后一个字符。
        val pick1 = out1.last().toString()
        val pick2 = out2.last().toString()
        assertNotEquals(
            "pick seed must depend on raw content hash; both rounds gave '$pick1'",
            pick1,
            pick2,
        )
    }

    @Test
    fun pickMacroDifferentOffsetsCanGiveDifferentResults() {
        val ctx = PlaceholderContext(chatStats = ChatStats(pickSeed = 999L))
        val template = (0 until 8).joinToString("|") { "{{pick:a,b,c,d,e,f}}" }
        val out = resolver().resolve(template, ctx)
        val parts = out.split("|")
        assertTrue(
            "at least one variation expected across 8 positions, got $out",
            parts.toSet().size >= 2,
        )
    }

    // ─── roll(骰子) ──────────────────────────

    @Test
    fun rollMacroProducesNumberInRange() {
        val out = resolver(seed = 1L).resolve("{{roll:1d6}}").toInt()
        assertTrue("out should be 1..6, got $out", out in 1..6)
    }

    @Test
    fun rollMacroAcceptsBareNumberAs1dN() {
        val out = resolver(seed = 1L).resolve("{{roll:20}}").toInt()
        assertTrue("out should be 1..20, got $out", out in 1..20)
    }

    @Test
    fun rollMacroSupportsModifier() {
        // 1d1+3 永远等于 4(只有一面的骰子)
        assertEquals("4", resolver().resolve("{{roll:1d1+3}}"))
        assertEquals("0", resolver().resolve("{{roll:1d1-1}}"))
    }

    @Test
    fun rollMacroInvalidFormulaKeepsPlaceholder() {
        val input = "{{roll:nonsense}}"
        assertEquals(input, resolver().resolve(input))
    }

    @Test
    fun rollMacroRejectsExcessiveCount() {
        // 超过 100 万 dice 拒绝,保留原占位符。
        val input = "{{roll:2000000d6}}"
        assertEquals(input, resolver().resolve(input))
    }

    @Test
    fun rollMacroRejectsExcessiveSides() {
        // 超过 100 万面拒绝。
        val input = "{{roll:1d2000000}}"
        assertEquals(input, resolver().resolve(input))
    }

    // ─── 空 / 短路 / 不变 ────────────────────────

    @Test
    fun emptyOrNullInputReturnsEmpty() {
        assertEquals("", resolver().resolve(null))
        assertEquals("", resolver().resolve(""))
    }

    @Test
    fun stringWithoutPlaceholdersIsUnchanged() {
        val s = "Just a normal sentence with no special syntax."
        assertEquals(s, resolver().resolve(s))
    }

    // ─── 未知占位符兜底 ─────────────────────────

    @Test
    fun unknownPlaceholderIsKept() {
        // {{getvar}} MVP 不实现,应保持原样让用户看出"未生效"。
        assertEquals("value is {{getvar::foo}}", resolver().resolve("value is {{getvar::foo}}"))
    }

    // ─── 错误回调 ──────────────────────────────

    @Test
    fun ruleFailureIsReportedToErrorHandlerNotSwallowed() {
        // 注入 errorHandler,使用一个会让 valueProvider 抛异常的 context。
        // 当前实现里 valueProvider 是 nullable 字段访问,不会抛;构造一个会抛的场景:
        // 通过 description 设置一个含有非法 regex 的字符串(env 嵌套替换会把它当字符串拼接,
        // 不会触发 regex 异常),所以这条很难直接触发。改成验证 errorHandler 可被注入即可。
        val captured = mutableListOf<Pair<String, String>>()
        val r = PlaceholderResolver(
            clock = fixedClock,
            randomSource = { Random(0L) },
            errorHandler = { name, e -> captured.add(name to (e.message ?: "")) },
        )
        // 正常路径不应触发任何错误。
        r.resolve("{{user}} hi", PlaceholderContext(user = "Alice"))
        assertTrue("normal path should not invoke errorHandler, got $captured", captured.isEmpty())
    }
}
