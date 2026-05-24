package com.nuttavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nuttavern.data.model.BuiltInTool
import com.nuttavern.data.model.CustomBody
import com.nuttavern.data.model.CustomHeader
import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.ModelAbility
import com.nuttavern.data.model.ProviderProtocol
import com.nuttavern.ui.components.NutTavernGroupCard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2

/**
 * 编辑(或新建)单个模型的底部抽屉。
 *
 * - [initialModel] = null 表示"新建":先让用户填模型 ID,然后调 [newModelFromId] 拿一份
 *   带能力推断的初始 Model;
 * - [initialModel] != null 表示"编辑":模型 ID 不允许改(改了 = 创建另一个模型);
 * - 三 Tab:基本设置 / 高级设置 / 内置工具。本轮高级设置只展示占位入口,内置工具
 *   只针对 Gemini 协议显示开关,其余 Provider 显示提示文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditModelDialog(
    initialModel: Model?,
    providerName: String,
    protocol: ProviderProtocol,
    onDismiss: () -> Unit,
    onConfirm: (Model) -> Unit,
    inferCapabilities: (Model) -> Model,
    newModelFromId: (String) -> Model,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isCreate = initialModel == null

    var newModelIdInput by remember { mutableStateOf("") }
    // 真正的可编辑模型(在新建场景被首次填入 modelId 后才创建出来)。
    var workingModel by remember(initialModel?.id) { mutableStateOf(initialModel) }
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = if (isCreate) "添加模型 - $providerName" else "编辑模型",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )

            if (workingModel == null) {
                NewModelFirstStep(
                    modelIdInput = newModelIdInput,
                    onChangeModelId = { newModelIdInput = it },
                    onConfirmCreate = {
                        if (newModelIdInput.isNotBlank()) {
                            workingModel = newModelFromId(newModelIdInput)
                        }
                    },
                    onCancel = onDismiss,
                )
            } else {
                EditModelTabs(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (selectedTab) {
                        0 -> item("basic") {
                            BasicSettingsBlock(
                                model = workingModel!!,
                                onChange = { workingModel = it },
                                onReinfer = { workingModel = inferCapabilities(workingModel!!) },
                            )
                        }
                        1 -> item("advanced") {
                            AdvancedSettingsBlock(
                                model = workingModel!!,
                                protocol = protocol,
                                onChange = { workingModel = it },
                            )
                        }
                        else -> item("builtin") {
                            BuiltInToolsBlock(
                                model = workingModel!!,
                                protocol = protocol,
                                onChange = { workingModel = it },
                            )
                        }
                    }
                }
                EditModelActions(
                    canConfirm = workingModel?.modelId?.isNotBlank() == true,
                    onCancel = onDismiss,
                    onConfirm = {
                        workingModel?.let(onConfirm)
                    },
                )
            }
        }
    }
}

@Composable
private fun NewModelFirstStep(
    modelIdInput: String,
    onChangeModelId: (String) -> Unit,
    onConfirmCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    NutTavernGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "模型 ID",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = modelIdInput,
                onValueChange = onChangeModelId,
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "请填写真实下发到 API 的模型 id,如 gpt-5.5 / claude-opus-4-7 / gemini-3.1-pro-preview。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
    ) {
        TextButton(onClick = onCancel) { Text("取消") }
        TextButton(onClick = onConfirmCreate) { Text("下一步") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditModelTabs(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
) {
    // 用胶囊形 SingleChoiceSegmentedButtonRow 代替 TabRow:
    // - TabRow 默认底部一根 indicator 线,在抽屉里视觉太"硬",和周边的 GroupCard 圆角风格冲突;
    // - SegmentedButton 自带胶囊圆角(SegmentedButtonDefaults.itemShape),与设计系统一致;
    // - 此处不需要"自由滚动 / 无限 Tab"语义,SegmentedButton 表达"三选一"反而更准确。
    val labels = listOf("基本设置", "高级设置", "内置工具")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selectedTab == index,
                onClick = { onSelectTab(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasicSettingsBlock(
    model: Model,
    onChange: (Model) -> Unit,
    onReinfer: () -> Unit,
) {
    // "基本设置"全部塞进一个 NutTavernGroupCard:模型 ID(只读)→ 显示名 → 类型 → 输入模态
    // → 输出模态 → 能力。同类配置同一个卡片是设计系统强约束(AGENTS.md 组件规范第 2 条)。
    NutTavernGroupCard {
        EditField(label = "模型 ID(只读)", value = model.modelId, readOnly = true) {}
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        EditField(label = "显示名", value = model.displayName) { onChange(model.copy(displayName = it)) }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        SectionHint(text = "输入模态")
        ModalitySegmented(
            modalities = model.inputModalities,
            onChange = { onChange(model.copy(inputModalities = it)) },
        )

        SectionHint(text = "输出模态")
        ModalitySegmented(
            modalities = model.outputModalities,
            onChange = { onChange(model.copy(outputModalities = it)) },
        )

        SectionHint(text = "能力")
        AbilitySegmented(
            abilities = model.abilities,
            onChange = { onChange(model.copy(abilities = it)) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
        ) {
            OutlinedButton(onClick = onReinfer) { Text("重新推断能力") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalitySegmented(
    modalities: List<Modality>,
    onChange: (List<Modality>) -> Unit,
) {
    val items = listOf(Modality.TEXT to "文本", Modality.IMAGE to "图像")
    MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items.forEachIndexed { index, (modality, label) ->
            SegmentedButton(
                checked = modality in modalities,
                onCheckedChange = { checked ->
                    val next = if (checked) modalities + modality else modalities - modality
                    val normalized = next.distinct().ifEmpty { listOf(Modality.TEXT) }
                    onChange(normalized)
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = items.size),
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbilitySegmented(
    abilities: List<ModelAbility>,
    onChange: (List<ModelAbility>) -> Unit,
) {
    val items = listOf(ModelAbility.TOOL to "工具", ModelAbility.REASONING to "推理")
    MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items.forEachIndexed { index, (ability, label) ->
            SegmentedButton(
                checked = ability in abilities,
                onCheckedChange = { checked ->
                    val next = if (checked) abilities + ability else abilities - ability
                    onChange(next.distinct())
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = items.size),
                label = { Text(label) },
            )
        }
    }
}

/**
 * 高级设置 Tab。三块功能合到**单个** [NutTavernGroupCard],用 SectionHint + Divider 区隔。
 *
 * - **提供商重写**:开关 + 字段(URL / API Key / 路径),空值 = 继承父 Provider。运行时由
 *   ChatApiClient.effectiveProvider 接入。
 * - **自定义 Headers**:键值对,运行时由 ChatApiClient.applyCustomHeaders 注入到 OkHttp builder。
 * - **自定义 Body**:key + JSON 字面量,JSONTokener 解析失败按字符串透传。
 *
 * 输入框统一用 M3 [androidx.compose.material3.OutlinedTextField] 风格,所有字段都有
 * 可见边框 / 浮动标签,与设计系统其他位置(注:Common Provider Fields 当前还在用
 * BasicTextField,后续单独迁移)。
 */
@Composable
private fun AdvancedSettingsBlock(
    model: Model,
    protocol: ProviderProtocol,
    onChange: (Model) -> Unit,
) {
    NutTavernGroupCard {
        SectionHint(text = "提供商重写")
        ProviderOverrideEditor(
            model = model,
            protocol = protocol,
            onChange = onChange,
        )

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        SectionHint(text = "自定义 Headers")
        CustomHeadersEditor(
            headers = model.customHeaders,
            onChange = { onChange(model.copy(customHeaders = it)) },
        )

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        SectionHint(text = "自定义 Body")
        CustomBodyEditor(
            bodies = model.customBodies,
            onChange = { onChange(model.copy(customBodies = it)) },
        )
    }
}

/**
 * 模型级 Provider Override 编辑器。
 *
 * 取舍:
 * - Override 的协议**必须与父协议同型**:[com.nuttavern.network.ChatApiClient.effectiveProvider]
 *   在 mergeOnto 时按父协议匹配,异型 Override 会被静默忽略;所以这里按入参 [protocol]
 *   直接建同型空 Override,UI 上不暴露"协议"选项;
 * - 用户没填的字段(空字符串)在 ChatApiClient.mergeOnto 中视为继承父值;
 * - Switch 关闭时把 providerOverride 设回 null。
 */
@Composable
private fun ProviderOverrideEditor(
    model: Model,
    protocol: ProviderProtocol,
    onChange: (Model) -> Unit,
) {
    val enabled = model.providerOverride != null
    // 关闭 Switch 时把当前 override 暂存,避免用户误关后重开要重新输入字段。
    // 跟随 model.id 重置:换模型时清空缓存。
    var stashedOverride by remember(model.id) {
        mutableStateOf<com.nuttavern.data.model.Provider?>(null)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "启用提供商重写",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "为该模型单独配置 endpoint / API Key,空字段继承父提供商",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked) {
                    // 优先恢复用户上次填过的 override(若协议同型),否则建空 Override。
                    val restored = stashedOverride?.takeIf { it.matchesProtocol(protocol) }
                    onChange(model.copy(providerOverride = restored ?: createEmptyOverride(model.id, protocol)))
                } else {
                    stashedOverride = model.providerOverride
                    onChange(model.copy(providerOverride = null))
                }
            },
        )
    }

    if (enabled) {
        val override = model.providerOverride ?: return
        OutlinedField(
            label = "API Key",
            value = override.apiKey,
            onValueChange = { newKey ->
                onChange(model.copy(providerOverride = override.withApiKey(newKey)))
            },
        )
        OutlinedField(
            label = "Base URL",
            value = override.baseUrl,
            onValueChange = { newUrl ->
                val updated = when (override) {
                    is com.nuttavern.data.model.Provider.OpenAI -> override.copy(baseUrl = newUrl)
                    is com.nuttavern.data.model.Provider.Google -> override.copy(baseUrl = newUrl)
                    is com.nuttavern.data.model.Provider.Claude -> override.copy(baseUrl = newUrl)
                }
                onChange(model.copy(providerOverride = updated))
            },
        )
        // OpenAI 协议特有的 chatCompletionsPath:让用户能在 Override 上把路径切到 /responses。
        if (override is com.nuttavern.data.model.Provider.OpenAI) {
            OutlinedField(
                label = "Chat Completions 路径",
                value = override.chatCompletionsPath,
                onValueChange = { path ->
                    onChange(model.copy(providerOverride = override.copy(chatCompletionsPath = path)))
                },
            )
        }
    }
}

@Composable
private fun CustomHeadersEditor(
    headers: List<CustomHeader>,
    onChange: (List<CustomHeader>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        headers.forEachIndexed { index, header ->
            KeyValueEditorRow(
                key = header.name,
                value = header.value,
                keyLabel = "Header 名",
                valueLabel = "值",
                onKeyChange = { newKey ->
                    onChange(headers.toMutableList().also { it[index] = header.copy(name = newKey) })
                },
                onValueChange = { newValue ->
                    onChange(headers.toMutableList().also { it[index] = header.copy(value = newValue) })
                },
                onRemove = {
                    onChange(headers.toMutableList().also { it.removeAt(index) })
                },
            )
        }
        AddRowButton(
            label = "添加 Header",
            onClick = { onChange(headers + CustomHeader()) },
        )
    }
}

@Composable
private fun CustomBodyEditor(
    bodies: List<CustomBody>,
    onChange: (List<CustomBody>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        bodies.forEachIndexed { index, body ->
            KeyValueEditorRow(
                key = body.key,
                value = body.jsonValue,
                keyLabel = "Body 字段",
                valueLabel = "JSON 字面量",
                onKeyChange = { newKey ->
                    onChange(bodies.toMutableList().also { it[index] = body.copy(key = newKey) })
                },
                onValueChange = { newValue ->
                    onChange(bodies.toMutableList().also { it[index] = body.copy(jsonValue = newValue) })
                },
                onRemove = {
                    onChange(bodies.toMutableList().also { it.removeAt(index) })
                },
            )
        }
        AddRowButton(
            label = "添加 Body 字段",
            onClick = { onChange(bodies + CustomBody()) },
        )
    }
}

/**
 * 通用 key-value 编辑行。两个 OutlinedField 上下排 + 删除按钮。
 *
 * 横排 key + value 在窄屏上挤,改成上下两行 + 末尾删除图标在 key 行右侧。
 */
@Composable
private fun KeyValueEditorRow(
    key: String,
    value: String,
    keyLabel: String,
    valueLabel: String,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedField(
                label = keyLabel,
                value = key,
                modifier = Modifier.weight(1f),
                onValueChange = onKeyChange,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        OutlinedField(
            label = valueLabel,
            value = value,
            modifier = Modifier.fillMaxWidth(),
            onValueChange = onValueChange,
        )
    }
}

/**
 * 项目通用的有边框输入框。M3 OutlinedTextField 直挂,只裁定 modifier / 单行 / 紧凑高度,
 * 视觉上保证项目里所有可见边框输入框风格一致。
 */
@Composable
private fun OutlinedField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AddRowButton(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
    ) {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

/**
 * 内置工具 Tab。
 *
 * - Gemini(Google 协议)真接入三个 Switch,写到 [Model.builtInTools];
 * - 其他协议整组灰显,Switch 不可用,文案提示当前仅 Gemini 官方 API 支持;
 *
 * 数据写到 model 之后,ChatApiClient 在构造 Gemini 请求时读取。
 */
@Composable
private fun BuiltInToolsBlock(
    model: Model,
    protocol: ProviderProtocol,
    onChange: (Model) -> Unit,
) {
    val isGemini = protocol == ProviderProtocol.GOOGLE
    NutTavernGroupCard {
        if (!isGemini) {
            Text(
                text = "内置工具仅 Gemini 官方 API 支持,当前提供商协议下不可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        BuiltInToolSwitchRow(
            title = "搜索",
            description = "启用 Google 搜索集成",
            enabled = isGemini,
            checked = BuiltInTool.Search in model.builtInTools,
            onCheckedChange = { checked ->
                onChange(model.copy(
                    builtInTools = if (checked) {
                        model.builtInTools + BuiltInTool.Search
                    } else {
                        model.builtInTools - BuiltInTool.Search
                    },
                ))
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        BuiltInToolSwitchRow(
            title = "URL 上下文",
            description = "启用 URL 内容处理",
            enabled = isGemini,
            checked = BuiltInTool.UrlContext in model.builtInTools,
            onCheckedChange = { checked ->
                onChange(model.copy(
                    builtInTools = if (checked) {
                        model.builtInTools + BuiltInTool.UrlContext
                    } else {
                        model.builtInTools - BuiltInTool.UrlContext
                    },
                ))
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        BuiltInToolSwitchRow(
            title = "图像生成",
            description = "启用图像生成功能",
            enabled = isGemini,
            checked = BuiltInTool.ImageGeneration in model.builtInTools,
            onCheckedChange = { checked ->
                onChange(model.copy(
                    builtInTools = if (checked) {
                        model.builtInTools + BuiltInTool.ImageGeneration
                    } else {
                        model.builtInTools - BuiltInTool.ImageGeneration
                    },
                ))
            },
        )
    }
}

@Composable
private fun BuiltInToolSwitchRow(
    title: String,
    description: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = LocalTextStyle.current.copy(
                color = if (readOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
    )
}

@Composable
private fun EditModelActions(
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
    ) {
        TextButton(onClick = onCancel) { Text("取消") }
        TextButton(onClick = onConfirm, enabled = canConfirm) { Text("保存") }
    }
}

/**
 * 按父协议建一个同型空 Override。
 *
 * id 上加 `-override` 后缀只是给 ChatApiClient 日志便于追踪,merge 时会被父 id 覆盖。
 * 字段全部留空 → mergeOnto 走"ifBlank → 继承父值"分支。
 */
private fun createEmptyOverride(
    modelId: String,
    protocol: ProviderProtocol,
): com.nuttavern.data.model.Provider = when (protocol) {
    ProviderProtocol.OPENAI -> com.nuttavern.data.model.Provider.OpenAI(id = "$modelId-override")
    ProviderProtocol.GOOGLE -> com.nuttavern.data.model.Provider.Google(id = "$modelId-override")
    ProviderProtocol.CLAUDE -> com.nuttavern.data.model.Provider.Claude(id = "$modelId-override")
}

/**
 * 判断 Override 的 sealed 子类是否与父协议 [protocol] 同型。
 * 协议切换后旧的暂存 Override 不再适用,这里负责把它过滤掉。
 */
private fun com.nuttavern.data.model.Provider.matchesProtocol(protocol: ProviderProtocol): Boolean = when (protocol) {
    ProviderProtocol.OPENAI -> this is com.nuttavern.data.model.Provider.OpenAI
    ProviderProtocol.GOOGLE -> this is com.nuttavern.data.model.Provider.Google
    ProviderProtocol.CLAUDE -> this is com.nuttavern.data.model.Provider.Claude
}
