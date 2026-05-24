package com.nuttavern.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PlugZap
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Trash2
import com.nuttavern.data.model.ClaudePromptCacheTtl
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.Provider
import com.nuttavern.data.model.ProviderProtocol
import com.nuttavern.ui.chat.ProviderIconBadge
import com.nuttavern.ui.components.NutTavernGroupCard
import com.nuttavern.ui.components.NutTavernGroupDivider
import com.nuttavern.ui.components.NutTavernModelCard
import com.nuttavern.ui.viewmodel.ProviderSettingsViewModel
import kotlinx.coroutines.launch

/**
 * 单个 Provider 详情页。
 *
 * - 顶栏左侧返回 + Provider 名称;右侧"删除"图标(危险红);
 * - 中间 [TabRow] 切换"配置 / 模型";
 * - 配置 Tab 表单顶部弹"测试连接"按钮(本轮先占位);
 * - 模型 Tab 底部 FAB"添加模型"。
 *
 * Provider 协议无法在 detail 页内切换:协议变更等价于换模型契约,会让所有已存模型失效;
 * 当前的取舍是"协议在新建时确定,后续要换协议就删了重建"。这条用户体验有损但稳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    providerId: String,
    onBack: () -> Unit,
    viewModel: ProviderSettingsViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsState()
    val provider = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }

    if (provider == null) {
        ProviderDetailMissing(onBack = onBack)
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingDeleteDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var creatingModel by remember { mutableStateOf(false) }
    var pickingFromRemote by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // 远端模型列表 lift 到详情页:进入模型 Tab 时拉一次,后续打开"管理模型"抽屉直接复用,
    // 不每次打开都重拉。失败时由抽屉的"重试"按钮触发 reload。
    var remoteModelsState by remember(provider.id) {
        mutableStateOf<RemoteModelsState>(RemoteModelsState.Loading)
    }
    suspend fun loadRemoteModels() {
        remoteModelsState = RemoteModelsState.Loading
        remoteModelsState = viewModel.fetchRemoteModelIds(provider.id).fold(
            onSuccess = { ids -> RemoteModelsState.Loaded(ids) },
            onFailure = { e -> RemoteModelsState.Failed(e.message.orEmpty().ifBlank { "拉取失败" }) },
        )
    }
    LaunchedEffect(provider.id, selectedTab) {
        // 第一次切到模型 Tab(1)时拉一次。再次切回不重拉,显式重试需用户点重试。
        if (selectedTab == 1 && remoteModelsState is RemoteModelsState.Loading) {
            loadRemoteModels()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProviderIconBadge(provider = provider, modifier = Modifier.size(28.dp))
                        Text(
                            text = provider.name.ifBlank { "未命名提供商" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (testingConnection) return@IconButton
                            testingConnection = true
                            coroutineScope.launch {
                                val result = viewModel.testConnection(provider.id)
                                testingConnection = false
                                snackbarHostState.showSnackbar(
                                    result.fold(
                                        onSuccess = { it },
                                        onFailure = { e ->
                                            e.message.orEmpty().ifBlank { "连接失败" }
                                        },
                                    ),
                                )
                            }
                        },
                        enabled = !testingConnection,
                    ) {
                        if (testingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Lucide.PlugZap,
                                contentDescription = "测试连接",
                            )
                        }
                    }
                    IconButton(onClick = { pendingDeleteDialog = true }) {
                        Icon(
                            imageVector = Lucide.Trash2,
                            contentDescription = "删除提供商",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (selectedTab == 1) {
                // 文案"管理模型 N/M":N=本地已添加;M=远端可用总数(仅 Loaded 状态有,其他状态只显示 N)。
                // 故意不带加号:这个 FAB 同时承担"添加 / 移除 / 查看"三态,加号会误导成只能加。
                val addedCount = provider.models.size
                val remoteTotal = (remoteModelsState as? RemoteModelsState.Loaded)?.modelIds?.size
                val countText = if (remoteTotal != null) "$addedCount / $remoteTotal" else "$addedCount"
                ExtendedFloatingActionButton(
                    onClick = { pickingFromRemote = true },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("管理模型")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = countText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    },
                    icon = {},
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {
            ProviderDetailTabs(
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )

            when (selectedTab) {
                0 -> ProviderConfigTab(
                    provider = provider,
                    onUpdateProvider = viewModel::updateProvider,
                )
                else -> ProviderModelsTab(
                    provider = provider,
                    onEditModel = { editingModel = it },
                    onDeleteModel = { model ->
                        viewModel.deleteModel(provider.id, model.id)
                    },
                )
            }
        }
    }

    if (pickingFromRemote) {
        AvailableModelsBottomSheet(
            provider = provider,
            state = remoteModelsState,
            onDismiss = { pickingFromRemote = false },
            onRetry = { coroutineScope.launch { loadRemoteModels() } },
            onAddModel = { modelId -> viewModel.addModelByModelId(provider.id, modelId) },
            onRemoveModel = { modelId -> viewModel.deleteModelByModelId(provider.id, modelId) },
            onSwitchToManual = {
                pickingFromRemote = false
                creatingModel = true
            },
        )
    }

    if (pendingDeleteDialog) {
        AlertDialog(
            onDismissRequest = { pendingDeleteDialog = false },
            title = { Text("删除提供商") },
            text = { Text("将删除「${provider.name}」及其下所有模型,操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteDialog = false
                        viewModel.deleteProvider(provider.id)
                        onBack()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (creatingModel) {
        EditModelDialog(
            initialModel = null,
            providerName = provider.name,
            protocol = currentProtocol(provider),
            onDismiss = { creatingModel = false },
            onConfirm = { newModel ->
                viewModel.addModel(provider.id, newModel)
                creatingModel = false
            },
            inferCapabilities = viewModel::inferCapabilities,
            newModelFromId = viewModel::newModel,
        )
    }

    editingModel?.let { target ->
        EditModelDialog(
            initialModel = target,
            providerName = provider.name,
            protocol = currentProtocol(provider),
            onDismiss = { editingModel = null },
            onConfirm = { updated ->
                viewModel.updateModel(provider.id, updated)
                editingModel = null
            },
            inferCapabilities = viewModel::inferCapabilities,
            newModelFromId = viewModel::newModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailMissing(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("提供商不存在") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "提供商已被删除或不存在",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderDetailTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    TabRow(
        selectedTabIndex = selected,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selected]),
            )
        },
    ) {
        Tab(
            selected = selected == 0,
            onClick = { onSelect(0) },
            text = { Text("配置") },
        )
        Tab(
            selected = selected == 1,
            onClick = { onSelect(1) },
            text = { Text("模型") },
        )
    }
}

@Composable
private fun ProviderConfigTab(
    provider: Provider,
    onUpdateProvider: (Provider) -> Unit,
) {
    var pendingProtocol by remember(provider.id) { mutableStateOf<ProviderProtocol?>(null) }
    var iconPickerOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "protocol") {
            ProviderProtocolSelector(
                current = currentProtocol(provider),
                onSelect = { target ->
                    if (target == currentProtocol(provider)) return@ProviderProtocolSelector
                    val needsConfirm = provider.models.isNotEmpty() ||
                        provider.baseUrl.isNotBlank() ||
                        provider.apiKey.isNotBlank()
                    if (needsConfirm) {
                        pendingProtocol = target
                    } else {
                        onUpdateProvider(provider.withProtocol(target))
                    }
                },
            )
        }
        item(key = "common") {
            CommonProviderFields(
                provider = provider,
                onUpdate = onUpdateProvider,
                onPickIcon = { iconPickerOpen = true },
            )
        }
        when (provider) {
            is Provider.OpenAI -> item(key = "openai") {
                OpenAiProviderFields(provider = provider, onUpdate = onUpdateProvider)
            }
            is Provider.Google -> {
                // Google 协议没有独立配置字段(Vertex AI 已删除),通用字段已经覆盖。
            }
            is Provider.Claude -> item(key = "claude") {
                ClaudeProviderFields(provider = provider, onUpdate = onUpdateProvider)
            }
        }
    }

    if (iconPickerOpen) {
        ProviderIconPickerSheet(
            provider = provider,
            onDismiss = { iconPickerOpen = false },
            onSelectIconKey = { newKey ->
                onUpdateProvider(provider.withIconKey(newKey))
            },
        )
    }

    pendingProtocol?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingProtocol = null },
            title = { Text("切换协议") },
            text = {
                Text(
                    "切换到 ${target.displayLabel} 协议会清空当前的 Base URL 和模型列表" +
                        "(API Key 会保留)。是否继续?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateProvider(provider.withProtocol(target))
                    pendingProtocol = null
                }) {
                    Text("切换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProtocol = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun currentProtocol(provider: Provider): ProviderProtocol = when (provider) {
    is Provider.OpenAI -> ProviderProtocol.OPENAI
    is Provider.Google -> ProviderProtocol.GOOGLE
    is Provider.Claude -> ProviderProtocol.CLAUDE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderProtocolSelector(
    current: ProviderProtocol,
    onSelect: (ProviderProtocol) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ProviderProtocol.entries.forEachIndexed { index, protocol ->
            SegmentedButton(
                selected = current == protocol,
                onClick = { onSelect(protocol) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ProviderProtocol.entries.size,
                ),
                label = { Text(protocol.displayLabel) },
            )
        }
    }
}

@Composable
private fun CommonProviderFields(
    provider: Provider,
    onUpdate: (Provider) -> Unit,
    onPickIcon: () -> Unit,
) {
    NutTavernGroupCard {
        ProviderIconRow(
            provider = provider,
            onClick = onPickIcon,
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ProviderTextRow(
            label = "名称",
            value = provider.name,
            onValueChange = { onUpdate(provider.withName(it)) },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ProviderTextRow(
            label = "API Key",
            value = provider.apiKey,
            secret = true,
            onValueChange = { onUpdate(provider.withApiKey(it)) },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ProviderTextRow(
            label = "Base URL",
            value = provider.baseUrl,
            keyboardType = KeyboardType.Uri,
            onValueChange = { next ->
                onUpdate(
                    when (provider) {
                        is Provider.OpenAI -> provider.copy(baseUrl = next)
                        is Provider.Google -> provider.copy(baseUrl = next)
                        is Provider.Claude -> provider.copy(baseUrl = next)
                    },
                )
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ProviderToggleRow(
            label = "启用",
            description = "关闭后此提供商不会出现在模型选择器里",
            checked = provider.enabled,
            onCheckedChange = { onUpdate(provider.withEnabled(it)) },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        CustomPromptPostProcessingRow(
            value = provider.customPromptPostProcessing,
            onSelect = { picked ->
                onUpdate(
                    when (provider) {
                        is Provider.OpenAI -> provider.copy(customPromptPostProcessing = picked)
                        is Provider.Google -> provider.copy(customPromptPostProcessing = picked)
                        is Provider.Claude -> provider.copy(customPromptPostProcessing = picked)
                    },
                )
            },
        )
    }
}

@Composable
private fun OpenAiProviderFields(
    provider: Provider.OpenAI,
    onUpdate: (Provider) -> Unit,
) {
    NutTavernGroupCard {
        ProviderTextRow(
            label = "Chat Completions 路径",
            value = provider.chatCompletionsPath,
            onValueChange = { onUpdate(provider.copy(chatCompletionsPath = it)) },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ProviderToggleRow(
            label = "Responses API",
            description = "启用后请求体改用 OpenAI Responses 格式;当前实现仍走 Chat Completions,留作后续轮接入",
            checked = provider.useResponsesApi,
            onCheckedChange = { onUpdate(provider.copy(useResponsesApi = it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeProviderFields(
    provider: Provider.Claude,
    onUpdate: (Provider) -> Unit,
) {
    NutTavernGroupCard {
        ProviderToggleRow(
            label = "提示缓存",
            description = "在 system 与最近一条消息上打 cache_control 断点,后续轮请求复用前缀,降低费用与延迟",
            checked = provider.promptCaching,
            onCheckedChange = { onUpdate(provider.copy(promptCaching = it)) },
        )
        if (provider.promptCaching) {
            NutTavernGroupDivider()
            ClaudeCacheTtlRow(
                ttl = provider.promptCacheTtl,
                onSelect = { onUpdate(provider.copy(promptCacheTtl = it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeCacheTtlRow(
    ttl: ClaudePromptCacheTtl,
    onSelect: (ClaudePromptCacheTtl) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "缓存有效期",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "1 小时为 beta 能力,请求会附带 anthropic-beta 头",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ClaudePromptCacheTtl.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = ttl == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ClaudePromptCacheTtl.entries.size,
                    ),
                    label = {
                        Text(
                            when (value) {
                                ClaudePromptCacheTtl.FIVE_MINUTES -> "5 分钟"
                                ClaudePromptCacheTtl.ONE_HOUR -> "1 小时"
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderIconRow(
    provider: Provider,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "图标",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (provider.iconKey.isBlank()) "自动推断" else "已自定义",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProviderIconBadge(
            provider = provider,
            modifier = Modifier.size(32.dp),
        )
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderTextRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
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
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProviderModelsTab(
    provider: Provider,
    onEditModel: (Model) -> Unit,
    onDeleteModel: (Model) -> Unit,
) {
    if (provider.models.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "还没有模型,点右下角管理",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(provider.models, key = { it.id }) { model ->
            SwipeToDeleteModelCard(
                provider = provider,
                model = model,
                onEdit = { onEditModel(model) },
                onDelete = { onDeleteModel(model) },
            )
        }
    }
}

/**
 * 模型卡片左滑露出垃圾桶。完全划过(EndToStart)即认为确认删除。
 *
 * 取舍:
 * - 不弹二次确认对话框。原因:卡片删除影响很小(模型可以从远端再加回来),弹窗会破坏滑动手势的
 *   "确认即生效"语义。如果担心误删,以后引入"删除后 5 秒撤销 Snackbar"。
 * - 滑动背景用 `errorContainer`,垃圾桶图标用 `onErrorContainer`,符合危险动作配色约束。
 * - `confirmValueChange` 接住 EndToStart 状态,执行删除并返回 true(允许真正消失)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteModelCard(
    provider: Provider,
    model: Model,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // 仅左滑(EndToStart)露出红色背景;其他方向不允许,这里也不绘制内容。
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Icon(
                            imageVector = Lucide.Trash2,
                            contentDescription = "删除模型",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        NutTavernModelCard(
            title = model.resolvedDisplayName,
            onClick = onEdit,
            leading = {
                ProviderIconBadge(
                    provider = provider,
                    modelName = model.modelId,
                )
            },
            trailing = {
                Icon(
                    imageVector = Lucide.SquarePen,
                    contentDescription = "编辑",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            chips = { ModelCapabilityChips(model = model) },
        )
    }
}

@Composable
private fun CustomPromptPostProcessingRow(
    value: com.nuttavern.data.model.CustomPromptPostProcessing,
    onSelect: (com.nuttavern.data.model.CustomPromptPostProcessing) -> Unit,
) {
    var pickerOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { pickerOpen = true },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "自定义提示词后处理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = customPromptPostProcessingLabel(value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "发请求前对消息列表的合并 / 改写策略,主要给中转站使用,默认 None",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择后处理策略") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.nuttavern.data.model.CustomPromptPostProcessing.entries.forEach { mode ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (mode == value) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (mode == value) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            onClick = {
                                onSelect(mode)
                                pickerOpen = false
                            },
                        ) {
                            Text(
                                text = customPromptPostProcessingLabel(mode),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { pickerOpen = false }) {
                    Text("关闭")
                }
            },
        )
    }
}

private fun customPromptPostProcessingLabel(
    mode: com.nuttavern.data.model.CustomPromptPostProcessing,
): String = when (mode) {
    com.nuttavern.data.model.CustomPromptPostProcessing.NONE -> "None(默认,不改写)"
    com.nuttavern.data.model.CustomPromptPostProcessing.CLAUDE -> "Claude 风格"
    com.nuttavern.data.model.CustomPromptPostProcessing.MERGE -> "Merge"
    com.nuttavern.data.model.CustomPromptPostProcessing.MERGE_TOOLS -> "Merge + Tools"
    com.nuttavern.data.model.CustomPromptPostProcessing.SEMI -> "Semi"
    com.nuttavern.data.model.CustomPromptPostProcessing.SEMI_TOOLS -> "Semi + Tools"
    com.nuttavern.data.model.CustomPromptPostProcessing.STRICT -> "Strict"
    com.nuttavern.data.model.CustomPromptPostProcessing.STRICT_TOOLS -> "Strict + Tools"
    com.nuttavern.data.model.CustomPromptPostProcessing.SINGLE -> "Single"
}

