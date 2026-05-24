package com.nuttavern.data.regex

/**
 * 正则脚本"执行时机"的 UI 派生枚举。
 *
 * 底层 [RegexScript] 把"何时跑"拆成三个独立布尔字段(对齐酒馆 engine.js):
 * - [RegexScript.markdownOnly] — 仅在 markdown 渲染阶段生效(对齐酒馆 Only Format Display)
 * - [RegexScript.promptOnly] — 仅在 prompt 拼接阶段生效(对齐酒馆 Only Format Prompt)
 * - [RegexScript.runOnEdit] — 编辑已发出的消息时是否重跑(对齐酒馆 Run On Edit)
 *
 * 三字段两两独立组合 = **2³ = 8 种**。本枚举 1:1 映射 8 种,数据层完全 round-trip 兼容
 * 酒馆任意组合,不做"非典型组合夹取"(早期 5 档夹取会让首次编辑导入的酒馆脚本静默丢字段)。
 *
 * **数据层完全不感知本枚举** — 仅用于 UI 表单展示。落库的 [RegexScript] 永远直接存三个独立字段。
 *
 * | 选项 | markdownOnly | promptOnly | runOnEdit | 语义 |
 * |---|---|---|---|---|
 * | [AFTER_GENERATION] | false | false | false | 接收时:永久改写聊天文件 |
 * | [AFTER_GENERATION_AND_EDIT] | false | false | true | 接收 + 编辑时都跑(永久) |
 * | [DISPLAY_ONLY] | true | false | false | 仅显示时(短暂,不动聊天文件) |
 * | [PROMPT_ONLY] | false | true | false | 仅发送时(短暂,不动聊天文件) |
 * | [DISPLAY_AND_PROMPT] | true | true | false | 显示 + 发送时(短暂) |
 * | [DISPLAY_AND_EDIT] | true | false | true | 仅显示 + 编辑时也重跑 |
 * | [PROMPT_AND_EDIT] | false | true | true | 仅发送 + 编辑时也重跑 |
 * | [DISPLAY_PROMPT_AND_EDIT] | true | true | true | 显示 + 发送 + 编辑全开 |
 */
enum class RegexExecutionTiming(
    val markdownOnly: Boolean,
    val promptOnly: Boolean,
    val runOnEdit: Boolean,
) {
    AFTER_GENERATION(markdownOnly = false, promptOnly = false, runOnEdit = false),
    AFTER_GENERATION_AND_EDIT(markdownOnly = false, promptOnly = false, runOnEdit = true),
    DISPLAY_ONLY(markdownOnly = true, promptOnly = false, runOnEdit = false),
    PROMPT_ONLY(markdownOnly = false, promptOnly = true, runOnEdit = false),
    DISPLAY_AND_PROMPT(markdownOnly = true, promptOnly = true, runOnEdit = false),
    DISPLAY_AND_EDIT(markdownOnly = true, promptOnly = false, runOnEdit = true),
    PROMPT_AND_EDIT(markdownOnly = false, promptOnly = true, runOnEdit = true),
    DISPLAY_PROMPT_AND_EDIT(markdownOnly = true, promptOnly = true, runOnEdit = true),
    ;

    /**
     * 把当前选项对应的三字段写回到 [script]。其它字段不变。
     */
    fun applyTo(script: RegexScript): RegexScript = script.copy(
        markdownOnly = markdownOnly,
        promptOnly = promptOnly,
        runOnEdit = runOnEdit,
    )

    companion object {
        /**
         * 从脚本三字段精确映射到 8 个 enum 中的一个。**不夹取、不归一**:任意三字段组合
         * 都对应唯一 enum,确保 round-trip 不丢字段。
         */
        fun from(script: RegexScript): RegexExecutionTiming = entries.first { option ->
            option.markdownOnly == script.markdownOnly &&
                option.promptOnly == script.promptOnly &&
                option.runOnEdit == script.runOnEdit
        }
    }
}
