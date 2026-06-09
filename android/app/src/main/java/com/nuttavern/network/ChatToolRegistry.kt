package com.nuttavern.network

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端内置工具注册表。
 *
 * 集中持有所有本地 function calling 工具的**定义**(不含启用状态)。启用状态由
 * [com.nuttavern.data.tools.LocalToolsRepository] 持久化,发送链路在 [com.nuttavern.ui.viewmodel.ChatViewModel]
 * 里按"全局默认 + 会话 toolMode + 单工具启用集"算出本次实际可用工具,再传进 streamChat。
 *
 * 新增工具:在这里 new 一个 [ChatTool] 加进 [tools]。execute 体保持纯粹(只算结果、不碰 UI),
 * 需要 Android 上下文的工具(剪贴板等)后续按需注入再加。
 */
@Singleton
class ChatToolRegistry @Inject constructor() {

    private val timeTool = ChatTool(
        id = "get_current_time",
        name = "get_current_time",
        displayName = "获取当前时间",
        description = "获取设备当前的本地日期和时间,包括年月日、星期、时区和时间戳。" +
            "当用户询问现在几点、今天日期、今天星期几等与当前时间相关的问题时调用。",
        parametersSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()),
        needsApproval = false,
        execute = {
            val now = ZonedDateTime.now()
            val date = now.toLocalDate()
            val time = now.toLocalTime().withNano(0)
            val weekday = now.dayOfWeek
            JSONObject()
                .put("date", date.toString())
                .put("time", time.toString())
                .put("datetime", now.withNano(0).toString())
                .put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                .put("timezone", now.zone.id)
                .put("utc_offset", now.offset.id)
                .put("timestamp_ms", now.toInstant().toEpochMilli())
                .toString()
        },
    )

    /** 当前注册的全部内置工具(定义层,不代表启用)。 */
    val tools: List<ChatTool> = listOf(timeTool)

    /** 按 [ChatTool.id] 取工具定义。 */
    fun toolById(id: String): ChatTool? = tools.firstOrNull { it.id == id }
}
