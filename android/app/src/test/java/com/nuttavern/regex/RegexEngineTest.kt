package com.nuttavern.regex

import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.data.regex.SubstituteRegex
import com.nuttavern.prompt.PlaceholderContext
import com.nuttavern.prompt.PlaceholderResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RegexEngine 单测。覆盖以下路径:
 *
 * 1. JS regex flag 兼容(i / s / m,g 自动全局,u/y 静默忽略);
 * 2. 替换串引用语法({{match}} / $0 / $1 / $<name>,缺失引用 → 空串,字面 $ 保留);
 * 3. trimStrings 作用在被引用的捕获组值上(对齐酒馆 filterString,不动整段输入);
 * 4. SubstituteRegex 三模式(NONE / RAW / ESCAPED);
 * 5. 三作用域执行顺序与开关(GLOBAL → SCOPED → PRESET,characterAllowed / presetAllowed);
 * 6. placement 过滤(USER_INPUT / AI_OUTPUT 不互通);
 * 7. minDepth / maxDepth 边界;
 * 8. disabled / promptOnly / markdownOnly / runOnEdit 过滤;
 * 9. 模式非法 / flag 非法时静默返回原文。
 */
class RegexEngineTest {

    private val resolver = PlaceholderResolver()
    private val engine = RegexEngine(resolver)

    // ─── JS flag 兼容 ─────────────────────────────────

    @Test
    fun flagILiteralIsCaseInsensitive() {
        val out = engine.runRegexScript(
            "Hello WORLD",
            buildScript(findRegex = "/world/i", replaceString = "kotlin"),
        )
        assertEquals("Hello kotlin", out)
    }

    @Test
    fun flagSDotMatchesNewline() {
        val out = engine.runRegexScript(
            "a\nb",
            buildScript(findRegex = "/a.b/s", replaceString = "X"),
        )
        assertEquals("X", out)
    }

    @Test
    fun flagMMultilineCaret() {
        val out = engine.runRegexScript(
            "a\nb\nc",
            buildScript(findRegex = "/^b/m", replaceString = "B"),
        )
        assertEquals("a\nB\nc", out)
    }

    @Test
    fun flagGIsAutoGlobalReplaceAll() {
        val out = engine.runRegexScript(
            "a a a",
            buildScript(findRegex = "/a/g", replaceString = "b"),
        )
        assertEquals("b b b", out)
    }

    @Test
    fun unsupportedFlagYIsSilentlyDropped() {
        // sticky `y` flag Kotlin 不支持,引擎应丢弃 flag 但保留 pattern;不抛异常。
        val out = engine.runRegexScript(
            "abc",
            buildScript(findRegex = "/b/y", replaceString = "X"),
        )
        assertEquals("aXc", out)
    }

    // ─── 替换串引用语法转换 ──────────────────────────

    @Test
    fun matchPlaceholderTranslatesToWholeMatch() {
        val out = engine.runRegexScript(
            "<thinking>secret</thinking>",
            buildScript(
                findRegex = "/<thinking>([\\s\\S]*?)<\\/thinking>/s",
                replaceString = "[{{match}}]",
            ),
        )
        assertEquals("[<thinking>secret</thinking>]", out)
    }

    @Test
    fun numericGroupRefIsPreserved() {
        val out = engine.runRegexScript(
            "Alice",
            buildScript(findRegex = "/(A)(li)(ce)/", replaceString = "$3-$2-$1"),
        )
        assertEquals("ce-li-A", out)
    }

    @Test
    fun namedGroupRefAngleBracketSyntaxIsTranslated() {
        val out = engine.runRegexScript(
            "Alice",
            buildScript(
                findRegex = "/(?<head>A)(?<tail>lice)/",
                replaceString = "\$<tail>-\$<head>",
            ),
        )
        assertEquals("lice-A", out)
    }

    // ─── trimStrings(作用在被引用的捕获组值上,对齐酒馆 filterString)──

    @Test
    fun trimStringsRemovedFromReferencedGroupValue() {
        // 对齐酒馆 engine.js 第 438 行:trimStrings 只作用在替换串引用到的**组值**上,
        // 不动整段输入。组 1 捕获 "<noise>secret<noise>",trim 掉 "<noise>" → "secret"。
        val out = engine.runRegexScript(
            "<tag><noise>secret<noise></tag>",
            buildScript(
                findRegex = "/<tag>(.*)<\\/tag>/",
                replaceString = "$1",
                trimStrings = listOf("<noise>"),
            ),
        )
        assertEquals("secret", out)
    }

    @Test
    fun trimStringsDoNotTouchInputOutsideReferencedGroups() {
        // 替换串无组引用(纯字面 "OK"),trimStrings 不会作用到任何地方;
        // 输入里匹配区域外的 "<noise>" 应原样保留 —— 与旧"整段先 trim"实现的关键区别。
        val out = engine.runRegexScript(
            "<noise>Hello world<noise>",
            buildScript(
                findRegex = "/Hello world/",
                replaceString = "OK",
                trimStrings = listOf("<noise>"),
            ),
        )
        assertEquals("<noise>OK<noise>", out)
    }

    @Test
    fun trimStringsAppliedPerGroupNotWholeMatch() {
        // 多组引用,每个组值各自 trim。组 1 = "[a]x[a]" → "x",组 2 = "[a]y[a]" → "y"。
        val out = engine.runRegexScript(
            "([a]x[a])([a]y[a])",
            buildScript(
                findRegex = "/\\(([^)]*)\\)\\(([^)]*)\\)/",
                replaceString = "$1-$2",
                trimStrings = listOf("[a]"),
            ),
        )
        assertEquals("x-y", out)
    }

    @Test
    fun matchMacroValueAlsoTrimmed() {
        // {{match}} → $0(整匹配)也算被引用的组值,同样过 trimStrings。
        val out = engine.runRegexScript(
            "<x>keep<x>",
            buildScript(
                findRegex = "/<x>keep<x>/",
                replaceString = "{{match}}",
                trimStrings = listOf("<x>"),
            ),
        )
        assertEquals("keep", out)
    }

    @Test
    fun missingNumberedGroupReferenceBecomesEmpty() {
        // 引用不存在的组($3,但只有 2 个组)→ 空串(酒馆 engine.js 第 433-435 行)。
        val out = engine.runRegexScript(
            "ab",
            buildScript(findRegex = "/(a)(b)/", replaceString = "$1$3$2"),
        )
        assertEquals("ab", out)
    }

    @Test
    fun missingNamedGroupReferenceBecomesEmpty() {
        // 引用不存在的命名组 → 空串,不抛异常。
        val out = engine.runRegexScript(
            "ab",
            buildScript(
                findRegex = "/(?<first>a)(b)/",
                replaceString = "\$<first>\$<missing>",
            ),
        )
        assertEquals("a", out)
    }

    @Test
    fun literalDollarSequenceNotMatchedAsGroupIsPreserved() {
        // 替换串里 "$x"(x 非数字、非 <name>)不被引用正则匹配 → 原样保留,不报错。
        // 这正是手动解析相比 Kotlin Regex.replace 的优势:字面 $ 不触发异常。
        val out = engine.runRegexScript(
            "price",
            buildScript(findRegex = "/price/", replaceString = "cost is \$x here"),
        )
        assertEquals("cost is \$x here", out)
    }

    // ─── SubstituteRegex(作用于 Find Regex)────────────

    @Test
    fun substituteRegexNoneKeepsFindRegexAsIs() {
        // NONE:findRegex 原样使用,{{user}} 不会被展开,所以匹配字面 "{{user}}" 的子串
        // 不出现 → 替换不发生,原文不变。
        val out = engine.runRegexScript(
            "Hello Bob",
            buildScript(
                findRegex = "/{{user}}/",
                replaceString = "PLAYER",
                substituteRegex = SubstituteRegex.NONE.value,
            ),
            placeholderContext = PlaceholderContext(user = "Bob"),
        )
        assertEquals("Hello Bob", out)
    }

    @Test
    fun substituteRegexRawExpandsPlaceholdersInFindRegex() {
        // RAW:findRegex 中的 {{user}} 替换为 "Bob",所以匹配字面 "Bob" → 替换发生。
        val out = engine.runRegexScript(
            "Hello Bob",
            buildScript(
                findRegex = "/{{user}}/",
                replaceString = "PLAYER",
                substituteRegex = SubstituteRegex.RAW.value,
            ),
            placeholderContext = PlaceholderContext(user = "Bob"),
        )
        assertEquals("Hello PLAYER", out)
    }

    @Test
    fun substituteRegexEscapedNeutralizesMetacharsInExpandedFindRegex() {
        // ESCAPED:占位符替换 + 元字符转义。假设 user 名字里含正则元字符 "Bob.Smith",
        // ESCAPED 模式会把它转成 "Bob\.Smith",匹配字面值;RAW 不转义,"." 会匹配任意字符,
        // 可能误匹配 "BobXSmith"。
        val pattern = "/{{user}}/"
        val escaped = engine.runRegexScript(
            "Hello BobXSmith",
            buildScript(
                findRegex = pattern,
                replaceString = "PLAYER",
                substituteRegex = SubstituteRegex.ESCAPED.value,
            ),
            placeholderContext = PlaceholderContext(user = "Bob.Smith"),
        )
        // ESCAPED 把 "." 转义,所以不会匹配 "BobXSmith" → 原文不变。
        assertEquals("Hello BobXSmith", escaped)
    }

    @Test
    fun substituteRegexEscapedPreservesMetacharsInFindRegexLiteral() {
        // 对齐酒馆 substituteParamsExtended(..., sanitizeRegexMacro):
        // 只对**展开的占位符值**做元字符转义,findRegex **字面量里**用户写的 `.* ` 等正则元素**保留**。
        //
        // 场景:findRegex 写 `/{{user}}.* test/`,user="Alice.Bob"
        // - ESCAPED 期望:user 值里的 `.` 转义 → `Alice\.Bob`,但 findRegex 字面里的 `.*` **保留**
        //   作为正则元素 → 完整 pattern 等价 `Alice\.Bob.*test`,能匹配 "Alice.Bob foo test" 这种
        //   "user 字面 + 任意中间 + test" 的串。
        // - 旧实现错误地整串转义,会把 `.* ` 一起转成 `\.\*test`,无法匹配上述输入。
        val out = engine.runRegexScript(
            "before Alice.Bob foo test after",
            buildScript(
                findRegex = "/{{user}}.*test/",
                replaceString = "MATCHED",
                substituteRegex = SubstituteRegex.ESCAPED.value,
            ),
            placeholderContext = PlaceholderContext(user = "Alice.Bob"),
        )
        assertEquals("before MATCHED after", out)
    }

    @Test
    fun substituteRegexEscapedDoesNotMatchWhenUserValueLiteralAbsent() {
        // 与上一条对照:user="Alice.Bob",但输入串里只有 "AliceXBob"(无字面 `.`),期望
        // ESCAPED 模式下不匹配(`.` 转义后只认字面 `.`)。这条排除"任何中间字符都能匹配"
        // 的整串转义反例。
        val out = engine.runRegexScript(
            "before AliceXBob foo test after",
            buildScript(
                findRegex = "/{{user}}.*test/",
                replaceString = "MATCHED",
                substituteRegex = SubstituteRegex.ESCAPED.value,
            ),
            placeholderContext = PlaceholderContext(user = "Alice.Bob"),
        )
        assertEquals("before AliceXBob foo test after", out)
    }

    @Test
    fun substituteRegexEscapedKeepsCaptureGroupsInFindRegex() {
        // findRegex 里写的 `(...)` 分组在 ESCAPED 模式下应保留为正则分组,replace 串里 `$1` 能引用。
        // 旧整串转义会把 `(` `)` 转成 `\(` `\)`,$1 引用不到。
        val out = engine.runRegexScript(
            "Bob says hi",
            buildScript(
                findRegex = "/(\\w+) says (\\w+)/",
                replaceString = "{{user}}: $2 ($1)",
                substituteRegex = SubstituteRegex.ESCAPED.value,
            ),
            placeholderContext = PlaceholderContext(user = "Player.X"),
        )
        // {{user}} 出现在 replace 串(不是 findRegex),所以不走 ESCAPED 转义路径,
        // 而是走最后阶段的占位符替换 → "Player.X" 原样写入。
        assertEquals("Player.X: hi (Bob)", out)
    }

    @Test
    fun replaceStringAlwaysResolvesPlaceholdersAfterSubstitution() {
        // Replace 串里的 {{user}} 占位符**始终**在最后被替换,与 substituteRegex 字段无关。
        // 这是酒馆 engine.js 第 444 行 substituteParams 的行为,对齐之。
        val out = engine.runRegexScript(
            "x",
            buildScript(
                findRegex = "/x/",
                replaceString = "{{user}}",
                substituteRegex = SubstituteRegex.NONE.value,
            ),
            placeholderContext = PlaceholderContext(user = "Bob"),
        )
        assertEquals("Bob", out)
    }

    // ─── 三作用域执行顺序 ───────────────────────────

    @Test
    fun scopesExecuteInGlobalThenScopedThenPresetOrder() {
        val global = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val scoped = buildScript(
            findRegex = "/bar/",
            replaceString = "baz",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val preset = buildScript(
            findRegex = "/baz/",
            replaceString = "qux",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(global),
            scopedScripts = listOf(scoped),
            presetScripts = listOf(preset),
        )
        assertEquals("qux", out)
    }

    @Test
    fun characterAllowedFalseSkipsScopedScripts() {
        val global = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val scoped = buildScript(
            findRegex = "/bar/",
            replaceString = "baz",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(global),
            scopedScripts = listOf(scoped),
            characterAllowed = false,
        )
        assertEquals("bar", out)
    }

    @Test
    fun presetAllowedFalseSkipsPresetScripts() {
        val global = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val preset = buildScript(
            findRegex = "/bar/",
            replaceString = "baz",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(global),
            presetScripts = listOf(preset),
            presetAllowed = false,
        )
        assertEquals("bar", out)
    }

    // ─── placement / 标志位过滤 ──────────────────────

    @Test
    fun placementMismatchSkipsScript() {
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.AI_OUTPUT.value),
        )
        // 当前阶段 USER_INPUT,但脚本只在 AI_OUTPUT 启用 → 跳过。
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
        )
        assertEquals("foo", out)
    }

    @Test
    fun disabledScriptIsSkipped() {
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
            disabled = true,
        )
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
        )
        assertEquals("foo", out)
    }

    @Test
    fun runOnEditFalseSkipsEditPath() {
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
            runOnEdit = false,
        )
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
            isEdit = true,
        )
        assertEquals("foo", out)
    }

    // ─── 三场景门控(Ephemerality)─────────────────

    @Test
    fun defaultScriptOnlyRunsInFileScene() {
        // 两个 Ephemerality 都不勾 = 改文件场景。isPrompt / isMarkdown 任一 true 都应跳过。
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val inPromptScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
            isPrompt = true,
        )
        assertEquals("foo", inPromptScene)

        val inMarkdownScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
            isMarkdown = true,
        )
        assertEquals("foo", inMarkdownScene)

        val inFileScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
        )
        assertEquals("bar", inFileScene)
    }

    @Test
    fun promptOnlyScriptOnlyRunsInPromptScene() {
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
            promptOnly = true,
        )
        val inPromptScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
            isPrompt = true,
        )
        assertEquals("bar", inPromptScene)

        // 改文件场景不应跑
        val inFileScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
        )
        assertEquals("foo", inFileScene)
    }

    @Test
    fun markdownOnlyScriptOnlyRunsInMarkdownScene() {
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.AI_OUTPUT.value),
            markdownOnly = true,
        )
        val inMarkdownScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.AI_OUTPUT,
            globalScripts = listOf(script),
            isMarkdown = true,
        )
        assertEquals("bar", inMarkdownScene)

        // 改文件场景不应跑
        val inFileScene = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.AI_OUTPUT,
            globalScripts = listOf(script),
        )
        assertEquals("foo", inFileScene)
    }

    @Test
    fun depthFilterRangeRespected() {
        val script = buildScript(
            findRegex = "/foo/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.AI_OUTPUT.value),
            minDepth = 1,
            maxDepth = 3,
        )
        // depth = 0 不在 [1, 3] 范围,跳过。
        val skipped = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.AI_OUTPUT,
            globalScripts = listOf(script),
            depth = 0,
        )
        assertEquals("foo", skipped)

        // depth = 2 在范围内,执行。
        val applied = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.AI_OUTPUT,
            globalScripts = listOf(script),
            depth = 2,
        )
        assertEquals("bar", applied)
    }

    // ─── 异常路径 ───────────────────────────────────

    @Test
    fun invalidRegexPatternReturnsRawSilently() {
        val script = buildScript(
            findRegex = "/[unclosed/",
            replaceString = "bar",
            placement = listOf(RegexPlacement.USER_INPUT.value),
        )
        val out = engine.getRegexedString(
            raw = "foo",
            placement = RegexPlacement.USER_INPUT,
            globalScripts = listOf(script),
        )
        // 非法模式 → 静默返回原文,不抛异常,不影响其他脚本。
        assertEquals("foo", out)
    }

    @Test
    fun emptyInputShortCircuits() {
        val script = buildScript(findRegex = "/foo/", replaceString = "bar")
        val out = engine.runRegexScript("", script)
        assertEquals("", out)
    }

    @Test
    fun bareRegexWithoutSlashesIsAccepted() {
        // 用户输入裸正则(没用 /pattern/flags 字面量包裹)也应能匹配,降低输入门槛。
        val out = engine.runRegexScript(
            "foo bar",
            buildScript(findRegex = "foo", replaceString = "BAZ"),
        )
        assertEquals("BAZ bar", out)
    }

    private fun buildScript(
        findRegex: String,
        replaceString: String,
        trimStrings: List<String> = emptyList(),
        placement: List<Int> = listOf(
            RegexPlacement.USER_INPUT.value,
            RegexPlacement.AI_OUTPUT.value,
        ),
        disabled: Boolean = false,
        markdownOnly: Boolean = false,
        promptOnly: Boolean = false,
        runOnEdit: Boolean = true,
        substituteRegex: Int = SubstituteRegex.NONE.value,
        minDepth: Int? = null,
        maxDepth: Int? = null,
    ): RegexScript {
        return RegexScript(
            id = "test-${findRegex.hashCode()}",
            scriptName = "test",
            findRegex = findRegex,
            replaceString = replaceString,
            trimStrings = trimStrings,
            placement = placement,
            disabled = disabled,
            markdownOnly = markdownOnly,
            promptOnly = promptOnly,
            runOnEdit = runOnEdit,
            substituteRegex = substituteRegex,
            minDepth = minDepth,
            maxDepth = maxDepth,
        )
    }
}
