package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEditHistory
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.LorebookRepository
import com.nuttavern.network.ChatTool
import com.nuttavern.network.ToolApprovalDetails
import com.nuttavern.network.ToolDiffEntry
import com.nuttavern.network.ToolDiffField
import com.nuttavern.network.ToolDiffType
import com.nuttavern.network.ToolContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书**写**工具:让模型在当前会话已启用的世界书上批量编辑条目,并支持回滚。
 *
 * 作用范围硬边界与只读工具一致:只能改 [ToolContext.sessionLorebooks] 集合内的世界书,集合外拒绝。
 * agent 不能新建 / 删除整本世界书,不能改全局启用状态——只能对**条目**增删改。
 *
 * 两个工具(均 needsApproval=true,写操作必须人工确认):
 * - [applyLorebookEdits]:一个调用里批量 create / update(patch 语义) / set_enabled / delete 多条条目,
 *   支持 preview dry-run(只算 before/after 不落库)。
 * - [undoLorebookEdits]:按世界书 id 从撤销栈恢复上一次编辑前的整本快照。
 *
 * 落库读最新库状态(而非 context 快照),避免覆盖用户在 UI 的并发改动;范围校验用 context 集合。
 */
@Singleton
class LorebookWriteTools @Inject constructor(
    private val lorebookRepository: LorebookRepository,
    private val editHistory: LorebookEditHistory,
) {

    val tools: List<ChatTool> = listOf(applyLorebookEditsTool(), undoLorebookEditsTool())

    private fun applyLorebookEditsTool(): ChatTool = ChatTool(
        id = TOOL_APPLY_LOREBOOK_EDITS,
        name = TOOL_APPLY_LOREBOOK_EDITS,
        displayName = "编辑世界书条目",
        description = "在当前对话已启用的某本世界书里批量编辑条目:新建(create)、更新(update,只改传入字段)、" +
            "启用或禁用(set_enabled)、删除(delete)。一次最多 $MAX_EDITS 条操作。" +
            "传 preview=true 时只返回改动前后对照、不实际保存,供先确认再执行。" +
            "lorebook_id 必须是当前对话已启用的世界书(可先用 list_session_lorebooks 获取)。",
        parametersSchema = applyEditsSchema(),
        needsApproval = true,
        group = LOREBOOK_WRITE_TOOL_GROUP,
        approvalDetails = { arguments, context ->
            buildApplyLorebookEditsApprovalDetails(context.sessionLorebooks, arguments)
        },
        execute = { arguments, context ->
            applyLorebookEdits(context.sessionLorebooks, arguments)
        },
    )

    private fun undoLorebookEditsTool(): ChatTool = ChatTool(
        id = TOOL_UNDO_LOREBOOK_EDITS,
        name = TOOL_UNDO_LOREBOOK_EDITS,
        displayName = "撤销世界书编辑",
        description = "撤销对某本世界书的上一次编辑,恢复到编辑前的状态。" +
            "lorebook_id 必须是当前对话已启用的世界书。每本书最多可连续撤销 ${LorebookEditHistory.MAX_HISTORY} 次。",
        parametersSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "lorebook_id",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "要撤销编辑的世界书 id,必须在当前对话已启用集合内。"),
                ),
            )
            .put("required", JSONArray().put("lorebook_id")),
        needsApproval = true,
        group = LOREBOOK_WRITE_TOOL_GROUP,
        approvalDetails = { arguments, context ->
            buildUndoLorebookEditsApprovalDetails(context.sessionLorebooks, arguments)
        },
        execute = { arguments, context ->
            undoLorebookEdits(context.sessionLorebooks, arguments)
        },
    )

    private suspend fun applyLorebookEdits(
        sessionLorebooks: List<Lorebook>,
        arguments: JSONObject,
    ): String {
        val lorebookId = arguments.optString("lorebook_id").trim()
        if (lorebookId.isBlank()) return errorJson("lorebook_id is required")
        if (sessionLorebooks.none { it.id == lorebookId }) {
            return errorJson("lorebook not enabled in this conversation: $lorebookId")
        }

        val editsArray = arguments.optJSONArray("edits")
            ?: return errorJson("edits is required and must be an array")
        if (editsArray.length() == 0) return errorJson("edits must not be empty")
        if (editsArray.length() > MAX_EDITS) {
            return errorJson("too many edits: ${editsArray.length()} (max $MAX_EDITS), split into batches")
        }

        // 读最新库状态(非 context 快照),避免覆盖用户在 UI 的并发改动。
        val book = lorebookRepository.findById(lorebookId).first()
            ?: return errorJson("lorebook not found: $lorebookId")

        val preview = arguments.optBoolean("preview", false)
        val plan = planLorebookEdits(book, editsArray)
        if (plan.error != null) return errorJson(plan.error)

        if (!preview) {
            // 落库前压入编辑前整书快照,供回滚。
            editHistory.push(lorebookId, book.entries)
            lorebookRepository.updateEntries(lorebookId, plan.resultEntries)
        }

        val response = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_APPLY_LOREBOOK_EDITS)
            .put("lorebook_id", lorebookId)
            .put("preview", preview)
            // saved 明示是否真正落库,避免模型把 preview dry-run 误读成"已保存"。
            .put("saved", !preview)
            .put("applied", plan.appliedJson)
            .put("before_after", plan.beforeAfterJson)
            .put("undo_depth", editHistory.depth(lorebookId))
        val warnings = JSONArray()
        if (preview) {
            val note = "这是预览结果，尚未保存。确认无误后用 preview=false 再次调用同样的 edits 才会写入。"
            response.put("note", note)
            warnings.put(JSONObject().put("code", "PREVIEW_NOT_SAVED").put("message", note))
        }
        // 被改动后仍处于禁用态的条目:提示模型/用户它们不会参与本轮注入,改了也"不生效"。
        val disabledTargets = disabledEffectiveUids(plan.resultEntries, plan.appliedJson)
        if (disabledTargets.isNotEmpty()) {
            val disabledUidsJson = JSONArray(disabledTargets)
            response.put("disabled_entry_uids", disabledUidsJson)
            response.put(
                "disabled_note",
                "uid $disabledTargets 当前为禁用状态，编辑已记录但不会参与对话注入。" +
                    "若需生效，用 set_enabled 启用对应条目。",
            )
            warnings.put(
                JSONObject()
                    .put("code", "DISABLED_ENTRY_NOT_EFFECTIVE")
                    .put("uids", disabledUidsJson)
                    .put("message", "这些条目仍为禁用状态，编辑已记录但不会参与对话注入。"),
            )
        }
        response.put("warnings", warnings)
        return response.toString()
    }

    private suspend fun buildApplyLorebookEditsApprovalDetails(
        sessionLorebooks: List<Lorebook>,
        arguments: JSONObject,
    ): ToolApprovalDetails {
        val lorebookId = arguments.optString("lorebook_id").trim()
        if (lorebookId.isBlank()) {
            return ToolApprovalDetails(warnings = listOf("缺少 lorebook_id，调用会被拒绝。"))
        }
        val sessionBook = sessionLorebooks.find { it.id == lorebookId }
            ?: return ToolApprovalDetails(warnings = listOf("世界书不在当前对话已启用集合内：$lorebookId"))
        val editsArray = arguments.optJSONArray("edits")
            ?: return ToolApprovalDetails(warnings = listOf("缺少 edits 数组，调用会被拒绝。"))
        if (editsArray.length() == 0) {
            return ToolApprovalDetails(warnings = listOf("edits 为空，调用会被拒绝。"))
        }
        if (editsArray.length() > MAX_EDITS) {
            return ToolApprovalDetails(warnings = listOf("一次最多 $MAX_EDITS 条编辑，当前 ${editsArray.length()} 条会被拒绝。"))
        }

        val latestBook = lorebookRepository.findById(lorebookId).first() ?: sessionBook
        val plan = planLorebookEdits(latestBook, editsArray)
        if (plan.error != null) {
            return ToolApprovalDetails(
                description = "目标世界书：${latestBook.displayNameForApproval()}",
                warnings = listOf("编辑计划无效：${plan.error}"),
            )
        }

        val diff = buildLorebookEditDiffSections(plan.beforeAfterJson)
        val disabledTargets = disabledEffectiveUids(plan.resultEntries, plan.appliedJson)
        val warnings = buildList {
            if (arguments.optBoolean("preview", false)) {
                add("这是 preview=true 的预览调用，不会保存到世界书。")
            }
            if (disabledTargets.isNotEmpty()) {
                add("uid $disabledTargets 编辑后仍为禁用状态，不会参与对话注入。")
            }
        }
        return ToolApprovalDetails(
            description = "目标世界书：${latestBook.displayNameForApproval()}；将执行 ${editsArray.length()} 项编辑。",
            diffs = diff,
            warnings = warnings,
        )
    }

    private fun buildUndoLorebookEditsApprovalDetails(
        sessionLorebooks: List<Lorebook>,
        arguments: JSONObject,
    ): ToolApprovalDetails {
        val lorebookId = arguments.optString("lorebook_id").trim()
        if (lorebookId.isBlank()) {
            return ToolApprovalDetails(warnings = listOf("缺少 lorebook_id，调用会被拒绝。"))
        }
        val book = sessionLorebooks.find { it.id == lorebookId }
        val name = book?.displayNameForApproval() ?: lorebookId
        return ToolApprovalDetails(
            description = "将撤销世界书「$name」的上一次工具编辑，并恢复到编辑前的整书条目快照。",
            diffs = listOf(
                ToolDiffEntry(
                    title = "恢复整本世界书条目列表",
                    type = ToolDiffType.STATUS,
                    fields = listOf(ToolDiffField("当前可撤销步数", null, editHistory.depth(lorebookId).toString())),
                ),
            ),
            warnings = if (book == null) listOf("该世界书不在当前对话已启用集合内，调用会被拒绝。") else emptyList(),
        )
    }

    private suspend fun undoLorebookEdits(
        sessionLorebooks: List<Lorebook>,
        arguments: JSONObject,
    ): String {
        val lorebookId = arguments.optString("lorebook_id").trim()
        if (lorebookId.isBlank()) return errorJson("lorebook_id is required")
        if (sessionLorebooks.none { it.id == lorebookId }) {
            return errorJson("lorebook not enabled in this conversation: $lorebookId")
        }

        val snapshot = editHistory.pop(lorebookId)
            ?: return errorJson("nothing to undo for lorebook: $lorebookId")
        lorebookRepository.updateEntries(lorebookId, snapshot)

        return JSONObject()
            .put("ok", true)
            .put("tool", TOOL_UNDO_LOREBOOK_EDITS)
            .put("lorebook_id", lorebookId)
            .put("saved", true)
            .put("restored_entry_count", snapshot.size)
            .put("undo_depth", editHistory.depth(lorebookId))
            .put("warnings", JSONArray())
            .toString()
    }

    /** 批量编辑的执行计划(纯计算,不落库),便于 preview 与正式执行共用同一套逻辑。见顶层 [planLorebookEdits]。 */

    private fun applyEditsSchema(): JSONObject {
        val editItemSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "op",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put(OP_CREATE).put(OP_UPDATE).put(OP_SET_ENABLED).put(OP_DELETE))
                            .put("description", "操作类型:create 新建 / update 更新 / set_enabled 启停 / delete 删除"),
                    )
                    .put("uid", JSONObject().put("type", "integer").put("description", "update/set_enabled/delete 必填,目标条目 uid"))
                    .put("enabled", JSONObject().put("type", "boolean").put("description", "set_enabled 必填"))
                    .put(
                        "entry",
                        JSONObject().put("type", "object")
                            .put("description", "create 必填。可含 comment / key(数组) / keysecondary / content / enabled / order"),
                    )
                    .put(
                        "patch",
                        JSONObject().put("type", "object")
                            .put("description", "update 必填,只改传入字段。可含 content / comment / key / keysecondary / order"),
                    ),
            )
            .put("required", JSONArray().put("op"))

        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "lorebook_id",
                        JSONObject().put("type", "string").put("description", "目标世界书 id,必须在当前对话已启用集合内。"),
                    )
                    .put(
                        "preview",
                        JSONObject().put("type", "boolean")
                            .put("description", "true=只返回改动前后对照、不保存(dry-run);默认 false 实际保存。"),
                    )
                    .put(
                        "edits",
                        JSONObject().put("type", "array")
                            .put("description", "编辑操作列表,一次最多 $MAX_EDITS 条。")
                            .put("items", editItemSchema),
                    ),
            )
            .put("required", JSONArray().put("lorebook_id").put("edits"))
    }

    private fun errorJson(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("error", message)
            .put("message", message)
            .toString()

    companion object {
        const val TOOL_APPLY_LOREBOOK_EDITS = "apply_lorebook_edits"
        const val TOOL_UNDO_LOREBOOK_EDITS = "undo_lorebook_edits"

        private const val OP_CREATE = "create"
        private const val OP_UPDATE = "update"
        private const val OP_SET_ENABLED = "set_enabled"
        private const val OP_DELETE = "delete"

        /** 一次 apply 的最大操作数,超出拒绝,让模型分批。 */
        private const val MAX_EDITS = 20

        /** 世界书编辑工具分组:允许批量写入 / 撤销,写操作始终强制人工确认。 */
        val LOREBOOK_WRITE_TOOL_GROUP = com.nuttavern.network.ToolGroup(
            id = "lorebook_write",
            displayName = "世界书编辑",
            description = "允许模型编辑当前对话已启用的世界书条目，写操作始终需要确认",
        )
    }
}

/** 批量编辑的执行计划(纯计算,不落库)。[error] 非空表示编辑非法,resultEntries 此时无意义。 */
internal data class LorebookEditPlan(
    val resultEntries: List<LorebookEntry>,
    val appliedJson: JSONArray,
    val beforeAfterJson: JSONArray,
    val error: String? = null,
)

private const val EDIT_OP_CREATE = "create"
private const val EDIT_OP_UPDATE = "update"
private const val EDIT_OP_SET_ENABLED = "set_enabled"
private const val EDIT_OP_DELETE = "delete"

/**
 * 纯函数:在给定世界书上算出批量编辑的结果(不落库)。preview 与正式执行共用。
 *
 * - create:uid 由 [Lorebook.nextEntryUid] 起递增分配,新建条目追加在末尾;
 * - update:patch 语义,只改 patch 出现的字段;
 * - set_enabled:enabled 取反写入 disable;
 * - delete:按 uid 删除。
 *
 * 任一操作非法(缺字段 / uid 不存在 / 未知 op)立即返回带 [LorebookEditPlan.error] 的计划,不部分应用。
 */
internal fun planLorebookEdits(book: Lorebook, editsArray: JSONArray): LorebookEditPlan {
    val entriesByUid = book.entries.associateBy { it.uid }.toMutableMap()
    var nextUid = book.nextEntryUid()
    val applied = JSONArray()
    val beforeAfter = JSONArray()

    for (i in 0 until editsArray.length()) {
        val edit = editsArray.optJSONObject(i)
            ?: return planError("edits[$i] is not an object")
        when (val op = edit.optString("op").trim()) {
            EDIT_OP_CREATE -> {
                val entryJson = edit.optJSONObject("entry")
                    ?: return planError("edits[$i] create requires 'entry'")
                val created = buildEntryFromJson(entryJson, uid = nextUid)
                entriesByUid[created.uid] = created
                applied.put(JSONObject().put("op", EDIT_OP_CREATE).put("uid", created.uid).put("comment", created.comment))
                beforeAfter.put(
                    JSONObject().put("uid", created.uid)
                        .put("before", JSONObject.NULL)
                        .put("after", entrySummaryJson(created)),
                )
                nextUid++
            }
            EDIT_OP_UPDATE -> {
                val uid = optUid(edit) ?: return planError("edits[$i] update requires integer 'uid'")
                val existing = entriesByUid[uid] ?: return planError("edits[$i] update: uid $uid not found")
                val patch = edit.optJSONObject("patch")
                    ?: return planError("edits[$i] update requires 'patch'")
                val (updated, changedFields) = applyPatch(existing, patch)
                entriesByUid[uid] = updated
                applied.put(
                    JSONObject().put("op", EDIT_OP_UPDATE).put("uid", uid)
                        .put("changed_fields", JSONArray(changedFields)),
                )
                beforeAfter.put(
                    JSONObject().put("uid", uid)
                        .put("before", entrySummaryJson(existing))
                        .put("after", entrySummaryJson(updated)),
                )
            }
            EDIT_OP_SET_ENABLED -> {
                val uid = optUid(edit) ?: return planError("edits[$i] set_enabled requires integer 'uid'")
                if (!edit.has("enabled")) return planError("edits[$i] set_enabled requires 'enabled'")
                val existing = entriesByUid[uid] ?: return planError("edits[$i] set_enabled: uid $uid not found")
                val enabled = edit.optBoolean("enabled")
                val updated = existing.copy(disable = !enabled)
                entriesByUid[uid] = updated
                applied.put(JSONObject().put("op", EDIT_OP_SET_ENABLED).put("uid", uid).put("enabled", enabled))
                beforeAfter.put(
                    JSONObject().put("uid", uid)
                        .put("before", JSONObject().put("enabled", !existing.disable))
                        .put("after", JSONObject().put("enabled", enabled)),
                )
            }
            EDIT_OP_DELETE -> {
                val uid = optUid(edit) ?: return planError("edits[$i] delete requires integer 'uid'")
                val existing = entriesByUid.remove(uid)
                    ?: return planError("edits[$i] delete: uid $uid not found")
                applied.put(JSONObject().put("op", EDIT_OP_DELETE).put("uid", uid))
                beforeAfter.put(
                    JSONObject().put("uid", uid)
                        .put("before", entrySummaryJson(existing))
                        .put("after", JSONObject.NULL),
                )
            }
            else -> return planError("edits[$i] unknown op: '$op'")
        }
    }

    // 保持原有条目顺序,新建的追加在末尾(按 uid 升序)。
    val originalOrder = book.entries.mapNotNull { entriesByUid[it.uid] }
    val originalUids = book.entries.map { it.uid }.toSet()
    val created = entriesByUid.values.filter { it.uid !in originalUids }.sortedBy { it.uid }
    return LorebookEditPlan(originalOrder + created, applied, beforeAfter)
}

private fun planError(message: String): LorebookEditPlan =
    LorebookEditPlan(emptyList(), JSONArray(), JSONArray(), message)

/** patch 语义:只改 patch 里出现的字段,其余沿用原值。返回更新后的条目 + 实际变更字段名。 */
private fun applyPatch(existing: LorebookEntry, patch: JSONObject): Pair<LorebookEntry, List<String>> {
    var updated = existing
    val changed = mutableListOf<String>()

    if (patch.has("content")) {
        updated = updated.copy(content = patch.optString("content"))
        changed += "content"
    }
    if (patch.has("comment")) {
        updated = updated.copy(comment = patch.optString("comment"))
        changed += "comment"
    }
    if (patch.has("key")) {
        updated = updated.copy(key = jsonArrayToStringList(patch.optJSONArray("key")))
        changed += "key"
    }
    if (patch.has("keysecondary")) {
        updated = updated.copy(keysecondary = jsonArrayToStringList(patch.optJSONArray("keysecondary")))
        changed += "keysecondary"
    }
    if (patch.has("order")) {
        updated = updated.copy(order = patch.optInt("order"))
        changed += "order"
    }
    return updated to changed
}

/** create:用 entry JSON 的可写字段构造条目,未传字段用 LorebookEntry 默认值,uid 由仓库分配。 */
private fun buildEntryFromJson(entryJson: JSONObject, uid: Int): LorebookEntry =
    LorebookEntry(
        uid = uid,
        comment = entryJson.optString("comment"),
        key = jsonArrayToStringList(entryJson.optJSONArray("key")),
        keysecondary = jsonArrayToStringList(entryJson.optJSONArray("keysecondary")),
        content = entryJson.optString("content"),
        disable = if (entryJson.has("enabled")) !entryJson.optBoolean("enabled") else false,
        order = if (entryJson.has("order")) entryJson.optInt("order") else LorebookEntry().order,
    )

private fun optUid(edit: JSONObject): Int? {
    if (!edit.has("uid")) return null
    val uid = edit.optInt("uid", Int.MIN_VALUE)
    return if (uid == Int.MIN_VALUE) null else uid
}

private fun jsonArrayToStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
}

private fun entrySummaryJson(entry: LorebookEntry): JSONObject =
    JSONObject()
        .put("uid", entry.uid)
        .put("comment", entry.comment)
        .put("key", JSONArray(entry.key))
        .put("enabled", !entry.disable)
        .put("content", entry.content)

private fun Lorebook.displayNameForApproval(): String = name.ifBlank { id }

/**
 * 把 before_after JSON 转成确认弹窗使用的结构化 diff 数据。
 *
 * 目标是接近编程 CLI 的变更预览:新增 / 删除 / 修改 / 启停分类,提供上下行差异。
 */
internal fun buildLorebookEditDiffSections(beforeAfterJson: JSONArray): List<ToolDiffEntry> {
    val diffs = mutableListOf<ToolDiffEntry>()

    for (i in 0 until beforeAfterJson.length()) {
        val change = beforeAfterJson.optJSONObject(i) ?: continue
        val uid = change.optInt("uid")
        val beforeValue = change.opt("before")
        val afterValue = change.opt("after")
        val before = beforeValue as? JSONObject
        val after = afterValue as? JSONObject

        when {
            beforeValue == JSONObject.NULL && after != null -> {
                diffs += ToolDiffEntry(
                    title = "uid=$uid ${entryTitle(after)}",
                    type = ToolDiffType.ADDED,
                    fields = listOf(
                        ToolDiffField("关键词", null, formatJsonArray(after.optJSONArray("key"))),
                        ToolDiffField("正文", null, previewText(after.optString("content"))),
                    ),
                )
            }
            before != null && afterValue == JSONObject.NULL -> {
                diffs += ToolDiffEntry(
                    title = "uid=$uid ${entryTitle(before)}",
                    type = ToolDiffType.DELETED,
                )
            }
            isOnlyEnabledChange(before, after) -> {
                diffs += ToolDiffEntry(
                    title = "uid=$uid ${entryTitle(after!!)}",
                    type = ToolDiffType.STATUS,
                    fields = listOf(
                        ToolDiffField(
                            name = "启用状态",
                            before = before!!.optBoolean("enabled").toString(),
                            after = after.optBoolean("enabled").toString(),
                        ),
                    ),
                )
            }
            before != null && after != null -> {
                val fields = changedFields(before, after)
                diffs += ToolDiffEntry(
                    title = "uid=$uid ${entryTitle(after)}",
                    type = ToolDiffType.MODIFIED,
                    fields = fields,
                )
            }
        }
    }

    return diffs
}

private fun isOnlyEnabledChange(before: JSONObject?, after: JSONObject?): Boolean {
    if (before == null || after == null) return false
    return before.length() == 1 && after.length() == 1 && before.has("enabled") && after.has("enabled")
}

private fun changedFields(before: JSONObject, after: JSONObject): List<ToolDiffField> {
    val fields = listOf("comment", "key", "enabled", "content")
    return fields.mapNotNull { field ->
        if (field == "content") {
            val beforeText = before.optString(field)
            val afterText = after.optString(field)
            if (beforeText == afterText) return@mapNotNull null
            val (previewBefore, previewAfter) = extractDiffContext(beforeText, afterText)
            ToolDiffField(field, previewBefore, previewAfter)
        } else {
            val beforeText = jsonValueForPreview(before, field)
            val afterText = jsonValueForPreview(after, field)
            if (beforeText == afterText) null else ToolDiffField(field, beforeText, afterText)
        }
    }
}

/**
 * 提取长文本差异上下文:截取修改位置前后各保留一段文本,用 `...` 省略无关部分。
 */
internal fun extractDiffContext(before: String, after: String, contextLength: Int = 15): Pair<String, String> {
    var prefixLen = 0
    while (prefixLen < before.length && prefixLen < after.length && before[prefixLen] == after[prefixLen]) {
        prefixLen++
    }
    
    var suffixLen = 0
    while (suffixLen < before.length - prefixLen && suffixLen < after.length - prefixLen && 
        before[before.length - 1 - suffixLen] == after[after.length - 1 - suffixLen]) {
        suffixLen++
    }

    val beforeDiff = before.substring(prefixLen, before.length - suffixLen)
    val afterDiff = after.substring(prefixLen, after.length - suffixLen)

    val prefixStart = (prefixLen - contextLength).coerceAtLeast(0)
    val prefix = before.substring(prefixStart, prefixLen).replace(Regex("\\s+"), " ")
    val prefixDot = if (prefixStart > 0) "..." else ""

    val beforeSuffixEnd = (before.length - suffixLen + contextLength).coerceAtMost(before.length)
    val beforeSuffix = before.substring(before.length - suffixLen, beforeSuffixEnd).replace(Regex("\\s+"), " ")
    val beforeSuffixDot = if (beforeSuffixEnd < before.length) "..." else ""
    
    val afterSuffixEnd = (after.length - suffixLen + contextLength).coerceAtMost(after.length)
    val afterSuffix = after.substring(after.length - suffixLen, afterSuffixEnd).replace(Regex("\\s+"), " ")
    val afterSuffixDot = if (afterSuffixEnd < after.length) "..." else ""

    val cleanBeforeDiff = beforeDiff.replace(Regex("\\s+"), " ")
    val cleanAfterDiff = afterDiff.replace(Regex("\\s+"), " ")

    val previewBefore = "$prefixDot$prefix[-$cleanBeforeDiff-]$beforeSuffix$beforeSuffixDot"
    val previewAfter = "$prefixDot$prefix[+$cleanAfterDiff+]$afterSuffix$afterSuffixDot"
    
    return previewBefore to previewAfter
}

private fun jsonValueForPreview(json: JSONObject, field: String): String = when (field) {
    "key" -> formatJsonArray(json.optJSONArray(field))
    "content" -> previewText(json.optString(field))
    else -> json.opt(field)?.toString()?.ifBlank { "(空)" } ?: "(缺失)"
}

private fun entryTitle(entryJson: JSONObject): String {
    val comment = entryJson.optString("comment").ifBlank { "未命名条目" }
    return "「$comment」"
}

private fun formatJsonArray(array: JSONArray?): String {
    if (array == null || array.length() == 0) return "[]"
    return (0 until array.length()).joinToString(prefix = "[", postfix = "]") { index ->
        array.optString(index)
    }
}

private fun previewText(text: String, limit: Int = 48): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return "(空)"
    return if (normalized.length <= limit) normalized else normalized.take(limit) + "..."
}

/**
 * 找出被 create / update 触碰、但结果仍处于禁用态的条目 uid。
 *
 * 这些条目编辑成功也不会参与对话注入(运行时只扫 `!disable` 的条目),回执据此提示模型/用户。
 * set_enabled / delete 不在此列:前者本身就是启停意图,后者条目已不存在。
 */
internal fun disabledEffectiveUids(resultEntries: List<LorebookEntry>, appliedJson: JSONArray): List<Int> {
    val touchedUids = buildSet {
        for (i in 0 until appliedJson.length()) {
            val applied = appliedJson.optJSONObject(i) ?: continue
            when (applied.optString("op")) {
                EDIT_OP_CREATE, EDIT_OP_UPDATE -> add(applied.optInt("uid"))
            }
        }
    }
    return resultEntries
        .filter { it.uid in touchedUids && it.disable }
        .map { it.uid }
}
