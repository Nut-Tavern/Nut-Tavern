package com.nuttavern.network.tools

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEditHistory
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.LorebookRepository
import com.nuttavern.network.ChatTool
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
        group = LorebookReadTools.LOREBOOK_TOOL_GROUP,
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
        group = LorebookReadTools.LOREBOOK_TOOL_GROUP,
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

        return JSONObject()
            .put("lorebook_id", lorebookId)
            .put("preview", preview)
            .put("applied", plan.appliedJson)
            .put("before_after", plan.beforeAfterJson)
            .put("undo_depth", editHistory.depth(lorebookId))
            .toString()
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
            .put("lorebook_id", lorebookId)
            .put("restored_entry_count", snapshot.size)
            .put("undo_depth", editHistory.depth(lorebookId))
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
        JSONObject().put("error", message).toString()

    companion object {
        const val TOOL_APPLY_LOREBOOK_EDITS = "apply_lorebook_edits"
        const val TOOL_UNDO_LOREBOOK_EDITS = "undo_lorebook_edits"

        private const val OP_CREATE = "create"
        private const val OP_UPDATE = "update"
        private const val OP_SET_ENABLED = "set_enabled"
        private const val OP_DELETE = "delete"

        /** 一次 apply 的最大操作数,超出拒绝,让模型分批。 */
        private const val MAX_EDITS = 20
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
