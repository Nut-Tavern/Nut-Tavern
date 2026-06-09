package com.nuttavern.data.tools

/**
 * 会话级内置工具开关模式。
 *
 * - [FOLLOW_GLOBAL]:跟随全局默认开关 [LocalToolsSettings.defaultEnabled];
 * - [FORCE_ON]:本会话强制启用工具(忽略全局默认);
 * - [FORCE_OFF]:本会话强制关闭工具(忽略全局默认)。
 *
 * 持久化用 [storageValue] 字符串落库([com.nuttavern.data.local.entity.ConversationEntity.toolMode]),
 * 解析失败兜底 [FOLLOW_GLOBAL]。
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
 * 结合全局默认与会话模式,判定当前会话是否应携带内置工具。
 *
 * @param mode 会话模式
 * @param globalDefaultEnabled 全局默认开关
 */
fun ConversationToolMode.resolveToolsEnabled(globalDefaultEnabled: Boolean): Boolean = when (this) {
    ConversationToolMode.FOLLOW_GLOBAL -> globalDefaultEnabled
    ConversationToolMode.FORCE_ON -> true
    ConversationToolMode.FORCE_OFF -> false
}
