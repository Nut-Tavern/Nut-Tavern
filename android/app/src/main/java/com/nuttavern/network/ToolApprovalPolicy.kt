package com.nuttavern.network

/**
 * 计算一次工具调用是否需要人工确认。
 *
 * 工具自身声明的 [toolRequiresApproval] 是安全下限,不能被用户设置关闭;[userRequiresApproval]
 * 只能给低风险工具额外加确认。因此两者取 OR。
 */
fun shouldRequireToolApproval(
    toolRequiresApproval: Boolean,
    userRequiresApproval: Boolean,
): Boolean = toolRequiresApproval || userRequiresApproval
