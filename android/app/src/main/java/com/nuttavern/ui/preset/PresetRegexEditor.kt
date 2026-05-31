package com.nuttavern.ui.preset

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.regex.RegexScript
import com.nuttavern.ui.components.EntityAction
import com.nuttavern.ui.components.NutTavernEntityActionsSheet
import com.nuttavern.ui.regex.RegexScriptFormBody
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * 预设内嵌正则脚本编辑器(PRESET 作用域)。
 *
 * 与 [com.nuttavern.ui.character.CharacterRegexEditor] 结构一致,**共用字段表单**
 * ([RegexScriptFormBody]),走**预设草稿的写回路径**:
 * - 不持有 ViewModel,纯 UI;
 * - 列表 + 单条编辑组成两层覆盖式 Scaffold,改完点保存回到预设编辑页主表单
 *   (由 preset 草稿一同 upsert),不直接落库。
 *
 * 数据源 = [com.nuttavern.data.preset.Preset.extensions] 的 `regex_scripts` 节点(不进 Preset
 * 顶层 schema),读写由 [com.nuttavern.data.preset.presetRegexScripts] /
 * [com.nuttavern.data.preset.withPresetRegexScripts] 完成,与 PromptComposer / ChatViewModel
 * 同口径,导入导出 round-trip 与酒馆 JSON 兼容。
 *
 * 列表交互简化(对齐 CharacterRegexEditor):
 * - 不做拖排序(PRESET 脚本数量通常很少;导入导出按数组顺序保留即可);
 * - 行内 Switch 切启用;
 * - 点击行进编辑;
 * - 顶栏 "+" 新建 / 导入按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetRegexEditor(
    scripts: List<RegexScript>,
    onChange: (List<RegexScript>) -> Unit,
    onBack: () -> Unit,
) {
    var editingScript by remember { mutableStateOf<RegexScript?>(null) }
    var longPressScript by remember { mutableStateOf<RegexScript?>(null) }
    BackHandler { onBack() }

    val context = LocalContext.current
    val regexJson = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }

    // 导入预设内嵌正则:解析酒馆 JSON(单对象/数组),重分配 UUID 后平铺追加到当前列表。
    // PRESET 无组概念,单条/多条都直接追加。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText()
                ?: return@rememberLauncherForActivityResult
            val imported: List<RegexScript> = try {
                regexJson.decodeFromString<List<RegexScript>>(text)
            } catch (_: Exception) {
                listOf(regexJson.decodeFromString<RegexScript>(text))
            }
            val reassigned = imported.map { it.copy(id = UUID.randomUUID().toString()) }
            onChange(scripts + reassigned)
            android.widget.Toast.makeText(
                context,
                "已导入 ${reassigned.size} 条正则",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "导入失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("预设正则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回预设编辑")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Lucide.FileDown, contentDescription = "导入正则")
                    }
                    IconButton(
                        onClick = {
                            editingScript = RegexScript(
                                id = UUID.randomUUID().toString(),
                                scriptName = "",
                            )
                        },
                    ) {
                        Icon(Lucide.Plus, contentDescription = "新建正则")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "该预设还没有内嵌正则,点 + 新建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(scripts, key = { it.id }) { script ->
                    PresetRegexRow(
                        script = script,
                        onClick = { editingScript = script },
                        onLongClick = { longPressScript = script },
                        onToggleEnabled = { enabled ->
                            onChange(
                                scripts.map {
                                    if (it.id == script.id) it.copy(disabled = !enabled) else it
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    val current = editingScript
    if (current != null) {
        PresetRegexScriptForm(
            initial = current,
            // 列表里已有同 id 项 → 编辑路径允许删除;只在内存里的新建草稿 → 不显示删除
            // (新建未保存就退出 = 取消)。
            allowDelete = scripts.any { it.id == current.id },
            onSave = { edited ->
                val exists = scripts.any { it.id == edited.id }
                val nextScripts = if (exists) {
                    scripts.map { if (it.id == edited.id) edited else it }
                } else {
                    scripts + edited
                }
                onChange(nextScripts)
                editingScript = null
            },
            onDelete = {
                onChange(scripts.filterNot { it.id == current.id })
                editingScript = null
            },
            onBack = { editingScript = null },
        )
    }

    val longPressed = longPressScript
    if (longPressed != null) {
        NutTavernEntityActionsSheet(
            title = longPressed.scriptName.ifBlank { "未命名规则" },
            actions = listOf(
                EntityAction(
                    icon = Lucide.Copy,
                    title = "复制",
                    onClick = {
                        val copy = longPressed.copy(
                            id = UUID.randomUUID().toString(),
                            scriptName = longPressed.scriptName + "*",
                        )
                        val index = scripts.indexOfFirst { it.id == longPressed.id }
                        val nextScripts = scripts.toMutableList().apply {
                            if (index >= 0) add(index + 1, copy) else add(copy)
                        }
                        onChange(nextScripts)
                    },
                ),
                EntityAction(
                    icon = Lucide.Trash2,
                    title = "删除",
                    destructive = true,
                    onClick = { onChange(scripts.filterNot { it.id == longPressed.id }) },
                ),
            ),
            onDismiss = { longPressScript = null },
        )
    }
}

@Composable
private fun PresetRegexRow(
    script: RegexScript,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    com.nuttavern.ui.components.NutTavernEntityCard(
        title = script.scriptName,
        titleFallback = "未命名规则",
        subtitle = script.findRegex.takeIf { it.isNotBlank() },
        onClick = onClick,
        onLongClick = onLongClick,
        trailing = {
            com.nuttavern.ui.components.NutTavernEntitySwitch(
                checked = !script.disabled,
                onCheckedChange = onToggleEnabled,
            )
        },
    )
}

/**
 * 单条脚本编辑覆盖层(全屏 Scaffold)。结构与 [com.nuttavern.ui.regex.RegexEditScreen] 相同,
 * 但走"草稿 + 保存回调"而非"直接 upsert 仓库"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetRegexScriptForm(
    initial: RegexScript,
    allowDelete: Boolean,
    onSave: (RegexScript) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val isDirty = draft != initial

    fun attemptBack() {
        if (isDirty) showUnsavedDialog = true else onBack()
    }
    BackHandler { attemptBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(if (initial.scriptName.isBlank()) "新增预设正则" else "编辑预设正则")
                },
                navigationIcon = {
                    IconButton(onClick = ::attemptBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(draft) }, enabled = isDirty) {
                        Icon(Lucide.Check, contentDescription = "保存")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        RegexScriptFormBody(
            draft = draft,
            onDraftChange = { draft = it },
            allowDelete = allowDelete,
            onDeleteRequest = { showDeleteDialog = true },
            contentPadding = padding,
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除该规则?") },
            text = { Text("从该预设的内嵌正则列表中移除,无法在此页恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("保存改动?") },
            text = { Text("当前规则还有未保存的修改,直接退出将丢失这些修改。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onSave(draft)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onBack()
                    },
                ) { Text("放弃") }
            },
        )
    }
}
