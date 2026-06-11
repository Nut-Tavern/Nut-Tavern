package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEditHistory
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.LorebookRepository
import com.nuttavern.network.ChatTool
import com.nuttavern.network.DiffHunk
import com.nuttavern.network.DiffLine
import com.nuttavern.network.DiffLineKind
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
                    fields = listOf(buildDiffField("当前可撤销步数", null, editHistory.depth(lorebookId).toString())),
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
        .put("keysecondary", JSONArray(entry.keysecondary))
        .put("enabled", !entry.disable)
        .put("order", entry.order)
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
                        buildDiffField("关键词", null, formatJsonArray(after.optJSONArray("key"))),
                        buildDiffField("次要关键词", null, formatJsonArray(after.optJSONArray("keysecondary"))),
                        buildDiffField("启用状态", null, after.optBoolean("enabled").toString()),
                        buildDiffField("排序权重", null, after.optInt("order").toString()),
                        buildDiffField("正文", null, after.optString("content")),
                    ),
                )
            }
            before != null && afterValue == JSONObject.NULL -> {
                diffs += ToolDiffEntry(
                    title = "uid=$uid ${entryTitle(before)}",
                    type = ToolDiffType.DELETED,
                    fields = listOf(
                        buildDiffField("关键词", formatJsonArray(before.optJSONArray("key")), null),
                        buildDiffField("次要关键词", formatJsonArray(before.optJSONArray("keysecondary")), null),
                        buildDiffField("启用状态", before.optBoolean("enabled").toString(), null),
                        buildDiffField("排序权重", before.optInt("order").toString(), null),
                        buildDiffField("正文", before.optString("content"), null),
                    ),
                )
            }
            isOnlyEnabledChange(before, after) -> {
                diffs += ToolDiffEntry(
                    title = "uid=$uid ${entryTitle(after!!)}",
                    type = ToolDiffType.STATUS,
                    fields = listOf(
                        buildDiffField(
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
    return listOf("comment", "key", "keysecondary", "enabled", "order", "content").mapNotNull { field ->
        val beforeText = fieldText(before, field)
        val afterText = fieldText(after, field)
        if (beforeText == afterText) null
        else buildDiffField(fieldDisplayName(field), beforeText, afterText)
    }
}

/** 取字段的可读文本值。content 保留换行用于行级 diff,其余字段为单行。 */
private fun fieldText(json: JSONObject, field: String): String = when (field) {
    "key" -> formatJsonArray(json.optJSONArray(field))
    "keysecondary" -> formatJsonArray(json.optJSONArray(field))
    "content" -> json.optString(field)
    "enabled" -> json.optBoolean(field).toString()
    "order" -> json.optInt(field).toString()
    else -> json.optString(field).ifBlank { "(空)" }
}

/** 条目字段的中文显示名,与新增/删除/状态卡片的字段名保持一致。 */
private fun fieldDisplayName(field: String): String = when (field) {
    "comment" -> "条目名称"
    "key" -> "关键词"
    "keysecondary" -> "次要关键词"
    "enabled" -> "启用状态"
    "order" -> "排序权重"
    "content" -> "正文"
    else -> field
}

/**
 * 构造一个字段的行级 diff。
 *
 * - [before] 为 null:整段新增,所有行标记 ADDED。
 * - [after] 为 null:整段删除,所有行标记 REMOVED。
 * - 两者都有:按行做 LCS 比对,改动行及其前后各 [DIFF_CONTEXT_LINES] 行上下文聚成 hunk,
 *   不连续的改动各自成 hunk(对齐 unified diff)。
 */
internal fun buildDiffField(name: String, before: String?, after: String?): ToolDiffField {
    if (before != null && after != null && shouldUseSummaryDiff(before, after)) {
        return buildSummaryDiffField(name, before, after)
    }
    if (before == null && after != null && shouldUseOneSidedSummary(after)) {
        return buildOneSidedSummaryDiffField(name, DiffLineKind.ADDED, after)
    }
    if (before != null && after == null && shouldUseOneSidedSummary(before)) {
        return buildOneSidedSummaryDiffField(name, DiffLineKind.REMOVED, before)
    }

    val ops = when {
        before == null && after != null -> buildOneSidedDiffLines(DiffLineKind.ADDED, after)
        before != null && after == null -> buildOneSidedDiffLines(DiffLineKind.REMOVED, before)
        before != null && after != null -> diffLines(before.split("\n"), after.split("\n"))
        else -> emptyList()
    }
    val (hunks, trailingGap) = splitIntoHunks(ops, DIFF_CONTEXT_LINES)
    return ToolDiffField(name, hunks, trailingGap)
}

/** diff hunk 改动行前后保留的上下文行数。 */
private const val DIFF_CONTEXT_LINES = 3

/** LCS 的时间/内存是 O(n*m),超过这些阈值就降级成摘要 diff,避免确认弹窗卡顿。 */
private const val MAX_LCS_LINE_PRODUCT = 40_000
private const val MAX_LCS_TOTAL_CHARS = 80_000
private const val SUMMARY_PREVIEW_LINES = 8
private const val SUMMARY_PREVIEW_CHARS = 2_000

private fun shouldUseSummaryDiff(before: String, after: String): Boolean {
    val beforeLineCount = before.count { it == '\n' } + 1
    val afterLineCount = after.count { it == '\n' } + 1
    return beforeLineCount * afterLineCount > MAX_LCS_LINE_PRODUCT ||
        before.length + after.length > MAX_LCS_TOTAL_CHARS
}

private fun buildSummaryDiffField(name: String, before: String, after: String): ToolDiffField {
    val beforePreview = previewLargeText(before)
    val afterPreview = previewLargeText(after)
    val lines = beforePreview.lines.mapIndexed { index, text ->
        DiffLine(DiffLineKind.REMOVED, index + 1, null, text)
    } + afterPreview.lines.mapIndexed { index, text ->
        DiffLine(DiffLineKind.ADDED, null, index + 1, text)
    }
    return ToolDiffField(
        name = name,
        hunks = listOf(DiffHunk(lines, precededByGap = false)),
        hasTrailingGap = beforePreview.truncated || afterPreview.truncated,
    )
}

private fun buildOneSidedDiffLines(kind: DiffLineKind, text: String): List<LineOp> {
    return text.split("\n").mapIndexed { index, line ->
        when (kind) {
            DiffLineKind.ADDED -> LineOp(DiffLineKind.ADDED, null, index, line)
            DiffLineKind.REMOVED -> LineOp(DiffLineKind.REMOVED, index, null, line)
            DiffLineKind.CONTEXT -> LineOp(DiffLineKind.CONTEXT, index, index, line)
        }
    }
}

private fun buildOneSidedSummaryDiffField(name: String, kind: DiffLineKind, text: String): ToolDiffField {
    val preview = previewLargeText(text)
    val lines = preview.lines.mapIndexed { index, line ->
        when (kind) {
            DiffLineKind.ADDED -> DiffLine(DiffLineKind.ADDED, null, index + 1, line)
            DiffLineKind.REMOVED -> DiffLine(DiffLineKind.REMOVED, index + 1, null, line)
            DiffLineKind.CONTEXT -> DiffLine(DiffLineKind.CONTEXT, index + 1, index + 1, line)
        }
    }
    return ToolDiffField(
        name = name,
        hunks = listOf(DiffHunk(lines, precededByGap = false)),
        hasTrailingGap = preview.truncated,
    )
}

private fun shouldUseOneSidedSummary(text: String): Boolean {
    return text.length > MAX_LCS_TOTAL_CHARS || text.count { it == '\n' } + 1 > SUMMARY_PREVIEW_LINES * 4
}

private data class LargeTextPreview(val lines: List<String>, val truncated: Boolean)

private fun previewLargeText(text: String): LargeTextPreview {
    val lines = text.split("\n")
    val keptLines = lines.take(SUMMARY_PREVIEW_LINES).map { line ->
        if (line.length <= SUMMARY_PREVIEW_CHARS) line else line.take(SUMMARY_PREVIEW_CHARS) + "…"
    }
    val truncated = lines.size > keptLines.size || lines.any { it.length > SUMMARY_PREVIEW_CHARS }
    return LargeTextPreview(keptLines, truncated)
}

/** 行级 diff 的中间表示:行号为 0 起,渲染时 +1。 */
internal data class LineOp(
    val kind: DiffLineKind,
    val oldIndex: Int?,
    val newIndex: Int?,
    val text: String,
)

/** 标准 LCS 行级 diff:逐行比对,产出上下文 / 删除 / 新增的有序行操作。 */
internal fun diffLines(before: List<String>, after: List<String>): List<LineOp> {
    val n = before.size
    val m = after.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (before[i] == after[j]) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }

    val ops = mutableListOf<LineOp>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            before[i] == after[j] -> {
                ops += LineOp(DiffLineKind.CONTEXT, i, j, before[i])
                i++; j++
            }
            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                ops += LineOp(DiffLineKind.REMOVED, i, null, before[i])
                i++
            }
            else -> {
                ops += LineOp(DiffLineKind.ADDED, null, j, after[j])
                j++
            }
        }
    }
    while (i < n) { ops += LineOp(DiffLineKind.REMOVED, i, null, before[i]); i++ }
    while (j < m) { ops += LineOp(DiffLineKind.ADDED, null, j, after[j]); j++ }
    return ops
}

/**
 * 把行操作切成若干 hunk:每个改动行前后保留 [contextLines] 行上下文,相邻改动的上下文重叠时合并成一块。
 *
 * @return hunk 列表,以及"最后一块之后是否还有被省略的行"。
 */
internal fun splitIntoHunks(ops: List<LineOp>, contextLines: Int): Pair<List<DiffHunk>, Boolean> {
    val changeIndices = ops.indices.filter { ops[it].kind != DiffLineKind.CONTEXT }
    if (changeIndices.isEmpty()) return emptyList<DiffHunk>() to false

    val ranges = mutableListOf<IntRange>()
    for (index in changeIndices) {
        val start = (index - contextLines).coerceAtLeast(0)
        val end = (index + contextLines).coerceAtMost(ops.size - 1)
        val last = ranges.lastOrNull()
        if (last != null && start <= last.last + 1) {
            ranges[ranges.size - 1] = last.first..maxOf(last.last, end)
        } else {
            ranges += start..end
        }
    }

    val hunks = ranges.mapIndexed { rangeIndex, range ->
        val precededByGap = if (rangeIndex == 0) range.first > 0 else true
        val lines = range.map { opIndex ->
            val op = ops[opIndex]
            DiffLine(op.kind, op.oldIndex?.plus(1), op.newIndex?.plus(1), op.text)
        }
        DiffHunk(lines, precededByGap)
    }
    val trailingGap = ranges.last().last < ops.size - 1
    return hunks to trailingGap
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
