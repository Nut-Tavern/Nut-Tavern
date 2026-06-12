package com.nuttavern.ui.lorebook

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UserRound
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.data.lorebook.SelectiveLogic
import com.nuttavern.data.lorebook.WiPosition
import com.nuttavern.data.lorebook.WiRole
import com.nuttavern.ui.components.NutTavernEnumRow
import com.nuttavern.ui.components.NutTavernExpandableHeader
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernGroupSection
import com.nuttavern.ui.components.NutTavernIconRow
import com.nuttavern.ui.components.NutTavernLabeledTextField
import com.nuttavern.ui.components.NutTavernNumericField
import com.nuttavern.ui.components.NutTavernSwitchRow
import com.nuttavern.ui.components.NumericParser

/**
 * 世界书条目编辑表单的 LazyList items。
 *
 * 全局世界书编辑页和角色内嵌世界书编辑器共用此表单体。
 * 调用方负责 Scaffold / TopBar / 保存逻辑 / 对话框,此函数只输出表单 items。
 *
 * @param draft 当前编辑草稿
 * @param onDraftChange 草稿变更回调
 * @param onDeleteClick 删除按钮点击回调;null 时不显示删除行
 * @param onCharacterFilterClick 角色过滤器行点击回调;null 时不显示角色过滤器行
 * @param deleteSubtitle 删除行副标题文案
 */
fun LazyListScope.lorebookEntryFormItems(
    draft: LorebookEntry,
    onDraftChange: (LorebookEntry) -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    onCharacterFilterClick: (() -> Unit)? = null,
    deleteSubtitle: String = "从世界书中永久移除",
) {
    // 基础
    item(key = "basic") {
        NutTavernGroupSection {
            NutTavernLabeledTextField(
                label = "标题",
                value = draft.comment,
                onValueChange = { onDraftChange(draft.copy(comment = it)) },
                placeholder = "条目名称,仅用于列表显示",
                singleLine = true,
            )
            NutTavernGroupDivider()
            NutTavernLabeledTextField(
                label = "内容",
                value = draft.content,
                onValueChange = { onDraftChange(draft.copy(content = it)) },
                placeholder = "激活后注入到 prompt 的文本",
                minLines = 4,
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "常驻",
                subtitle = "不需要关键词触发,始终注入",
                checked = draft.constant,
                onCheckedChange = { onDraftChange(draft.copy(constant = it)) },
            )
        }
    }

    // 关键词
    item(key = "keywords") {
        NutTavernGroupSection {
            NutTavernLabeledTextField(
                label = "主关键词",
                value = draft.key.joinToString(", "),
                onValueChange = { raw ->
                    onDraftChange(draft.copy(key = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                },
                placeholder = "逗号分隔,任一命中即触发",
                supportingText = "当前 ${draft.key.size} 个关键词",
            )
            NutTavernGroupDivider()
            NutTavernLabeledTextField(
                label = "次要关键词",
                value = draft.keysecondary.joinToString(", "),
                onValueChange = { raw ->
                    onDraftChange(draft.copy(keysecondary = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                },
                placeholder = "逗号分隔,配合下方逻辑使用",
            )
            NutTavernGroupDivider()
            NutTavernEnumRow(
                label = "次要关键词逻辑",
                value = draft.selectiveLogic,
                options = listOf(
                    SelectiveLogic.AND_ANY to "任一命中",
                    SelectiveLogic.NOT_ALL to "不全部命中",
                    SelectiveLogic.NOT_ANY to "全部不命中",
                    SelectiveLogic.AND_ALL to "全部命中",
                ),
                onSelect = { onDraftChange(draft.copy(selectiveLogic = it)) },
            )
        }
    }

    // 注入控制
    item(key = "injection") {
        NutTavernGroupSection {
            NutTavernEnumRow(
                label = "注入位置",
                value = draft.position,
                options = listOf(
                    WiPosition.BEFORE to "角色描述之前",
                    WiPosition.AFTER to "角色描述之后",
                    // AN_TOP / AN_BOTTOM 不暴露:author's note 模块未落地,运行时被忽略
                    // (见 AGENTS.md "Author's Note 模块未落地"待办)。导入的旧条目 position
                    // 仍 round-trip 保留,但本仓库 UI 不再让用户主动选这两档。
                    WiPosition.AT_DEPTH to "按深度插入",
                    WiPosition.EM_TOP to "示例消息之前",
                    WiPosition.EM_BOTTOM to "示例消息之后",
                ),
                onSelect = { onDraftChange(draft.copy(position = it)) },
            )
            if (draft.position == WiPosition.AT_DEPTH) {
                NutTavernGroupDivider()
                NutTavernNumericField(
                    label = "深度",
                    value = draft.depth,
                    onValueChange = { it?.let { v -> onDraftChange(draft.copy(depth = v)) } },
                    parser = NumericParser.IntParser,
                    helperText = "倒数第 N 条消息之前插入",
                    min = 0,
                    max = 1000,
                )
                NutTavernGroupDivider()
                NutTavernEnumRow(
                    label = "角色",
                    value = draft.role,
                    options = listOf(
                        WiRole.SYSTEM to "系统",
                        WiRole.USER to "用户",
                        WiRole.ASSISTANT to "助手",
                    ),
                    onSelect = { onDraftChange(draft.copy(role = it)) },
                )
            }
            NutTavernGroupDivider()
            NutTavernNumericField(
                label = "排序权重",
                value = draft.order,
                onValueChange = { it?.let { v -> onDraftChange(draft.copy(order = v)) } },
                parser = NumericParser.IntParser,
                helperText = "数字越大越先注入,默认 100",
                min = 0,
            )
        }
    }

    // 高级(折叠)
    item(key = "advanced-header") {
        AdvancedSectionStateful(draft = draft, onDraftChange = onDraftChange)
    }

    // 扫描范围扩展(折叠)
    item(key = "scan-scope-header") {
        ScanScopeSectionStateful(draft = draft, onDraftChange = onDraftChange)
    }

    // 角色过滤器
    if (onCharacterFilterClick != null) {
        item(key = "character-filter") {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.UserRound,
                    title = "角色过滤器",
                    subtitle = draft.characterFilter?.let { cf ->
                        val mode = if (cf.isExclude) "排除" else "仅限"
                        val count = cf.names.size
                        if (count == 0) "未设置" else "$mode $count 个角色"
                    } ?: "未设置(对所有角色生效)",
                    onClick = onCharacterFilterClick,
                    showTrailingChevron = true,
                )
            }
        }
    }

    // 删除
    if (onDeleteClick != null) {
        item(key = "delete") {
            NutTavernGroupSection {
                NutTavernIconRow(
                    icon = Lucide.Trash2,
                    title = "删除条目",
                    subtitle = deleteSubtitle,
                    destructive = true,
                    onClick = onDeleteClick,
                )
            }
        }
    }
}

// ── 高级设置折叠区(内部持有展开状态) ──

@Composable
private fun AdvancedSectionStateful(
    draft: LorebookEntry,
    onDraftChange: (LorebookEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    NutTavernExpandableHeader(
        title = "高级设置",
        expanded = expanded,
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        NutTavernGroupSection {
            NutTavernLabeledTextField(
                label = "互斥组",
                value = draft.group,
                onValueChange = { onDraftChange(draft.copy(group = it)) },
                placeholder = "同组内只激活权重最高的一个",
                singleLine = true,
            )
            NutTavernGroupDivider()
            NutTavernNumericField(
                label = "组内权重",
                value = draft.groupWeight,
                onValueChange = { it?.let { v -> onDraftChange(draft.copy(groupWeight = v)) } },
                parser = NumericParser.IntParser,
                min = 0,
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "组内强制激活",
                subtitle = "即使权重低也强制激活",
                checked = draft.groupOverride,
                onCheckedChange = { onDraftChange(draft.copy(groupOverride = it)) },
            )
            NutTavernGroupDivider()
            NutTavernNumericField(
                label = "激活概率",
                value = draft.probability,
                onValueChange = { it?.let { v -> onDraftChange(draft.copy(probability = v)) } },
                parser = NumericParser.IntParser,
                helperText = "0-100,100 = 必定激活",
                min = 0,
                max = 100,
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "启用概率判断",
                checked = draft.useProbability,
                onCheckedChange = { onDraftChange(draft.copy(useProbability = it)) },
            )
            NutTavernGroupDivider()
            NutTavernNumericField(
                label = "Sticky",
                value = draft.sticky,
                onValueChange = { onDraftChange(draft.copy(sticky = it)) },
                parser = NumericParser.IntParser,
                helperText = "激活后继续保持 N 条消息;留空表示不黏着",
                placeholder = "Non-sticky",
                min = 1,
                max = 10000,
                nullable = true,
            )
            NutTavernGroupDivider()
            NutTavernNumericField(
                label = "Cooldown",
                value = draft.cooldown,
                onValueChange = { onDraftChange(draft.copy(cooldown = it)) },
                parser = NumericParser.IntParser,
                helperText = "激活后 N 条消息内不再触发;留空表示无冷却",
                placeholder = "No cooldown",
                min = 1,
                max = 10000,
                nullable = true,
            )
            NutTavernGroupDivider()
            NutTavernNumericField(
                label = "Delay",
                value = draft.delay,
                onValueChange = { onDraftChange(draft.copy(delay = it)) },
                parser = NumericParser.IntParser,
                helperText = "会话消息数达到 N 后才允许激活;留空表示无延迟",
                placeholder = "No delay",
                min = 1,
                max = 10000,
                nullable = true,
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "Delay until recursion",
                subtitle = "仅在递归扫描阶段允许激活",
                checked = draft.delayUntilRecursion > 0,
                onCheckedChange = { enabled ->
                    onDraftChange(draft.copy(delayUntilRecursion = if (enabled) 1 else 0))
                },
            )
            if (draft.delayUntilRecursion > 0) {
                NutTavernGroupDivider()
                NutTavernNumericField(
                    label = "Recursion Level",
                    value = draft.delayUntilRecursion,
                    onValueChange = { value ->
                        onDraftChange(draft.copy(delayUntilRecursion = value ?: 1))
                    },
                    parser = NumericParser.IntParser,
                    helperText = "第 N 层递归扫描开始允许激活",
                    placeholder = "1",
                    min = 1,
                    max = 10000,
                )
            }
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "忽略 Token 预算",
                subtitle = "即使超出预算也强制注入",
                checked = draft.ignoreBudget,
                onCheckedChange = { onDraftChange(draft.copy(ignoreBudget = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "递归时跳过",
                subtitle = "递归扫描阶段不激活此条目",
                checked = draft.excludeRecursion,
                onCheckedChange = { onDraftChange(draft.copy(excludeRecursion = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "阻止触发递归",
                subtitle = "此条目的内容不触发其他条目",
                checked = draft.preventRecursion,
                onCheckedChange = { onDraftChange(draft.copy(preventRecursion = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "标题加入内容",
                subtitle = "把标题也拼到注入内容前面",
                checked = draft.addMemo,
                onCheckedChange = { onDraftChange(draft.copy(addMemo = it)) },
            )
            NutTavernGroupDivider()
            // 以下 4 个为条目级覆写字段:留空 = 使用全局(书级)设置;显式设置 = 覆盖书级默认。
            // 对齐酒馆 world-info.js (caseSensitive: 269 / scanDepth: 280 /
            // matchWholeWords: 347 / useGroupScoring: 119) 的 `entry.xxx ?? world_info_xxx` 回落语义。
            // 文案照抄酒馆 zh-cn.json (Scan Depth / Case-Sensitive / Whole Words / Group Scoring /
            // Use global setting / Use global / Yes / No)。
            NutTavernNumericField(
                label = "扫描深度",
                value = draft.entryScanDepth,
                onValueChange = { onDraftChange(draft.copy(entryScanDepth = it)) },
                parser = NumericParser.IntParser,
                placeholder = "使用全局设置",
                min = 0,
                max = 1000,
                nullable = true,
            )
            NutTavernGroupDivider()
            NutTavernEnumRow<Boolean?>(
                label = "区分大小写",
                value = draft.entryCaseSensitive,
                options = listOf(
                    null to "使用全局",
                    true to "是",
                    false to "否",
                ),
                onSelect = { onDraftChange(draft.copy(entryCaseSensitive = it)) },
            )
            NutTavernGroupDivider()
            NutTavernEnumRow<Boolean?>(
                label = "完整单词",
                value = draft.entryMatchWholeWords,
                options = listOf(
                    null to "使用全局",
                    true to "是",
                    false to "否",
                ),
                onSelect = { onDraftChange(draft.copy(entryMatchWholeWords = it)) },
            )
            NutTavernGroupDivider()
            NutTavernEnumRow<Boolean?>(
                label = "组评分",
                value = draft.entryUseGroupScoring,
                options = listOf(
                    null to "使用全局",
                    true to "是",
                    false to "否",
                ),
                onSelect = { onDraftChange(draft.copy(entryUseGroupScoring = it)) },
            )
        }
    }
}

// ── 扫描范围扩展折叠区(内部持有展开状态) ──

@Composable
private fun ScanScopeSectionStateful(
    draft: LorebookEntry,
    onDraftChange: (LorebookEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    NutTavernExpandableHeader(
        title = "扫描范围扩展",
        expanded = expanded,
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        NutTavernGroupSection {
            NutTavernSwitchRow(
                label = "匹配用户身份描述",
                subtitle = "关键词额外扫描当前用户身份的描述文本",
                checked = draft.matchPersonaDescription,
                onCheckedChange = { onDraftChange(draft.copy(matchPersonaDescription = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "匹配角色描述",
                subtitle = "关键词额外扫描角色 description 字段",
                checked = draft.matchCharacterDescription,
                onCheckedChange = { onDraftChange(draft.copy(matchCharacterDescription = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "匹配角色性格",
                subtitle = "关键词额外扫描角色 personality 字段",
                checked = draft.matchCharacterPersonality,
                onCheckedChange = { onDraftChange(draft.copy(matchCharacterPersonality = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "匹配角色深度提示",
                subtitle = "关键词额外扫描角色 depth prompt",
                checked = draft.matchCharacterDepthPrompt,
                onCheckedChange = { onDraftChange(draft.copy(matchCharacterDepthPrompt = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "匹配场景",
                subtitle = "关键词额外扫描角色 scenario 字段",
                checked = draft.matchScenario,
                onCheckedChange = { onDraftChange(draft.copy(matchScenario = it)) },
            )
            NutTavernGroupDivider()
            NutTavernSwitchRow(
                label = "匹配创作者备注",
                subtitle = "关键词额外扫描角色 creator_notes 字段",
                checked = draft.matchCreatorNotes,
                onCheckedChange = { onDraftChange(draft.copy(matchCreatorNotes = it)) },
            )
        }
    }
}
