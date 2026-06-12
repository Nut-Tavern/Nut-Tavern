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
     * @param newParts 新生成回复的 parts。**禁止传空列表** —— 空候选会导致用户切回时看到空白
     *   消息(parts 同步为空 list);调用方必须先用 [buildAssistantParts] 等组装出至少一个 part
     *   才允许调本函数。空回复场景应走"不落库"路径,不要并入 swipe。
     * @return 并入后的消息(parts 同步为新候选,swipeIndex 指向新候选)。
     * @throws IllegalArgumentException 当 [newParts] 为空时。
     */
    fun appendRegeneratedCandidate(message: Message, newParts: List<MessagePart>): Message {
        require(newParts.isNotEmpty()) {
            "appendRegeneratedCandidate 不接受空 parts:空候选会让用户切回时看到空白消息," +
                "调用方应在空回复场景走不落库路径。"
        }
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

    /**
     * 删除当前选中的候选,并把 swipeIndex 顶上后一个(若已是末位则退到新末位)。
     * 对齐酒馆 deleteSwipe(script.js:9279):splice 后 `newSwipeId = min(swipeId, swipes.length - 1)`。
     *
     * 仅在 [Message.swipes].size >= 2 时调用;调用方负责先判断 [Message.hasMultipleSwipes],
     * size <= 1 应走"删整条消息"路径,而不是调本函数。这里传入不合法时返回原消息。
     *
     * @return 删除后的消息(parts 同步为新选中候选);若不满足前置条件返回原消息。
     */
    fun removeCurrentCandidate(message: Message): Message {
        if (message.swipes.size <= 1) return message
        val currentIndex = message.swipeIndex.coerceIn(0, message.swipes.lastIndex)
        val nextSwipes = message.swipes.toMutableList().apply { removeAt(currentIndex) }
        val nextIndex = minOf(currentIndex, nextSwipes.lastIndex)
        return message.copy(
            parts = nextSwipes[nextIndex],
            swipes = nextSwipes,
            swipeIndex = nextIndex,
        )
    }
}
