package com.nuttavern.data.lorebook

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书编辑撤销栈(进程内内存态,不持久化)。
 *
 * 每次 `apply_lorebook_edits` 落库**前**,把目标世界书的整本条目快照压栈;`undo_lorebook_edits`
 * 弹栈恢复。按世界书 id 各自维护一个栈,最多保留最近 [MAX_HISTORY] 次,超出丢弃最旧的。
 *
 * 为什么用内存态而非持久化:回滚主要服务"刚改错想撤回"的即时场景,跨进程重启的撤销需求极少;
 * 持久化会显著增加世界书数据结构与迁移复杂度,不划算。重启后栈清空 = 不可再回滚历史编辑,可接受。
 *
 * 为什么存整书条目快照而非条目级 diff:世界书体积小,整书快照恢复不会因 uid 错位 / 并发编辑出错,
 * 比 diff 更稳、更可预测(对齐 docs/modules/lorebook-tools.md 的取舍)。
 *
 * 线程安全:用 [ConcurrentHashMap] + 每本书栈操作同步,工具执行在 IO 协程,可能并发。
 */
@Singleton
class LorebookEditHistory @Inject constructor() {

    private val stacksByLorebookId = ConcurrentHashMap<String, ArrayDeque<List<LorebookEntry>>>()

    /** 压入一次编辑前的整书条目快照。超过 [MAX_HISTORY] 时丢弃最旧。 */
    fun push(lorebookId: String, entriesSnapshot: List<LorebookEntry>) {
        val stack = stacksByLorebookId.getOrPut(lorebookId) { ArrayDeque() }
        synchronized(stack) {
            stack.addLast(entriesSnapshot)
            while (stack.size > MAX_HISTORY) {
                stack.removeFirst()
            }
        }
    }

    /** 弹出并返回最近一次快照;栈空返回 null。 */
    fun pop(lorebookId: String): List<LorebookEntry>? {
        val stack = stacksByLorebookId[lorebookId] ?: return null
        return synchronized(stack) {
            if (stack.isEmpty()) null else stack.removeLast()
        }
    }

    /** 当前某本书可回滚的步数。 */
    fun depth(lorebookId: String): Int {
        val stack = stacksByLorebookId[lorebookId] ?: return 0
        return synchronized(stack) { stack.size }
    }

    companion object {
        /** 每本书最多保留的撤销快照数。 */
        const val MAX_HISTORY = 8
    }
}
