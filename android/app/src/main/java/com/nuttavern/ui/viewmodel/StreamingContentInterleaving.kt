package com.nuttavern.ui.viewmodel

/**
 * 流式回复落库时,把正文按"工具到达切点"切段,与工具调用交错排序的纯逻辑。
 *
 * 抽成顶层纯函数便于单测:只负责"切点 → 有序片段编排",不碰正则 / part 构造 / repository。
 * ChatViewModel 拿到 [ContentSlice] 序列后,对 [ContentSlice.Text] 跑 AI_OUTPUT 正则、
 * 对 [ContentSlice.Tool] 配上对应工具 part。
 */
internal sealed interface ContentSlice {
    /**
     * 一段正文。[raw] 是未经正则/裁剪的原文,[isTail] 标记是否为最后一个工具之后的尾段
     * (尾段做尾部空白裁剪,中间段保留原样,避免吞掉 markdown 段落分隔)。
     */
    data class Text(val raw: String, val isTail: Boolean) : ContentSlice

    /** 第 [toolIndex] 个工具调用(对应已按切点升序排好的工具列表下标)。 */
    data class Tool(val toolIndex: Int) : ContentSlice
}

/**
 * 按升序切点把 [answerContent] 切段,与工具交错:文字段 → 工具0 → 文字段 → 工具1 → ... → 尾段。
 *
 * @param offsets 第 i 个工具到达时已累积的正文长度,必须已按升序排列。越界值会被 clamp 到
 *   `[cursor, answerContent.length]`,保证切点单调不回退。
 *
 * 规则:
 * - 每个工具前的文字段(可能为空)先于该工具;空段不产出 [ContentSlice.Text];
 * - 最后一个工具之后的尾段标记 isTail=true;
 * - 无工具(offsets 空)时整段正文作为单个 isTail=true 的文字段。
 */
internal fun interleaveContentWithTools(
    answerContent: String,
    offsets: List<Int>,
): List<ContentSlice> {
    if (offsets.isEmpty()) {
        return if (answerContent.isEmpty()) emptyList()
        else listOf(ContentSlice.Text(answerContent, isTail = true))
    }

    val slices = mutableListOf<ContentSlice>()
    var cursor = 0
    offsets.forEachIndexed { toolIndex, rawOffset ->
        val cutPoint = rawOffset.coerceIn(cursor, answerContent.length)
        val segment = answerContent.substring(cursor, cutPoint)
        if (segment.isNotEmpty()) {
            slices += ContentSlice.Text(segment, isTail = false)
        }
        slices += ContentSlice.Tool(toolIndex)
        cursor = cutPoint
    }
    val tail = answerContent.substring(cursor)
    if (tail.isNotEmpty()) {
        slices += ContentSlice.Text(tail, isTail = true)
    }
    return slices
}
