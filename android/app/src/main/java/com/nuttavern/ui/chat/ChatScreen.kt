package com.nuttavern.ui.chat

import androidx.activity.compose.BackHandler
import android.content.ClipData
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.PanelRightOpen
import com.nuttavern.data.model.Message
import com.nuttavern.ui.components.NutTavernComposerTokens
import com.nuttavern.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPersonaDetail: (personaId: String) -> Unit = {},
    onNavigateToCharacterDetail: (characterId: String) -> Unit = {},
    onNavigateToPresetDetail: (presetId: String) -> Unit = {},
    onNavigateToRegexDetail: (regexId: String) -> Unit = {},
    onNavigateToLorebookDetail: (lorebookId: String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.currentMessages.collectAsState()
    val currentId by viewModel.currentConversationId.collectAsState()
    val conversations by viewModel.conversationList.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val streamingReasoningContent by viewModel.streamingReasoningContent.collectAsState()
    val streamingReasoningDurationMillis by viewModel.streamingReasoningDurationMillis.collectAsState()
    val streamingConversationId by viewModel.streamingConversationId.collectAsState()
    val isReplying by viewModel.isReplying.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentProvider by viewModel.currentProvider.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val availableProviders by viewModel.availableProviders.collectAsState()
    val currentThinkingLevel by viewModel.currentThinkingLevel.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val imageInputSupported = currentModel?.inputModalities?.contains(com.nuttavern.data.model.Modality.IMAGE) == true
    val clipboardMessage by viewModel.clipboardMessage.collectAsState()
    val currentCharacter by viewModel.currentCharacter.collectAsState()
    val currentCharacterId by viewModel.currentCharacterId.collectAsState()
    val currentPersona by viewModel.currentPersona.collectAsState()
    val currentPersonaId by viewModel.currentPersonaId.collectAsState()
    val currentPreset by viewModel.currentPreset.collectAsState()
    val currentPresetId by viewModel.currentPresetId.collectAsState()
    val globalRegexScripts by viewModel.globalRegexScripts.collectAsState()
    val regexCounts by viewModel.regexCounts.collectAsState()
    val lorebookCounts by viewModel.lorebookCounts.collectAsState()
    val currentToolMode by viewModel.currentToolMode.collectAsState()
    val localToolsSettings by viewModel.localToolsSettings.collectAsState()
    val currentToolActivity by viewModel.currentToolActivity.collectAsState()
    val pendingToolApproval by viewModel.pendingToolApproval.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    val conversationDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val settingsDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var showModelPicker by remember { mutableStateOf(false) }
    var showCharacterPicker by remember { mutableStateOf(false) }
    val regenerateMessage = remember { mutableStateOf<Message?>(null) }
    val pendingDeleteMessage = remember { mutableStateOf<Message?>(null) }
    val editMessage = remember { mutableStateOf<Message?>(null) }
    val conversationActionsTarget = remember { mutableStateOf<com.nuttavern.data.model.ConversationSummary?>(null) }
    val renameConversationTarget = remember { mutableStateOf<com.nuttavern.data.model.ConversationSummary?>(null) }
    val deleteConversationTarget = remember { mutableStateOf<com.nuttavern.data.model.ConversationSummary?>(null) }
    var pendingSidebarFeatureNotice by remember { mutableStateOf<String?>(null) }
    var showPersonaPicker by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showRegexPicker by remember { mutableStateOf(false) }
    var showLorebookPicker by remember { mutableStateOf(false) }
    var showToolModePicker by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf("") }

    val shouldShowStreaming = streamingConversationId == currentId &&
        (streamingContent.isNotBlank() || streamingReasoningContent.isNotBlank())
    // Composer 底部留白:无论键盘是否抬起,都保留与无键盘场景相同的间距,
    // 避免输入栏紧贴键盘顶部产生的局促感。
    val composerBottomPadding = NutTavernComposerTokens.RestingBottomPadding

    LaunchedEffect(clipboardMessage) {
        val textToCopy = clipboardMessage ?: return@LaunchedEffect
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", textToCopy)))
        snackbarHostState.showSnackbar("已复制")
        viewModel.clearClipboardMessage()
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    LaunchedEffect(pendingSidebarFeatureNotice) {
        val featureName = pendingSidebarFeatureNotice ?: return@LaunchedEffect

        snackbarHostState.showSnackbar("$featureName 暂未接入")
        pendingSidebarFeatureNotice = null
    }

    BackHandler(enabled = conversationDrawerState.isOpen) {
        coroutineScope.launch { conversationDrawerState.close() }
    }
    BackHandler(enabled = settingsDrawerState.isOpen) {
        coroutineScope.launch { settingsDrawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = conversationDrawerState,
        gesturesEnabled = !settingsDrawerState.isOpen,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentConversationId = currentId,
                currentCharacter = currentCharacter,
                onSelectConversation = { conversationId ->
                    viewModel.selectConversation(conversationId)
                    coroutineScope.launch { conversationDrawerState.close() }
                },
                onLongPressConversation = { conversation ->
                    conversationActionsTarget.value = conversation
                },
                onNewConversation = {
                    viewModel.startNewConversation()
                    coroutineScope.launch { conversationDrawerState.close() }
                },
                onOpenCharacterPicker = {
                    showCharacterPicker = true
                },
                onOpenSettings = {
                    // 不关闭抽屉,直接 navigate。
                    // 之前先 close 再 navigate 会让 close 动画(suspend)走完才弹设置页,
                    // 偏慢;并行 launch 又会让 SwipeableState 在 ChatScreen 离屏期间被中断,
                    // 返回后再 open 出现灰屏(只渲染 scrim 不渲染抽屉内容)。
                    // C 方案:抽屉保持 Open 状态被推到栈底,返回时抽屉仍是开的,
                    // SwipeableState 完整未被打断,不会再触发灰屏。
                    onNavigateToSettings()
                },
                onOpenStats = {
                    pendingSidebarFeatureNotice = "统计"
                },
                onDismiss = { coroutineScope.launch { conversationDrawerState.close() } },
            )
        },
    ) {
        // M3 ModalNavigationDrawer 默认从左侧滑出。设置抽屉需要从右侧出现,
        // 通过临时翻转 LayoutDirection 复用同一个组件;子树用 Ltr 还原避免内部布局被反转。
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ModalNavigationDrawer(
                drawerState = settingsDrawerState,
                gesturesEnabled = settingsDrawerState.isOpen,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        SettingsDrawer(
                            currentCharacter = currentCharacter,
                            currentPersona = currentPersona,
                            currentPreset = currentPreset,
                            globalRegexScripts = globalRegexScripts,
                            regexCounts = regexCounts,
                            lorebookCounts = lorebookCounts,
                            toolModeSubtitle = toolModeSubtitle(
                                currentConversationId = currentId,
                                currentMode = currentToolMode,
                                newConversationDefaultEnabled = localToolsSettings.defaultEnabled,
                            ),
                            onOpenUnavailableFeature = { featureName ->
                                pendingSidebarFeatureNotice = featureName
                            },
                            onOpenPersonaPicker = { showPersonaPicker = true },
                            onOpenPresetPicker = { showPresetPicker = true },
                            onOpenRegexPicker = { showRegexPicker = true },
                            onOpenLorebookPicker = { showLorebookPicker = true },
                            onOpenToolModePicker = { showToolModePicker = true },
                            onNavigateToCharacterDetail = { characterId ->
                                coroutineScope.launch { settingsDrawerState.close() }
                                onNavigateToCharacterDetail(characterId)
                            },
                            onDismiss = { coroutineScope.launch { settingsDrawerState.close() } },
                        )
                    }
                },
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ChatScreenContent(
                        snackbarHostState = snackbarHostState,
                        messages = messages,
                        streamingContent = streamingContent,
                        streamingReasoningContent = streamingReasoningContent,
                        streamingReasoningDurationMillis = streamingReasoningDurationMillis,
                        shouldShowStreaming = shouldShowStreaming,
                        currentConversationId = currentId,
                        currentConversationTitle = conversations
                            .firstOrNull { it.id == currentId }?.title?.ifBlank { null }
                            ?: "新对话",
                        composerBottomPadding = composerBottomPadding,
                        isReplying = isReplying,
                        draft = draft,
                        currentProvider = currentProvider,
                        currentModel = currentModel,
                        currentThinkingLevel = currentThinkingLevel,
                        currentToolActivity = currentToolActivity,
                        pendingAttachments = pendingAttachments,
                        imageInputSupported = imageInputSupported,
                        onAddImage = viewModel::addImageAttachment,
                        onRemoveImage = viewModel::removeImageAttachment,
                        onOpenConversationDrawer = {
                            coroutineScope.launch { conversationDrawerState.open() }
                        },
                        onOpenSettingsDrawer = {
                            coroutineScope.launch { settingsDrawerState.open() }
                        },
                        onCopyMessage = viewModel::requestCopyMessage,
                        onEditMessage = { message ->
                            editMessage.value = message
                            editContent = message.content
                        },
                        onRegenerateMessage = { message -> regenerateMessage.value = message },
                        onDeleteMessage = { message -> pendingDeleteMessage.value = message },
                        onOpenModelPicker = { showModelPicker = true },
                        onDraftChange = viewModel::updateDraft,
                        onSendDraft = viewModel::sendMessage,
                        onStopGeneration = viewModel::stopGeneration,
                        onSelectThinkingLevel = viewModel::selectThinkingLevel,
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            availableProviders = availableProviders,
            currentProviderId = currentProvider?.id ?: "",
            currentModelInternalId = currentModel?.id ?: "",
            onSelectProviderAndModel = { providerId, modelInternalId ->
                viewModel.selectProviderAndModel(providerId, modelInternalId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    com.nuttavern.ui.persona.PersonaPickerSheet(
        visible = showPersonaPicker,
        currentPersonaId = currentPersonaId,
        onApply = { personaId ->
            viewModel.selectPersonaForCurrentConversation(personaId)
            showPersonaPicker = false
        },
        onEdit = { personaId ->
            showPersonaPicker = false
            coroutineScope.launch { settingsDrawerState.close() }
            onNavigateToPersonaDetail(personaId)
        },
        onDismiss = { showPersonaPicker = false },
    )

    com.nuttavern.ui.preset.PresetPickerSheet(
        visible = showPresetPicker,
        currentPresetId = currentPresetId,
        onApply = { presetId ->
            viewModel.selectPresetForCurrentConversation(presetId)
            showPresetPicker = false
        },
        onEdit = { presetId ->
            showPresetPicker = false
            coroutineScope.launch { settingsDrawerState.close() }
            onNavigateToPresetDetail(presetId)
        },
        onDismiss = { showPresetPicker = false },
    )

    com.nuttavern.ui.regex.RegexPickerSheet(
        visible = showRegexPicker,
        onApply = { enabledGroupIds, enabledOrphanIds ->
            viewModel.updateRegexSelection(enabledGroupIds, enabledOrphanIds)
            showRegexPicker = false
        },
        onEdit = { id, isGroup ->
            showRegexPicker = false
            coroutineScope.launch { settingsDrawerState.close() }
            onNavigateToRegexDetail(id)
        },
        onDismiss = { showRegexPicker = false },
    )

    com.nuttavern.ui.lorebook.LorebookPickerSheet(
        visible = showLorebookPicker,
        onApply = { selectedIds ->
            viewModel.updateLorebookSelection(selectedIds)
            showLorebookPicker = false
        },
        onEdit = { lorebookId ->
            showLorebookPicker = false
            coroutineScope.launch { settingsDrawerState.close() }
            onNavigateToLorebookDetail(lorebookId)
        },
        onDismiss = { showLorebookPicker = false },
    )

    ToolModePickerSheet(
        visible = showToolModePicker,
        currentMode = currentToolMode,
        onSelect = { mode ->
            viewModel.selectToolMode(mode)
            showToolModePicker = false
        },
        onDismiss = { showToolModePicker = false },
    )

    pendingToolApproval?.let { approval ->
        ToolApprovalDialog(
            displayName = approval.displayName,
            argumentsJson = approval.argumentsJson,
            onAllow = { viewModel.resolveToolApproval(true) },
            onDeny = { viewModel.resolveToolApproval(false) },
        )
    }

    RegenerateMessageDialog(
        message = regenerateMessage.value,
        onConfirm = { message ->
            viewModel.regenerateFromMessage(message)
            regenerateMessage.value = null
        },
        onDismiss = { regenerateMessage.value = null },
    )

    DeleteMessageDialog(
        message = pendingDeleteMessage.value,
        onConfirm = { message ->
            viewModel.deleteMessage(message.id)
            pendingDeleteMessage.value = null
        },
        onDismiss = { pendingDeleteMessage.value = null },
    )

    EditMessageFullScreen(
        message = editMessage.value,
        content = editContent,
        onContentChange = { input -> editContent = input },
        onConfirm = { message, content ->
            viewModel.editMessage(message.id, content)
            editMessage.value = null
            editContent = ""
        },
        onDismiss = {
            editMessage.value = null
            editContent = ""
        },
    )

    ConversationActionsSheet(
        conversation = conversationActionsTarget.value,
        onDismiss = { conversationActionsTarget.value = null },
        onRename = { conversation ->
            conversationActionsTarget.value = null
            renameConversationTarget.value = conversation
        },
        onDelete = { conversation ->
            conversationActionsTarget.value = null
            deleteConversationTarget.value = conversation
        },
    )

    RenameConversationDialog(
        conversation = renameConversationTarget.value,
        onConfirm = { conversation, title ->
            viewModel.renameConversation(conversation.id, title)
            renameConversationTarget.value = null
        },
        onDismiss = { renameConversationTarget.value = null },
    )

    DeleteConversationDialog(
        conversation = deleteConversationTarget.value,
        onConfirm = { conversation ->
            viewModel.deleteConversation(conversation.id)
            deleteConversationTarget.value = null
        },
        onDismiss = { deleteConversationTarget.value = null },
    )

    if (showCharacterPicker) {
        CharacterPickerSheet(
            currentCharacterId = currentCharacterId,
            onSelectCharacter = { characterId ->
                viewModel.selectCharacter(characterId)
                showCharacterPicker = false
                coroutineScope.launch { conversationDrawerState.close() }
            },
            onOpenCharacterDetail = { characterId ->
                showCharacterPicker = false
                onNavigateToCharacterDetail(characterId)
            },
            onDismiss = { showCharacterPicker = false },
        )
    }
}

private fun toolModeSubtitle(
    currentConversationId: String,
    currentMode: com.nuttavern.data.tools.ConversationToolMode,
    newConversationDefaultEnabled: Boolean,
): String {
    if (currentConversationId.isBlank()) {
        return if (newConversationDefaultEnabled) "新会话默认启用" else "新会话默认关闭"
    }
    return when (currentMode) {
        com.nuttavern.data.tools.ConversationToolMode.FORCE_OFF -> "本会话关闭"
        else -> "本会话启用"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    snackbarHostState: SnackbarHostState,
    messages: List<Message>,
    streamingContent: String,
    streamingReasoningContent: String,
    streamingReasoningDurationMillis: Long,
    shouldShowStreaming: Boolean,
    currentConversationId: String,
    currentConversationTitle: String,
    composerBottomPadding: androidx.compose.ui.unit.Dp,
    isReplying: Boolean,
    draft: String,
    currentProvider: com.nuttavern.data.model.Provider?,
    currentModel: com.nuttavern.data.model.Model?,
    currentThinkingLevel: com.nuttavern.data.model.ThinkingLevel,
    currentToolActivity: String?,
    pendingAttachments: List<com.nuttavern.data.model.ImageAttachment>,
    imageInputSupported: Boolean,
    onAddImage: (ByteArray, String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onOpenConversationDrawer: () -> Unit,
    onOpenSettingsDrawer: () -> Unit,
    onCopyMessage: (Message) -> Unit,
    onEditMessage: (Message) -> Unit,
    onRegenerateMessage: (Message) -> Unit,
    onDeleteMessage: (Message) -> Unit,
    onOpenModelPicker: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onSelectThinkingLevel: (com.nuttavern.data.model.ThinkingLevel) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = currentConversationTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenConversationDrawer) {
                        Icon(
                            imageVector = Lucide.Menu,
                            contentDescription = "打开会话侧栏",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettingsDrawer) {
                        Icon(
                            imageVector = Lucide.PanelRightOpen,
                            contentDescription = "打开设置侧栏",
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            // Composer 走 Scaffold.bottomBar:Activity 是 adjustResize,bottomBar 自动跟 IME 上移,
            // ChatList 通过 innerPadding.bottom 拿到"键盘 + bottomBar"占用的高度,
            // 用作 LazyColumn 的 contentPadding 底边,从而正确把内容压在键盘上方。
            //
            // 不使用自定义 contentWindowInsets:Scaffold 默认的 systemBars 会被 bottomBar 抵消;
            // safeDrawing 会重复消费 IME,与 bottomBar 路径冲突,导致键盘高度被叠两次。
            Column {
                if (currentToolActivity != null) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "正在调用工具: $currentToolActivity...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ChatComposer(
                    draft = draft,
                    isReplying = isReplying,
                    currentProvider = currentProvider,
                    currentModelName = currentModel?.modelId.orEmpty(),
                    currentThinkingLevel = currentThinkingLevel,
                    pendingAttachments = pendingAttachments,
                    imageInputSupported = imageInputSupported,
                    onAddImage = onAddImage,
                    onRemoveImage = onRemoveImage,
                    onOpenModelPicker = onOpenModelPicker,
                    onDraftChange = onDraftChange,
                    onSendDraft = onSendDraft,
                    onStopGeneration = onStopGeneration,
                    onSelectThinkingLevel = onSelectThinkingLevel,
                    bottomPadding = composerBottomPadding,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ChatMessageList(
            messages = messages,
            streamingContent = streamingContent,
            streamingReasoningContent = streamingReasoningContent,
            streamingReasoningDurationMillis = streamingReasoningDurationMillis,
            shouldShowStreaming = shouldShowStreaming,
            conversationId = currentConversationId,
            innerPadding = innerPadding,
            modifier = Modifier.fillMaxSize(),
            onCopyMessage = onCopyMessage,
            onEditMessage = onEditMessage,
            onRegenerateMessage = onRegenerateMessage,
            onDeleteMessage = onDeleteMessage,
        )
    }
}
