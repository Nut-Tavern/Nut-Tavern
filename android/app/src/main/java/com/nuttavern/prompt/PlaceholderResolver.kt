package com.nuttavern.prompt

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.random.Random

/**
 * 占位符替换器。
 *
 * 把字符串里的 `{{xxx}}` / `<USER>` 等占位符按上下文替换成真实文本,语义对齐 SillyTavern
 * 的 macros 系统(参考 docs/prompt-pipeline-reference.md "占位符" 一节)。
 *
 * # 三段执行
 *
 * 顺序与酒馆 evaluateMacros 对齐:**preEnv → env → postEnv**。
 * - preEnv:不依赖上下文的旧标签 / 控制类(`<USER>` / `{{newline}}` / `{{trim}}` / `{{noop}}` / `{{roll}}`)。
 * - env:上下文驱动的字段(`{{user}}` / `{{description}}` / 角色卡字段)。
 * - postEnv:依赖时间 / 随机 / 历史统计 / 注释吞掉(`{{date}}` / `{{random}}` / `{{// 注释}}`)。
 *
 * # 嵌套占位符
 *
 * env 字段(角色 description / personality 等)里如果再嵌占位符,例如
 * `description = "loves {{user}}"`,resolve 内部会**反复跑 env 阶段直到稳定或达到迭代上限**。
 * 这避免了"description 替换后里面的 `{{user}}` 永远停留"的问题。
 *
 * 迭代上限 [MAX_ENV_ITERATIONS] 是为了防止"自我引用"占位符(如 user 值含 `{{user}}`)导致的无限循环;
 * 达到上限后剩余占位符保持原样。
 *
 * # 找不到值的兜底
 *
 * 占位符**已注册但值为 null**(例如 user 为 null)→ **保留原占位符**,不替换为空。
 * 这是 nut-tavern 有意保留的偏差(酒馆 sanitizeMacroValue 会输出空串):
 * 保留原占位符让用户在调试时立刻看出"哪个变量没传"。详见 docs/prompt-pipeline.md "偏差登记"。
 *
 * # 失败兜底
 *
 * 单条规则抛异常时通过 [errorHandler] 上报并跳过该条规则,不让整段替换崩溃。
 * 默认 errorHandler 走 stderr,生产侧由调用方注入 logger 接到自己的诊断系统。
 *
 * # 大小写
 *
 * 所有规则带 [RegexOption.IGNORE_CASE](与酒馆 `gi` flag 对齐)。
 *
 * # 短路
 *
 * 输入既无 `{{` 也无 `<` 时直接返回原文,跳过整个替换循环。
 */
class PlaceholderResolver(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: Locale = Locale.getDefault(),
    /**
     * `{{random:...}}` / `{{roll}}` 用的随机源。每次调用返回**同一个**共享 [Random],
     * 让多次随机调用共享 RNG 状态,避免"每次新建 Random(seed)"掩盖 bug。
     * 测试时注入 `{ sharedRng }`(同一引用)。
     */
    private val randomSource: () -> Random = { Random.Default },
    /**
     * 单条规则失败时的回调。默认走 [System.err]。
     * 不调 android.util.Log:它在 unit test 里默认 throw,会让 fail-safe 反而成测试崩源。
     */
    private val errorHandler: (ruleName: String, error: Throwable) -> Unit = { name, e ->
        System.err.println("PlaceholderResolver: rule '$name' failed: ${e.message}")
    },
) {
    /**
     * 替换 [content] 里的占位符。返回替换后的字符串。
     * [content] 为 null / 空时返回 ""(对齐酒馆 evaluateMacros 行为)。
     *
     * @param valueTransform 可选回调,对**每个占位符的实际替换值**额外加工后再写回。用于
     *   "占位符值需要被转义"等场景(对齐酒馆 `substituteParamsExtended(..., postProcessFn)`)。
     *   仅作用于真正命中并被替换的占位符,**不**作用于未命中保留原样的占位符(`{{user}}` 在
     *   `context.user == null` 时保留原占位符,不进 transform)。
     * @param singlePass 是否禁用 env 阶段的多轮迭代(嵌套占位符展开)。`true` 等价于酒馆
     *   `substituteParamsExtended` 的单轮语义。**配合 [valueTransform] 使用 sanitize 类不幂等
     *   的 transform 时必传 true**,避免 env 多轮把已转义的字符再转义一次。
     */
    fun resolve(
        content: String?,
        context: PlaceholderContext = PlaceholderContext(),
        valueTransform: ((String) -> String)? = null,
        singlePass: Boolean = false,
    ): String {
        if (content.isNullOrEmpty()) return ""
        if (!content.contains("{{") && !content.contains("<")) return content

        // {{pick}} seed 包含整段 raw content 的 hash,与酒馆 getPickReplaceMacro 对齐:
        // 同一会话同一段文本里的同一处 pick 永远命中同一项。
        val rawContentHash = content.hashCode().toLong()

        var result: String = content

        // preEnv:静态规则 + 实例规则(实例规则需要访问 randomSource / clock,无法塞进 companion)。
        for (rule in PRE_ENV_RULES) {
            result = rule.applySafely(result, context, errorHandler, valueTransform)
        }
        for (rule in instancePreEnvRules) {
            result = rule.applySafely(result, context, errorHandler, valueTransform)
        }

        // env 阶段迭代,直到字符串不再变化或达到上限。
        // 每轮使用同一份 envRules(它们是基于 context 构造的,不会逐轮变化)。
        val envRules = buildEnvRules(context)
        val maxIterations = if (singlePass) 1 else MAX_ENV_ITERATIONS
        var iteration = 0
        while (iteration < maxIterations) {
            val before = result
            for (rule in envRules) {
                result = rule.applySafely(result, context, errorHandler, valueTransform)
            }
            if (result == before) break
            iteration++
        }

        for (rule in buildPostEnvRules(rawContentHash)) {
            result = rule.applySafely(result, context, errorHandler, valueTransform)
        }
        return result
    }

    // ─── 实例 preEnv 规则(需要访问 randomSource) ───

    private val instancePreEnvRules: List<Rule> = listOf(
        Rule(
            name = "{{roll}}",
            regex = Regex("\\{\\{roll[ :]([^}]+)\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, _ ->
                val formula = match.groupValues[1].trim()
                rollDice(formula, randomSource()) ?: match.value
            },
        ),
    )

    // ─── env 规则 ───────────────────────────────

    private fun buildEnvRules(context: PlaceholderContext): List<Rule> = buildList {
        // 身份类。
        add(Rule.field("user") { context.user })
        add(Rule.field("char") { context.char })
        add(Rule.field("group") { context.group })
        add(Rule.field("persona") { context.persona })

        // 角色卡字段。
        add(Rule.field("description") { context.description })
        add(Rule.field("personality") { context.personality })
        add(Rule.field("scenario") { context.scenario })
        add(Rule.field("charPrompt") { context.charPrompt })
        // charInstruction / charJailbreak 是同一字段的两个别名(对齐酒馆)。
        add(Rule.field("charJailbreak") { context.charJailbreak })
        add(Rule.field("charInstruction") { context.charJailbreak })
        add(Rule.field("mesExamples") { context.mesExamples })
        add(Rule.field("mesExamplesRaw") { context.mesExamplesRaw })
        // charVersion 有 camelCase 和 snake_case 两个写法,都支持。
        add(Rule.field("charVersion") { context.charVersion })
        add(Rule.field("char_version") { context.charVersion })
        add(Rule.field("charDepthPrompt") { context.charDepthPrompt })
        add(Rule.field("creatorNotes") { context.creatorNotes })
    }

    // ─── postEnv 规则 ──────────────────────────

    private fun buildPostEnvRules(rawContentHash: Long): List<Rule> = listOf(
        // 时间 / 日期。注意 datetimeformat 用 Java DateTimeFormatter 语法(YYYY 是 week-based-year),
        // 不是 moment.js 语法。详见 docs/prompt-pipeline.md "偏差登记"。
        Rule(
            name = "{{time}}",
            regex = Regex("\\{\\{time\\}\\}", RegexOption.IGNORE_CASE),
            replace = { _, _ ->
                LocalDateTime.now(clock).format(
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
                )
            },
        ),
        Rule(
            name = "{{date}}",
            regex = Regex("\\{\\{date\\}\\}", RegexOption.IGNORE_CASE),
            replace = { _, _ ->
                LocalDateTime.now(clock).format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale),
                )
            },
        ),
        Rule(
            name = "{{weekday}}",
            regex = Regex("\\{\\{weekday\\}\\}", RegexOption.IGNORE_CASE),
            replace = { _, _ ->
                LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("EEEE", locale))
            },
        ),
        Rule(
            name = "{{isotime}}",
            regex = Regex("\\{\\{isotime\\}\\}", RegexOption.IGNORE_CASE),
            replace = { _, _ ->
                LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("HH:mm"))
            },
        ),
        Rule(
            name = "{{isodate}}",
            regex = Regex("\\{\\{isodate\\}\\}", RegexOption.IGNORE_CASE),
            replace = { _, _ ->
                LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            },
        ),
        Rule(
            name = "{{datetimeformat}}",
            regex = Regex("\\{\\{datetimeformat\\s+([^}]+)\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, _ ->
                val pattern = match.groupValues[1].trim()
                runCatching {
                    LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern(pattern, locale))
                }.getOrElse { match.value }
            },
        ),
        Rule(
            name = "{{time_UTC}}",
            // `{{time_UTC±N}}` 是显式 UTC offset 路径,与 `{{time}}` `{{date}}` 跟随系统时区
            // (clock.zone)的路径独立。例如 clock 设为 Asia/Shanghai 时,`{{time}}` 显示北京时间,
            // 而 `{{time_UTC+0}}` 仍然是 UTC 时间。
            regex = Regex("\\{\\{time_UTC([+-]\\d+)\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, _ ->
                val offset = match.groupValues[1].toIntOrNull() ?: return@Rule match.value
                val zone = java.time.ZoneOffset.ofHours(offset)
                clock.instant().atZone(zone).format(
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
                )
            },
        ),

        // 历史 / 统计:都从 chatStats 取值,缺失时不替换。
        Rule.field("lastMessage") { it.chatStats?.lastMessage },
        Rule.field("lastUserMessage") { it.chatStats?.lastUserMessage },
        Rule.field("lastCharMessage") { it.chatStats?.lastCharMessage },
        Rule.field("lastMessageId") { it.chatStats?.lastMessageId?.toString() },
        Rule.field("firstIncludedMessageId") { it.chatStats?.firstIncludedMessageId?.toString() },
        Rule.field("lastSwipeId") { it.chatStats?.lastSwipeId?.toString() },
        Rule.field("currentSwipeId") { it.chatStats?.currentSwipeId?.toString() },
        Rule.field("allChatRange") {
            val total = it.chatStats?.totalMessageCount ?: 0
            if (total == 0) "" else "0-${total - 1}"
        },
        Rule.field("idle_duration") {
            val millis = it.chatStats?.idleDurationMillis ?: return@field null
            humanizeDuration(millis)
        },

        // {{reverse:str}}
        Rule(
            name = "{{reverse}}",
            regex = Regex("\\{\\{reverse:(.+?)\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, _ -> match.groupValues[1].reversed() },
        ),

        // {{random:a,b,c}} / {{random::a::b::c}}: 每次都重新随机
        Rule(
            name = "{{random}}",
            regex = Regex("\\{\\{random\\s?::?([^}]+)\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, _ ->
                val items = parsePickList(match.groupValues[1])
                if (items.isEmpty()) "" else items[randomSource().nextInt(items.size)]
            },
        ),

        // {{pick:a,b,c}} / {{pick::a::b::c}}: 同 random,但用稳定 seed
        // seed = chatStats.pickSeed XOR rawContentHash XOR 位置 hash,与酒馆对齐。
        Rule(
            name = "{{pick}}",
            regex = Regex("\\{\\{pick\\s?::?([^}]+)\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, ctx ->
                val items = parsePickList(match.groupValues[1])
                if (items.isEmpty()) {
                    ""
                } else {
                    val seed = (ctx.chatStats?.pickSeed ?: 0L)
                        .xor(rawContentHash)
                        .xor(match.range.first.toLong())
                    val rng = Random(seed)
                    items[rng.nextInt(items.size)]
                }
            },
        ),

        // {{// 注释}}: postEnv 阶段吞掉,与酒馆 macros.js:659 一致。
        // 这样如果注释里嵌了占位符,前面的 env 阶段已经把它们替换过(虽然结果会被吞掉,但触发了
        // 必要的副作用,例如 random / pick 的 seed 推进)。
        Rule(
            name = "{{// 注释}}",
            regex = Regex("\\{\\{//[\\s\\S]*?\\}\\}", RegexOption.IGNORE_CASE),
            replace = { _, _ -> "" },
        ),
    )

    // ─── 工具 ────────────────────────────────────

    /**
     * 把 `{{random:...}}` / `{{pick:...}}` 的列表参数解析成项数组。
     *
     * 分隔符规则:
     * - 优先 `::`(双冒号):用 `split("::")`,**不 trim** 子项空白(对齐酒馆 macros.js:495-497)。
     * - 否则 `,`(逗号):支持 `\,` 转义出真实逗号。**手写状态机扫描**而不是用占位符替换 +
     *   `split(",")`,避免用户文本里恰好包含占位符魔法字符串(如 NUL)时被误还原。
     *
     * 子项最后会 `trim()`,与酒馆一致。
     */
    private fun parsePickList(listString: String): List<String> {
        if (listString.contains("::")) {
            return listString.split("::")
        }
        // 手写状态机:逐字符扫描;遇到 `\,` 拼真实逗号,遇到裸 `,` 切分。
        // 其他字符(包括其他 `\x`)一律按字面量拼,不做转义吞噬。
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < listString.length) {
            val c = listString[i]
            if (c == '\\' && i + 1 < listString.length && listString[i + 1] == ',') {
                current.append(',')
                i += 2
                continue
            }
            if (c == ',') {
                items.add(current.toString().trim())
                current.setLength(0)
                i++
                continue
            }
            current.append(c)
            i++
        }
        items.add(current.toString().trim())
        return items
    }

    private fun humanizeDuration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 5 -> "just now"
            seconds < 60 -> "$seconds seconds ago"
            seconds < 3600 -> "${seconds / 60} minutes ago"
            seconds < 86400 -> "${seconds / 3600} hours ago"
            else -> "${seconds / 86400} days ago"
        }
    }

    /**
     * 解析 droll 风格的 dice formula 并返回点数。
     * 支持 "Nd6" / "1d20" / "2d4+3" / "3d6-1";只给数字 N 视为 "1dN"。
     * 解析失败返回 null,调用方应保留原占位符。
     *
     * 上限保护:count 和 sides 都不允许超过 100 万,避免恶意 formula 卡死。
     */
    private fun rollDice(formula: String, rng: Random): String? {
        val normalized = if (formula.all { it.isDigit() }) "1d$formula" else formula
        val match = Regex("^(\\d+)d(\\d+)([+-]\\d+)?$").matchEntire(normalized) ?: return null
        val count = match.groupValues[1].toIntOrNull() ?: return null
        val sides = match.groupValues[2].toIntOrNull() ?: return null
        if (count <= 0 || sides <= 0) return null
        if (count > MAX_DICE_COUNT || sides > MAX_DICE_SIDES) return null
        val modifier = match.groupValues[3].toIntOrNull() ?: 0
        var total = 0
        repeat(count) { total += rng.nextInt(sides) + 1 }
        return (total + modifier).toString()
    }

    companion object {
        /** env 阶段最大迭代次数。10 已足够覆盖正常嵌套深度,防自引用循环。 */
        private const val MAX_ENV_ITERATIONS = 10

        /** dice formula 的安全上限,避免 1000000d6 卡死。 */
        private const val MAX_DICE_COUNT = 1_000_000
        private const val MAX_DICE_SIDES = 1_000_000

        /**
         * 不依赖上下文的 preEnv 规则。
         *
         * 顺序:
         * - 旧标签 `<USER>` `<BOT>` `<CHAR>` 先于花括号语法替换。
         *   原因:某些卡片同时存在两种语法,旧标签先变成具体值,后续 regex 不会再误匹配。
         * - `{{newline}}` / `{{noop}}` / `{{trim}}` 是控制类,提前到 preEnv 防止后续 regex 误命中。
         *
         * 不在这里:
         * - `{{// 注释}}` 在 postEnv,理由见对应 Rule 注释。
         * - `{{roll}}` 在 instance 层(需要 randomSource)。
         */
        private val PRE_ENV_RULES: List<Rule> = listOf(
            Rule(
                name = "<USER>",
                regex = Regex("<USER>", RegexOption.IGNORE_CASE),
                replace = { match, ctx -> ctx.user ?: match.value },
            ),
            Rule(
                name = "<BOT>",
                regex = Regex("<BOT>", RegexOption.IGNORE_CASE),
                replace = { match, ctx -> ctx.char ?: match.value },
            ),
            Rule(
                name = "<CHAR>",
                regex = Regex("<CHAR>", RegexOption.IGNORE_CASE),
                replace = { match, ctx -> ctx.char ?: match.value },
            ),
            Rule(
                name = "<GROUP>",
                regex = Regex("<GROUP>", RegexOption.IGNORE_CASE),
                replace = { match, ctx -> ctx.group ?: match.value },
            ),
            Rule(
                name = "{{newline}}",
                regex = Regex("\\{\\{newline\\}\\}", RegexOption.IGNORE_CASE),
                replace = { _, _ -> "\n" },
            ),
            Rule(
                name = "{{trim}}",
                regex = Regex("(?:\\r?\\n)*\\{\\{trim\\}\\}(?:\\r?\\n)*", RegexOption.IGNORE_CASE),
                replace = { _, _ -> "" },
            ),
            Rule(
                name = "{{noop}}",
                regex = Regex("\\{\\{noop\\}\\}", RegexOption.IGNORE_CASE),
                replace = { _, _ -> "" },
            ),
        )
    }
}

// ─── 内部规则结构 ─────────────────────────────────

/**
 * 一条占位符规则。
 *
 * - [name]:可读名,失败回调里能立刻看清是哪条规则崩了。
 * - [regex]:匹配模式。
 * - [replace]:命中后的替换函数,返回 match.value 等价于"保留占位符不变"。
 */
internal class Rule(
    val name: String,
    val regex: Regex,
    val replace: (MatchResult, PlaceholderContext) -> String,
) {
    /**
     * 应用本规则;失败时调用 [errorHandler] 上报并跳过该条规则,不让整段替换崩溃。
     *
     * [valueTransform] 仅作用于"实际产生替换"的命中(替换结果与原 match.value 不同)。
     * "未命中而保留原占位符"的场景下,transform 不会被调用,避免把 `{{user}}` 字面量也 sanitize。
     */
    fun applySafely(
        input: String,
        context: PlaceholderContext,
        errorHandler: (ruleName: String, error: Throwable) -> Unit,
        valueTransform: ((String) -> String)? = null,
    ): String {
        return try {
            regex.replace(input) { match ->
                val replaced = replace(match, context)
                if (valueTransform != null && replaced != match.value) valueTransform(replaced)
                else replaced
            }
        } catch (e: Exception) {
            errorHandler(name, e)
            input
        }
    }

    companion object {
        /**
         * 构造一条"简单字段"规则:正则 `\{\{<name>\}\}` -> 调 valueProvider 取替换值。
         * valueProvider 返回 null 时保留原占位符(see PlaceholderResolver "找不到值的兜底" 节)。
         */
        fun field(name: String, valueProvider: (PlaceholderContext) -> String?): Rule = Rule(
            name = "{{$name}}",
            regex = Regex("\\{\\{${Regex.escape(name)}\\}\\}", RegexOption.IGNORE_CASE),
            replace = { match, ctx -> valueProvider(ctx) ?: match.value },
        )
    }
}
