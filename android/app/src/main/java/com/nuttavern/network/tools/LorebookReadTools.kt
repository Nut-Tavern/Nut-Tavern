package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.network.ChatTool
import com.nuttavern.network.ToolContext
import com.nuttavern.network.ToolGroup
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书**只读**工具:让模型查看当前会话已启用的世界书与条目。
 *
 * 作用范围硬边界:只能读 [ToolContext.sessionLorebooks](当前会话已启用集合,global + 角色 + persona
 * 三来源合并)。集合外的世界书一律按"不存在"拒绝,不泄露其它会话 / 未启用世界书。
 *
 * 两个工具职责拆分(对齐 docs/modules/lorebook-tools.md):
 * - [listSessionLorebooks]:列书 + 条目**摘要**(不含正文,省 token),可按 query 模糊过滤;
 * - [readLorebookEntry]:按 lorebook_id + uid 读单条目**完整正文**(列表只给摘要,正文按需读)。
 *
 * 写工具(apply_lorebook_edits + 回滚)是后续批次,本类只含只读两个,无副作用,needsApproval=false。
 */
@Singleton
class LorebookReadTools @Inject constructor() {

    val tools: List<ChatTool> = listOf(listSessionLorebooksTool(), readLorebookEntryTool())

    private fun listSessionLorebooksTool(): ChatTool = ChatTool(
        id = TOOL_LIST_SESSION_LOREBOOKS,
        name = TOOL_LIST_SESSION_LOREBOOKS,
        displayName = "列出会话世界书",
        description = "列出当前对话已启用的世界书及其条目摘要(条目 uid、备注、关键词、启用状态、正文预览)。" +
            "不含条目完整正文,正文请用 read_lorebook_entry 按需读取。" +
            "当用户想查看、检索、整理世界书内容时调用。",
        parametersSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "query",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "可选。按世界书名 / 条目备注 / 关键词模糊过滤,留空返回全部。"),
                ),
            ),
        needsApproval = false,
        group = LOREBOOK_TOOL_GROUP,
        execute = { arguments, context ->
            val query = arguments.optString("query").trim()
            listSessionLorebooks(context.sessionLorebooks, query)
        },
    )

    private fun readLorebookEntryTool(): ChatTool = ChatTool(
        id = TOOL_READ_LOREBOOK_ENTRY,
        name = TOOL_READ_LOREBOOK_ENTRY,
        displayName = "读取世界书条目",
        description = "读取指定世界书中某个条目的完整内容(含正文)。" +
            "lorebook_id 必须是当前对话已启用的世界书(可先用 list_session_lorebooks 获取)。",
        parametersSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "lorebook_id",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "世界书 id,必须在当前对话已启用集合内。"),
                    )
                    .put(
                        "uid",
                        JSONObject()
                            .put("type", "integer")
                            .put("description", "条目在该世界书内的 uid。"),
                    ),
            )
            .put("required", JSONArray().put("lorebook_id").put("uid")),
        needsApproval = false,
        group = LOREBOOK_TOOL_GROUP,
        execute = { arguments, context ->
            readLorebookEntry(context.sessionLorebooks, arguments)
        },
    )

    private fun listSessionLorebooks(sessionLorebooks: List<Lorebook>, query: String): String {
        val matched = if (query.isBlank()) {
            sessionLorebooks
        } else {
            sessionLorebooks.filter { book -> bookMatchesQuery(book, query) }
        }

        val lorebooksJson = JSONArray()
        for (book in matched) {
            val entriesJson = JSONArray()
            for (entry in entriesMatchingQuery(book, query)) {
                entriesJson.put(entrySummaryJson(entry))
            }
            lorebooksJson.put(
                JSONObject()
                    .put("id", book.id)
                    .put("name", book.name)
                    .put("entries", entriesJson),
            )
        }
        return JSONObject().put("lorebooks", lorebooksJson).toString()
    }

    private fun readLorebookEntry(sessionLorebooks: List<Lorebook>, arguments: JSONObject): String {
        val lorebookId = arguments.optString("lorebook_id").trim()
        if (lorebookId.isBlank()) {
            return errorJson("lorebook_id is required")
        }
        if (!arguments.has("uid")) {
            return errorJson("uid is required")
        }
        val uid = arguments.optInt("uid", Int.MIN_VALUE)
        if (uid == Int.MIN_VALUE) {
            return errorJson("uid must be an integer")
        }

        val book = sessionLorebooks.find { it.id == lorebookId }
            ?: return errorJson("lorebook not enabled in this conversation: $lorebookId")
        val entry = book.entries.find { it.uid == uid }
            ?: return errorJson("entry not found: uid=$uid in lorebook=$lorebookId")

        return JSONObject()
            .put("lorebook_id", book.id)
            .put("lorebook_name", book.name)
            .put("entry", entryFullJson(entry))
            .toString()
    }

    private fun bookMatchesQuery(book: Lorebook, query: String): Boolean {
        val lowerQuery = query.lowercase()
        if (book.name.lowercase().contains(lowerQuery)) return true
        return book.entries.any { entryMatchesQuery(it, lowerQuery) }
    }

    /** query 命中书名时返回全部条目;否则只返回命中的条目。空 query 返回全部。 */
    private fun entriesMatchingQuery(book: Lorebook, query: String): List<LorebookEntry> {
        if (query.isBlank()) return book.entries
        val lowerQuery = query.lowercase()
        if (book.name.lowercase().contains(lowerQuery)) return book.entries
        return book.entries.filter { entryMatchesQuery(it, lowerQuery) }
    }

    private fun entryMatchesQuery(entry: LorebookEntry, lowerQuery: String): Boolean {
        if (entry.comment.lowercase().contains(lowerQuery)) return true
        return entry.key.any { it.lowercase().contains(lowerQuery) }
    }

    private fun entrySummaryJson(entry: LorebookEntry): JSONObject =
        JSONObject()
            .put("uid", entry.uid)
            .put("comment", entry.comment)
            .put("key", JSONArray(entry.key))
            .put("enabled", !entry.disable)
            .put("content_preview", entry.content.take(CONTENT_PREVIEW_LENGTH))

    private fun entryFullJson(entry: LorebookEntry): JSONObject =
        JSONObject()
            .put("uid", entry.uid)
            .put("comment", entry.comment)
            .put("key", JSONArray(entry.key))
            .put("keysecondary", JSONArray(entry.keysecondary))
            .put("enabled", !entry.disable)
            .put("content", entry.content)
            .put("constant", entry.constant)
            .put("order", entry.order)
            .put("position", entry.position)
            .put("depth", entry.depth)

    private fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    companion object {
        const val TOOL_LIST_SESSION_LOREBOOKS = "list_session_lorebooks"
        const val TOOL_READ_LOREBOOK_ENTRY = "read_lorebook_entry"

        /** 世界书工具分组:列书 / 读条目(后续写工具也归入此组),UI 合并成一张卡用一个总开关管理。 */
        val LOREBOOK_TOOL_GROUP = ToolGroup(
            id = "lorebook",
            displayName = "世界书",
            description = "让模型查看、检索并编辑当前对话已启用的世界书条目",
        )

        /** 列表摘要里正文预览的截断长度。 */
        private const val CONTENT_PREVIEW_LENGTH = 80
    }
}
