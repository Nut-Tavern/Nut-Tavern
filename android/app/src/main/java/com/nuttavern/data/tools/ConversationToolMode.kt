package com.nuttavern.data.tools

/**
 * 会话级内置工具开关模式。
 *
 * - [FOLLOW_GLOBAL]:旧版存储值,表示会话创建时未固化开关;新会话不再写入这个值;
 * - [FORCE_ON]:本会话启用工具;
 * - [FORCE_OFF]:本会话关闭工具。
 *
 * 持久化用 [storageValue] 字符串落库([com.nuttavern.data.local.entity.ConversationEntity.toolMode]),
 * 解析失败兜底 [FOLLOW_GLOBAL],运行时按旧会话兼容启用处理。
 */
enum class ConversationToolMode(val storageValue: String) {
    FOLLOW_GLOBAL("follow_global"),
    FORCE_ON("force_on"),
    FORCE_OFF("force_off"),
    ;

    companion object {
        fun fromStorage(value: String?): ConversationToolMode =
            entries.firstOrNull { it.storageValue == value } ?: FOLLOW_GLOBAL
    }
}

/**
 * 判定当前会话是否应携带内置工具。
 *
 * [FOLLOW_GLOBAL] 只作为旧数据兼容:旧版默认开关为启用,因此这里按启用处理。新 UI 使用会话级
 * enabled tool ids 控制具体工具是否可用,这个枚举只保留给旧数据与空会话占位状态。
 */
fun ConversationToolMode.resolveToolsEnabled(): Boolean = when (this) {
    ConversationToolMode.FOLLOW_GLOBAL -> true
    ConversationToolMode.FORCE_ON -> true
    ConversationToolMode.FORCE_OFF -> false
}
