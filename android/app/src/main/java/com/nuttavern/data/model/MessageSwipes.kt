package com.nuttavern.data.model

/**
 * swipe 候选纯函数:重新生成 / 切换候选时的列表运算,从 ViewModel / Repository 里抽出来便于单测。
 *
 * 数据模型对齐酒馆 `mes + swipes + swipe_id`:
 * - [Message.parts] 永远是"当前显示候选"(所有现有渲染 / 历史 / 正则链路只读它);
 * - [Message.swipes] 是全部候选,空 = 只有一个版本(不展示切换);
 * - [Message.swipeIndex] 指向 [Message.swipes] 里当前选中的候选。
 */
object MessageSwipes {

    /**
     * 把"新生成的候选"并入消息:旧版本作为已有候选保留,新候选追加到末尾并选中。
     *
     * - 消息原本无 swipe(swipes 为空):用当前 [Message.parts] 作为第 0 个候选,新回复作为第 1 个,
     *   选中新回复。这样旧回复不丢,用户可切回。
     * - 消息已有 swipe:保留全部已有候选,新回复追加为最后一个并选中。
     *
     * @param newParts 新生成回复的 parts。
     * @return 并入后的消息(parts 同步为新候选,swipeIndex 指向新候选)。
     */
    fun appendRegeneratedCandidate(message: Message, newParts: List<MessagePart>): Message {
        val existingCandidates = if (message.swipes.isEmpty()) {
            listOf(message.parts)
        } else {
            message.swipes
        }
        val nextCandidates = existingCandidates + listOf(newParts)
        return message.copy(
            parts = newParts,
            swipes = nextCandidates,
            swipeIndex = nextCandidates.lastIndex,
        )
    }

    /**
     * 切换到指定索引的候选。索引越界或与当前一致时返回原消息(调用方据此判断是否需要落库)。
     */
    fun selectCandidate(message: Message, targetIndex: Int): Message {
        if (message.swipes.size <= 1) return message
        if (targetIndex !in message.swipes.indices) return message
        if (targetIndex == message.swipeIndex) return message
        return message.copy(
            parts = message.swipes[targetIndex],
            swipeIndex = targetIndex,
        )
    }
}
