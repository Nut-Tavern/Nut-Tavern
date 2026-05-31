package com.nuttavern.regex

import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScope
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.data.regex.SubstituteRegex
import com.nuttavern.prompt.PlaceholderContext
import com.nuttavern.prompt.PlaceholderResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 正则脚本运行时引擎。**对齐酒馆 `extensions/regex/engine.js`** 的 `getRegexedString` /
 * `runRegexScript`。
 *
 * # 三场景门控(Ephemerality)
 *
 * 酒馆有 3 个独立调用场景,通过 [RegexScript.markdownOnly] / [RegexScript.promptOnly] +
 * 调用方传入的 [isMarkdown] / [isPrompt] 门控:
 *
 * | 场景 | isMarkdown | isPrompt | 跑哪些脚本 |
 * |---|---|---|---|
 * | 改聊天文件(默认/永久) | false | false | `markdownOnly=false && promptOnly=false` |
 * | 仅 markdown 渲染(短暂) | true | false | `markdownOnly=true` |
 * | 仅 prompt 拼接(短暂) | false | true | `promptOnly=true` |
 *
 * 一个脚本两个 only 都不勾 = 永久改文件(发到聊天历史里);勾任意 only = 短暂,只影响该场景。
 * 这与酒馆 UI 上的 "Ephemerality" 分组语义一致。
 *
 * 本仓库当前接入点:
 *
 * - [com.nuttavern.prompt.PromptComposer] 处理用户输入(USER_INPUT 阶段):传 `isPrompt=true`,
 *   只跑 `promptOnly=true` 的脚本(短暂改 prompt,不动用户输入文件);
 * - [com.nuttavern.ui.viewmodel.ChatViewModel.applyAiOutputRegex] 落库前处理 AI 回复:两个都 false,
 *   只跑 `markdownOnly=false && promptOnly=false` 的脚本(永久改聊天文件)。
 *
 * 我们没有"markdown 渲染"独立阶段(Compose UI 实时渲染,不再跑正则)。如果未来加这个阶段,
 * 调用方传 `isMarkdown=true` 即可,引擎逻辑无需改。
 *
 * # JS regex flag 兼容
 *
 * 酒馆脚本的 [RegexScript.findRegex] 用 JS 风格 `/pattern/flags` 字面量。本引擎解析 flags 并
 * 映射到 [RegexOption]:
 *
 * | JS flag | 处理 |
 * |---|---|
 * | `g` | Kotlin 由 [Regex.replace] 默认替换全部,解析后丢弃 |
 * | `i` | [RegexOption.IGNORE_CASE] |
 * | `s` | [RegexOption.DOT_MATCHES_ALL] |
 * | `m` | [RegexOption.MULTILINE] |
 * | `u` | Kotlin 默认 Unicode,丢弃 |
 * | 其它 | 静默忽略(包括 `y` sticky 模式) |
 *
 * 不抛异常:flag 解析失败 / 模式编译失败时,该脚本静默跳过,与酒馆一致。
 *
 * # SubstituteRegex(作用在 **Find Regex**,不是 Replace)
 *
 * 酒馆 `substitute_find_regex`(`engine.js` 397-409 行)作用于 **findRegex**,不是 replaceString。
 * Replace 里的 `{{user}}` 等占位符**始终在最后做替换**,不受本字段控制。
 *
 * - [SubstituteRegex.NONE]:findRegex 原样使用;
 * - [SubstituteRegex.RAW]:findRegex 先做占位符替换(`{{user}}` 等),不做正则元字符转义;
 * - [SubstituteRegex.ESCAPED]:findRegex 先做占位符替换 + 正则元字符转义(`.` → `\.` 等),
 *   适合"我想匹配字面 user 输入值"的场景,避免名字里的特殊字符破坏 pattern。
 *
 * # 替换串引用语法

 * 替换逻辑**逐次匹配手动解析**,对齐酒馆 engine.js 第 419-445 行,不走 Kotlin Regex.replace
 * 的反向引用(后者把 `$` 当特殊字符,字面 `$` 与越界组引用会抛异常,且 trimStrings 无法作用到
 * 单个组值)。支持的引用:
 *
 * | 酒馆语法 | 行为 |
 * |---|---|
 * | `{{match}}` | 先归一成 `$0`,取整个匹配 |
 * | `$0` | 整个匹配 |
 * | `$1` `$2` ... | 对应数字捕获组;组不存在 → 空串 |
 * | `$<name>` | 命名捕获组;无匹配 → 空串 |
 *
 * 每个取出的组值各自做 trimStrings 删除(作用在组值上,不是整段输入);组替换完成后再做
 * [PlaceholderResolver] 替换(对应酒馆 engine.js 第 444 行 `substituteParams`)。
 *
 * # 已知偏差(与酒馆相比)
 *
 * - JS sticky `y` flag 不实现:Kotlin Regex 不支持。
 * - 不实现 placeholder MD_DISPLAY = 0(已 deprecated)。
 * - 不实现 SLASH_COMMAND placement = 3:本仓库无 slash command 概念。
 */
@Singleton
class RegexEngine @Inject constructor(
    private val placeholderResolver: PlaceholderResolver,
) {

    /**
     * 按指定阶段([RegexPlacement])对文本执行所有匹配的正则脚本。
     *
     * 执行顺序:GLOBAL → SCOPED → PRESET,同一作用域内按列表顺序串行。每条脚本的输出作为下一条
     * 的输入。
     *
     * @param raw 待处理原文。
     * @param placement 当前阶段(USER_INPUT / AI_OUTPUT / WORLD_INFO / REASONING)。
     * @param globalScripts 全局正则。
     * @param scopedScripts 角色卡内嵌正则(可空)。
     * @param presetScripts 预设内嵌正则(可空)。
     * @param characterAllowed 用户级总开关:是否允许 SCOPED 脚本执行(对齐酒馆 character_allowed_regex)。
     * @param presetAllowed 用户级总开关:是否允许 PRESET 脚本执行(对齐酒馆 preset_allowed_regex)。
     * @param isMarkdown 当前调用是否在 markdown 渲染阶段(短暂场景)。
     * @param isPrompt 当前调用是否在 prompt 拼接阶段(短暂场景)。
     * @param isEdit 当前调用是否在编辑消息(影响 runOnEdit 过滤)。
     * @param depth 当前消息距离最末尾的距离(0 = 最新一条)。null = 不参与 minDepth/maxDepth 过滤。
     * @param placeholderContext 占位符上下文,供 SubstituteRegex.RAW / ESCAPED + 替换串占位符使用。
     */
    fun getRegexedString(
        raw: String,
        placement: RegexPlacement,
        globalScripts: List<RegexScript> = emptyList(),
        scopedScripts: List<RegexScript> = emptyList(),
        presetScripts: List<RegexScript> = emptyList(),
        characterAllowed: Boolean = true,
        presetAllowed: Boolean = true,
        isMarkdown: Boolean = false,
        isPrompt: Boolean = false,
        isEdit: Boolean = false,
        depth: Int? = null,
        placeholderContext: PlaceholderContext = PlaceholderContext(),
    ): String {
        if (raw.isEmpty()) return raw

        val ordered = buildList {
            globalScripts.forEach { add(it to RegexScope.GLOBAL) }
            if (characterAllowed) scopedScripts.forEach { add(it to RegexScope.SCOPED) }
            if (presetAllowed) presetScripts.forEach { add(it to RegexScope.PRESET) }
        }

        return ordered.fold(raw) { acc, (script, _) ->
            if (!isApplicable(script, placement, isMarkdown, isPrompt, isEdit, depth)) acc
            else runRegexScript(acc, script, placeholderContext)
        }
    }

    /**
     * 单条脚本执行。匹配失败 / 模式非法 / 引用越界都静默返回原文,不抛异常。
     *
     * 替换逻辑对齐酒馆 engine.js 第 418-445 行:**逐次匹配,手动解析引用**,而非把整段
     * 替换串交给 Kotlin Regex.replace 处理。原因见 [resolveReplacement]。
     */
    fun runRegexScript(
        raw: String,
        script: RegexScript,
        placeholderContext: PlaceholderContext = PlaceholderContext(),
    ): String {
        val substituteMode = SubstituteRegex.fromValue(script.substituteRegex)
        val processedFindRegex = prepareFindRegex(script.findRegex, substituteMode, placeholderContext)
        val (pattern, flags) = parseFindRegex(processedFindRegex) ?: return raw
        val options = mapJsFlagsToOptions(flags)
        val regex = runCatching { Regex(pattern, options) }.getOrNull() ?: return raw

        return runCatching {
            regex.replace(raw) { matchResult ->
                resolveReplacement(matchResult, script, placeholderContext)
            }
        }.getOrDefault(raw)
    }

    /**
     * 计算单次匹配的替换文本,对齐酒馆 engine.js 第 419-445 行。
     *
     * 流程:
     * 1. `{{match}}` → `$0`(整匹配);
     * 2. 用 `\$(\d+)|\$<([^>]+)>` 逐个解析引用,从 [matchResult] 取对应组值;
     *    引用不存在(组越界 / 命名组无匹配)→ 替换成空串(酒馆 engine.js 第 433-435 行);
     * 3. 每个取出的组值各自做 trimStrings 删除(作用在**组值**上,不是整段输入,
     *    对齐酒馆 filterString,engine.js 第 438 行);
     * 4. 整串组替换完成后做占位符替换(酒馆 substituteParams,engine.js 第 444 行)。
     *
     * # 为什么不用 Kotlin Regex.replace 的反向引用
     *
     * Kotlin/Java 替换串把 `$` 当特殊字符,字面 `$` 或越界组引用会抛 IllegalArgumentException;
     * trimStrings 也无法作用到单个组值。手动解析才能 1:1 复刻酒馆行为。
     *
     * # 与酒馆的有意差异
     *
     * 数字组越界严格按真实组数兜底(`groupValues.getOrNull` → 空串),不复刻酒馆 `args[num]`
     * 在越界时可能命中 JS replace 回调尾部参数(offset / string / groups)的旧行为。
     * "越界 → 空串"更符合用户直觉,且测试已锁(missingNumberedGroupReferenceBecomesEmpty)。
     */
    private fun resolveReplacement(
        matchResult: MatchResult,
        script: RegexScript,
        placeholderContext: PlaceholderContext,
    ): String {
        // 用 lambda 形式回填字面 "$0":两参 replace 会把替换串里的 $0 当成反向引用,
        // 这里要的是字面文本 $0 以供下游 GROUP_REFERENCE_REGEX 当"整匹配"解析。
        val withMatchMacro = MATCH_MACRO_REGEX.replace(script.replaceString) { "\$0" }
        val withGroups = GROUP_REFERENCE_REGEX.replace(withMatchMacro) { ref ->
            val numbered = ref.groupValues[1]
            val named = ref.groupValues[2]
            val groupValue = when {
                numbered.isNotEmpty() -> matchResult.groupValues.getOrNull(numbered.toInt())
                // 命名组不存在时 Kotlin groups[name] 抛 IllegalArgumentException,而非返回 null。
                named.isNotEmpty() -> runCatching { matchResult.groups[named]?.value }.getOrNull()
                else -> null
            }
            if (groupValue.isNullOrEmpty()) "" else filterTrimStrings(groupValue, script.trimStrings, placeholderContext)
        }
        return placeholderResolver.resolve(withGroups, placeholderContext)
    }

    /**
     * 从组值里删除 trimStrings 片段。每个 trimString 先做占位符替换,再整体删除。
     * 对齐酒馆 filterString(engine.js 第 457-464 行)。
     */
    private fun filterTrimStrings(
        value: String,
        trimStrings: List<String>,
        placeholderContext: PlaceholderContext,
    ): String {
        if (trimStrings.isEmpty()) return value
        return trimStrings.fold(value) { acc, fragment ->
            if (fragment.isEmpty()) return@fold acc
            val resolved = placeholderResolver.resolve(fragment, placeholderContext)
            if (resolved.isEmpty()) acc else acc.replace(resolved, "")
        }
    }

    /**
     * 三场景门控,**对齐酒馆 engine.js 第 348-355 行**:
     *
     * - `markdownOnly=true` 的脚本 → 只在 `isMarkdown=true` 时跑;
     * - `promptOnly=true` 的脚本 → 只在 `isPrompt=true` 时跑;
     * - 两个 only 都 false 的脚本 → 只在 `isMarkdown=false && isPrompt=false` 时跑(改文件场景)。
     *
     * 不存在"既改文件又只改 prompt"的脚本 — 三个互斥分支按 OR 组合,任一命中即跑。
     */
    private fun isApplicable(
        script: RegexScript,
        placement: RegexPlacement,
        isMarkdown: Boolean,
        isPrompt: Boolean,
        isEdit: Boolean,
        depth: Int?,
    ): Boolean {
        if (script.disabled) return false
        if (placement.value !in script.placement) return false

        val ephemeralityMatch =
            (script.markdownOnly && isMarkdown) ||
            (script.promptOnly && isPrompt) ||
            (!script.markdownOnly && !script.promptOnly && !isMarkdown && !isPrompt)
        if (!ephemeralityMatch) return false

        if (!script.runOnEdit && isEdit) return false
        if (depth != null) {
            val min = script.minDepth
            val max = script.maxDepth
            if (min != null && min >= -1 && depth < min) return false
            if (max != null && max >= 0 && depth > max) return false
        }
        return true
    }

    /**
     * 解析 `/pattern/flags` 字面量。容忍裸正则(无包裹斜杠)— 此时整串作为 pattern,flags 为空。
     */
    internal fun parseFindRegex(raw: String): Pair<String, String>? {
        if (raw.isEmpty()) return null
        // 完整 /pattern/flags 字面量。注意末尾 / 之后只能是 flag 字母。
        val literalRegex = Regex("^/(.+)/([gimsuy]*)$", RegexOption.DOT_MATCHES_ALL)
        val match = literalRegex.matchEntire(raw)
        return if (match != null) {
            match.groupValues[1] to match.groupValues[2]
        } else {
            raw to ""
        }
    }

    private fun mapJsFlagsToOptions(flags: String): Set<RegexOption> {
        val options = mutableSetOf<RegexOption>()
        flags.forEach { flag ->
            when (flag) {
                'i' -> options += RegexOption.IGNORE_CASE
                's' -> options += RegexOption.DOT_MATCHES_ALL
                'm' -> options += RegexOption.MULTILINE
                // g / u / y:Kotlin 默认行为或不支持,静默忽略。
                else -> Unit
            }
        }
        return options
    }

    /**
     * Find Regex 预处理。对齐酒馆 engine.js 第 397-409 行 `substituteRegex` 的作用点。
     *
     * - [SubstituteRegex.NONE]:findRegex 原样返回;
     * - [SubstituteRegex.RAW]:findRegex 中的 `{{user}}` 等占位符替换为对应值,**不**做转义;
     * - [SubstituteRegex.ESCAPED]:占位符替换 + **仅对替换进去的占位符值**做正则元字符转义。
     *   findRegex 字面量里用户写的 `()` `.` `*` 等正则元素**保留不转义**,与酒馆
     *   `substituteParamsExtended(..., sanitizeRegexMacro)` 行为一致。
     *
     * # 为什么 ESCAPED 不整串转义
     *
     * 早期实现把 resolver 输出整串再过 sanitize,语义错误:
     * 用户写 `/{{user}}.*test/` + ESCAPED + user="Alice.Bob" 期望匹配的 pattern 是
     * `Alice\.Bob.*test`(只转 user 值里的 `.`,`.* test` 保留)。整串转义会把 `.* ` 一起转成
     * `\.\*test`,与酒馆 round-trip 的脚本行为不一致。
     *
     * # singlePass 必传
     *
     * sanitize 不幂等(对已转义的 `\.` 再 sanitize 会变 `\\\.`)。env 多轮迭代会让嵌套占位符
     * 重复 sanitize,因此 ESCAPED 必须走单轮模式,与酒馆 substituteParamsExtended 行为对齐。
     */
    private fun prepareFindRegex(
        findRegex: String,
        mode: SubstituteRegex,
        placeholderContext: PlaceholderContext,
    ): String {
        return when (mode) {
            SubstituteRegex.NONE -> findRegex
            SubstituteRegex.RAW -> placeholderResolver.resolve(findRegex, placeholderContext)
            SubstituteRegex.ESCAPED -> placeholderResolver.resolve(
                content = findRegex,
                context = placeholderContext,
                valueTransform = ::sanitizeRegexMacro,
                singlePass = true,
            )
        }
    }

    /**
     * 对齐酒馆 engine.js 第 304-324 行 `sanitizeRegexMacro`:转义可能破坏正则语法的字符。
     */
    private fun sanitizeRegexMacro(value: String): String {
        return buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u000B' -> append("\\v")
                    '\u000C' -> append("\\f")
                    '\u0000' -> append("\\0")
                    '.', '^', '$', '*', '+', '?', '{', '}', '[', ']', '\\', '/', '|', '(', ')' -> {
                        append('\\')
                        append(c)
                    }
                    else -> append(c)
                }
            }
        }
    }

    private companion object {
        /** `{{match}}`(忽略大小写)→ `$0`。对齐酒馆 engine.js 第 421 行 `/{{match}}/gi`。 */
        val MATCH_MACRO_REGEX = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE)

        /**
         * 替换串引用解析:`$1`(数字组)或 `$<name>`(命名组)。
         * 对齐酒馆 engine.js 第 422 行 `/\$(\d+)|\$<([^>]+)>/g`。
         */
        val GROUP_REFERENCE_REGEX = Regex("\\$(\\d+)|\\$<([^>]+)>")
    }
}
