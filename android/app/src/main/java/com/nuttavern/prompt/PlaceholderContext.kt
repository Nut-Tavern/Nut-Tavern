package com.nuttavern.prompt

/**
 * 占位符替换的运行时上下文。
 *
 * 设计取舍:
 * - 用 data class 集中持有所有可替换变量,而不是用 Map<String, Any>。理由:
 *   - 类型清晰,IDE 可跳转;
 *   - 后续加新字段时编译期就能发现遗漏的调用方;
 *   - 占位符 -> 字段的映射在 PlaceholderResolver 里集中维护,Context 本身只是数据袋。
 * - 所有字段都可空。占位符找不到对应值时,渲染保持占位符原样(对齐酒馆 helperMissing 行为)。
 * - chatStats 是统计类信息(最近消息、消息 id 等),独立成嵌套结构,便于"无会话"场景传 null。
 *
 * 不在 Context 里放的:
 * - 用户输入框文本({{input}}):我们没有"输入框"概念,需要时单独走变量类入口;
 * - 模型 token 预算({{maxPrompt}} 等):ChatApiClient 不暴露这些,MVP 不接;
 * - 变量({{getvar}} 等):走独立 VariablesResolver,等后续轮再做。
 */
data class PlaceholderContext(
    val user: String? = null,
    val char: String? = null,
    val group: String? = null,
    val description: String? = null,
    val personality: String? = null,
    val scenario: String? = null,
    val persona: String? = null,
    val charPrompt: String? = null,
    val charJailbreak: String? = null,
    /**
     * 解析后的角色 mes_example,已经按 `<START>` 切成示范消息文本块(便于直接拼入 prompt)。
     * 通常等于 PromptComposer 在"例子对话注入"节点产出的字符串。
     */
    val mesExamples: String? = null,
    /**
     * **未解析**的 mes_example 原文,保留 `<START>` / 角色名前缀等所有原始分隔符。
     * 给那些自己用 prompt 模板控制例子格式的用户用,与 [mesExamples] 互不替代。
     * 对齐酒馆 environment.mesExamplesRaw(public/script.js:2882)。
     */
    val mesExamplesRaw: String? = null,
    val charVersion: String? = null,
    val charDepthPrompt: String? = null,
    val creatorNotes: String? = null,
    val chatStats: ChatStats? = null,
)

/**
 * 历史 / 统计类占位符的数据来源。
 *
 * 这些字段的语义对齐酒馆 macros.js 里的 getLastMessage / getLastUserMessage 等。
 * 仅在有当前会话时填充,无会话或会话为空时整个对象传 null。
 */
data class ChatStats(
    val lastMessage: String? = null,
    val lastUserMessage: String? = null,
    val lastCharMessage: String? = null,
    val lastMessageId: Int? = null,
    val firstIncludedMessageId: Int? = null,
    val lastSwipeId: Int? = null,
    val currentSwipeId: Int? = null,
    val totalMessageCount: Int = 0,
    /**
     * 最近一条用户消息距现在的毫秒数。null 表示没有用户消息。
     * 在替换 `{{idle_duration}}` 时格式化成可读时长。
     */
    val idleDurationMillis: Long? = null,
    /**
     * `{{pick:...}}` 用的稳定种子。语义对齐酒馆:`chatIdHash + contentHash + offset`。
     * Pickresolver 内部基于这个种子构造伪随机,保证同一会话同一文本里的 pick 一直选同一项。
     */
    val pickSeed: Long = 0L,
)
