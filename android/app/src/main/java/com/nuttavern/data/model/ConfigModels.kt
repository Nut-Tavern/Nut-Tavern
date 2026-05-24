package com.nuttavern.data.model

import com.google.gson.annotations.SerializedName

data class AssistantConfig(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("summary")
    val summary: String = "",
    @SerializedName("systemPrompt")
    val systemPrompt: String = "",
)

val defaultAssistants = listOf(
    AssistantConfig(
        "chat-assistant",
        "Chat Assistant",
        "General Q&A and daily chat",
        "You are a practical assistant. Keep answers clear, concise, and executable.",
    ),
    AssistantConfig(
        "writer-assistant",
        "Writer Assistant",
        "Drafting and rewriting",
        "You specialize in writing quality. Preserve intent first, then improve structure and tone.",
    ),
    AssistantConfig(
        "planner-assistant",
        "Planner Assistant",
        "Planning and task breakdown",
        "You break complex goals into clear steps with priorities, timing, and risk notes.",
    ),
)

data class SystemPromptConfig(
    @SerializedName("id")
    val id: String,
    @SerializedName("title")
    val title: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("prompt")
    val prompt: String,
)

val defaultSystemPrompts = listOf(
    SystemPromptConfig(
        "conversation-title",
        "会话标题提示词",
        "用于后续根据首轮上下文自动生成会话标题。",
        "请用 12 个字以内概括这段对话主题，只输出标题。",
    ),
    SystemPromptConfig(
        "ocr",
        "OCR 提示词",
        "用于图片文字识别与截图信息整理。",
        "请识别图片中的文字与关键界面信息，按结构化要点输出。",
    ),
    SystemPromptConfig(
        "translate",
        "翻译提示词",
        "用于文本翻译任务。",
        "请在保留原意的基础上翻译文本，专有名词保持一致。",
    ),
    SystemPromptConfig(
        "summary",
        "历史摘要提示词",
        "用于长会话压缩与上下文摘要。",
        "请保留事实、决策、待办和关键约束，压缩为可继续对话的摘要。",
    ),
)
