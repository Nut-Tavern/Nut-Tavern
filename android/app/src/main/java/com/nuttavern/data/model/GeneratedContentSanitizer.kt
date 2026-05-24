package com.nuttavern.data.model

data class ReasoningSplit(
    val answerContent: String,
    val reasoningContent: String,
)

object GeneratedContentSanitizer {
    private val thinkTagRegex = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.IGNORE_CASE)
    private val closingThinkTagRegex = Regex("</think>", RegexOption.IGNORE_CASE)

    fun sanitizeProviderTextField(value: String): String {
        return if (isMeaninglessNullText(value)) "" else value
    }

    fun sanitizeGeneratedDisplayText(value: String): String {
        // 渲染层只能去**末尾**的空白:模型流式吐出来的中间帧经常以 `\n` / `\n\n` 结尾,
        // 这是 markdown 段落分隔的天然信号,trim 双边会吞掉前导段落分隔,导致代码块 /
        // 列表 / 标题等结构在流式过程中视觉错乱。前导空白对人类几乎无感,这里也保留。
        return if (isMeaninglessNullText(value)) "" else value.trimEnd()
    }

    fun splitReasoningFromAnswer(content: String): ReasoningSplit {
        if (content.isBlank()) return ReasoningSplit(answerContent = "", reasoningContent = "")

        val reasoningMatches = thinkTagRegex.findAll(content).toList()
        if (reasoningMatches.isEmpty()) {
            return ReasoningSplit(answerContent = content, reasoningContent = "")
        }

        val reasoningContent = reasoningMatches
            .joinToString(separator = "\n\n") { match -> match.groupValues.getOrNull(1).orEmpty().trim() }
        val answerContent = content
            .replace(thinkTagRegex, "")
            .replace(closingThinkTagRegex, "")

        return ReasoningSplit(
            answerContent = answerContent,
            reasoningContent = reasoningContent,
        )
    }

    private fun isMeaninglessNullText(value: String): Boolean {
        return value.trim().equals("null", ignoreCase = true)
    }
}
