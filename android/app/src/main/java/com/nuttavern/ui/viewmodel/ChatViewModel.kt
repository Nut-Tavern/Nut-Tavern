package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.character.Character
import com.nuttavern.data.character.CharacterRepository
import com.nuttavern.data.model.AssistantConfig
import com.nuttavern.data.model.ChatRunMode
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.data.model.GeneratedContentSanitizer
import com.nuttavern.data.model.ImageAttachment
import com.nuttavern.data.model.Message
import com.nuttavern.data.model.MessagePart
import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.Provider
import com.nuttavern.data.model.ThinkingLevel
import com.nuttavern.data.model.WorkspaceAccessMode
import com.nuttavern.data.persona.PersonaRepository
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.data.preset.Preset
import com.nuttavern.data.preset.PresetRepository
import com.nuttavern.data.preset.presetRegexScripts
import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScriptRepository
import com.nuttavern.data.local.SettingsDataStore
import com.nuttavern.data.repository.AssistantRepository
import com.nuttavern.data.repository.ConversationRepository
import com.nuttavern.data.repository.ProviderRepository
import com.nuttavern.network.ChatApiClient
import com.nuttavern.network.ChatImage
import com.nuttavern.network.ChatMessage
import com.nuttavern.prompt.HistoryMessage
import com.nuttavern.prompt.PromptComposer
import com.nuttavern.prompt.PromptComposerInput
import com.nuttavern.prompt.PromptComposerOutput
import com.nuttavern.regex.RegexEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

private const val MAX_CONVERSATION_TITLE_LENGTH = 80
private val lorebookTimedEffectJson = Json { ignoreUnknownKeys = true }

/** 单张图片字节上限(原图直发不压缩,超限直接拒绝)。Claude 限 5MB/图,取保守值。 */
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** 只发图无文字时,新会话标题种子的兜底。 */
private const val ATTACHMENT_ONLY_TITLE_SEED = "[图片]"

/** 支持的图片 MIME → 落盘扩展名。三家通用集合的交集。 */
private fun imageExtensionForMime(mimeType: String): String? = when (mimeType.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> null
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val assistantRepository: AssistantRepository,
    private val conversationRepository: ConversationRepository,
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val presetRepository: PresetRepository,
    private val regexScriptRepository: RegexScriptRepository,
    private val lorebookRepository: com.nuttavern.data.lorebook.LorebookRepository,
    private val lorebookEngine: com.nuttavern.lorebook.LorebookEngine,
    private val sessionLorebookResolver: com.nuttavern.data.lorebook.SessionLorebookResolver,
    private val settingsDataStore: SettingsDataStore,
    private val promptComposer: PromptComposer,
    private val regexEngine: RegexEngine,
    private val chatApiClient: ChatApiClient,
    private val chatToolRegistry: com.nuttavern.network.ChatToolRegistry,
    private val toolsSettingsRepository: com.nuttavern.data.tools.ToolsSettingsRepository,
    private val localToolsRepository: com.nuttavern.data.tools.LocalToolsRepository,
) : ViewModel() {

    private val _conversationList = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversationList: StateFlow<List<ConversationSummary>> = _conversationList.asStateFlow()

    private val _nonArchivedConversations = MutableStateFlow<List<ConversationSummary>>(emptyList())

    private val _allConversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val allConversations: StateFlow<List<ConversationSummary>> = _allConversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow("")
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _messagesByConversationId = MutableStateFlow<Map<String, List<Message>>>(emptyMap())

    private val _currentMessages = MutableStateFlow<List<Message>>(emptyList())
    val currentMessages: StateFlow<List<Message>> = _currentMessages.asStateFlow()

    private val _isReplying = MutableStateFlow(false)
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * 待发送的图片附件(用户在 Composer 选了图但还没点发送)。发送成功后清空。
     * 二进制已落盘([ImageAttachment.path]),这里只持有元数据引用。
     */
    private val _pendingAttachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<ImageAttachment>> = _pendingAttachments.asStateFlow()

    private val _chatRunMode = MutableStateFlow(ChatRunMode.CHAT)
    val chatRunMode: StateFlow<ChatRunMode> = _chatRunMode.asStateFlow()

    private val _workspaceAccessMode = MutableStateFlow(WorkspaceAccessMode.NO_WORKSPACE)
    val workspaceAccessMode: StateFlow<WorkspaceAccessMode> = _workspaceAccessMode.asStateFlow()

    /**
     * 当前会话的思考量。会话级:切会话同步成该会话持久化的 thinkingLevel,切档位写回会话表。
     * `null` 不出现在这里——加载 / 创建会话时都会落到一个明确档位,默认 [ThinkingLevel.Default]。
     */
    private val _currentThinkingLevel = MutableStateFlow<ThinkingLevel>(ThinkingLevel.Default)
    val currentThinkingLevel: StateFlow<ThinkingLevel> = _currentThinkingLevel.asStateFlow()

    /** 当前会话的内置工具总开关。旧版兼容字段;新 UI 使用 [currentEnabledToolIds] 做按工具开关。 */
    private val _currentToolMode =
        MutableStateFlow(com.nuttavern.data.tools.ConversationToolMode.FOLLOW_GLOBAL)
    val currentToolMode: StateFlow<com.nuttavern.data.tools.ConversationToolMode> =
        _currentToolMode.asStateFlow()

    /** 当前会话启用的内置工具 id。右侧栏工具卡片直接读写这个集合。 */
    private val _currentEnabledToolIds = MutableStateFlow<Set<String>>(emptySet())
    val currentEnabledToolIds: StateFlow<Set<String>> = _currentEnabledToolIds.asStateFlow()

    /** 当前会话启用的世界书 id。右侧栏世界书卡片直接读写这个集合,世界书完全跟随会话,不继承任何全局默认。 */
    private val _currentEnabledLorebookIds = MutableStateFlow<Set<String>>(emptySet())
    val currentEnabledLorebookIds: StateFlow<Set<String>> = _currentEnabledLorebookIds.asStateFlow()

    /** 注册表里的全部内置工具定义,供右侧栏直接渲染工具卡片。 */
    val chatTools: List<com.nuttavern.network.ChatTool> = chatToolRegistry.tools

    /** 工具 id → 展示名。查不到(已下线工具等)退回原始 id,保证历史消息里的工具卡仍可读。 */
    fun toolDisplayName(toolName: String): String =
        chatToolRegistry.toolById(toolName)?.displayName ?: toolName

    /** 内置工具配置(各工具默认启用 / 各工具确认),供右侧栏与设置页展示。 */
    val localToolsSettings: StateFlow<com.nuttavern.data.tools.LocalToolsSettings> =
        localToolsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.nuttavern.data.tools.LocalToolsSettings(),
        )

    /**
     * 待人工确认的工具调用。非空时 UI 弹确认框;用户点按后通过 [resolveToolApproval] 回填结果。
     */
    private val _pendingToolApproval = MutableStateFlow<PendingToolApproval?>(null)
    val pendingToolApproval: StateFlow<PendingToolApproval?> = _pendingToolApproval.asStateFlow()
    private var toolApprovalDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** 当前流式回复正在调用的内置工具名。空表示没有工具活动,供 UI 展示短暂状态提示。 */
    private val _currentToolActivity = MutableStateFlow<String?>(null)
    val currentToolActivity: StateFlow<String?> = _currentToolActivity.asStateFlow()

    /**
     * 本次流式回复已执行完成的工具调用,按发生顺序累积。每条携带"工具到达时已累积的正文长度"
     * ([StreamingToolMark.contentOffset]),落库时据此把正文切成多段,与工具按真实时序交错穿插。
     *
     * OpenAI 等协议每轮 SSE 要么全文字要么全工具,工具总在一段文字之后;记录切点即可还原
     * "文字A → 工具 → 文字B → 工具" 的真实顺序,而非把工具全聚到正文前。
     */
    private val _streamingToolMarks = MutableStateFlow<List<StreamingToolMark>>(emptyList())

    private val _streamingConversationId = MutableStateFlow<String?>(null)
    val streamingConversationId: StateFlow<String?> = _streamingConversationId.asStateFlow()
    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()
    private val _streamingRawContent = MutableStateFlow("")
    private val _streamingExplicitReasoningContent = MutableStateFlow("")
    private val _streamingReasoningContent = MutableStateFlow("")
    val streamingReasoningContent: StateFlow<String> = _streamingReasoningContent.asStateFlow()
    private val _streamingReasoningDurationMillis = MutableStateFlow(0L)
    val streamingReasoningDurationMillis: StateFlow<Long> = _streamingReasoningDurationMillis.asStateFlow()

    val availableProviders: StateFlow<List<Provider>> = providerRepository.providers
    val currentModelInternalId: StateFlow<String> = providerRepository.selectedModelInternalId

    private val _currentProvider = MutableStateFlow<Provider?>(null)
    val currentProvider: StateFlow<Provider?> = _currentProvider.asStateFlow()

    private val _currentModel = MutableStateFlow<Model?>(null)
    val currentModel: StateFlow<Model?> = _currentModel.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _assistants = MutableStateFlow<List<AssistantConfig>>(emptyList())
    val assistants: StateFlow<List<AssistantConfig>> = _assistants.asStateFlow()

    private val _currentAssistantId = MutableStateFlow("chat-assistant")
    val currentAssistantId: StateFlow<String> = _currentAssistantId.asStateFlow()

    /**
     * 用户在"开始新会话"前选好的角色 id。null 表示用默认助手聊天(走 assistant.systemPrompt)。
     *
     * 切换路径:
     * - 用户在抽屉点角色 → [selectCharacterForNewConversation] 写入这里;
     * - 进入已有会话(`selectConversation`) → 同步成该会话持久化的 characterId;
     * - 开新会话([startNewConversation]) → 保留当前选中,直到用户主动切。
     *
     * 创建新会话时(由 [ensureCurrentConversation])把这个值写到 `ConversationEntity.characterId`,
     * 之后该会话的角色就锁定在那一刻的选择,后续切角色不会改写历史会话。
     */
    private val _currentCharacterId = MutableStateFlow<String?>(null)
    val currentCharacterId: StateFlow<String?> = _currentCharacterId.asStateFlow()

    /**
     * 当前选中角色的快照。null = 未绑定角色 / 角色被删了 / 还在加载。
     *
     * 只用来给抽屉标题、Picker 选中标记等 UI 读;不参与拼接管线(后者在 send 时按 id 查角色,
     * 避免视图层和拼接层互相绕)。
     */
    private val _currentCharacter = MutableStateFlow<com.nuttavern.data.character.Character?>(null)
    val currentCharacter: StateFlow<com.nuttavern.data.character.Character?> = _currentCharacter.asStateFlow()

    /**
     * 用户在"开始新会话"前选好的身份 id,以及"已在会话内时"该会话锁定的身份 id。
     *
     * 切换路径:
     * - 进入已有会话([selectConversation]) → 同步成该会话持久化的 personaId;
     * - 抽屉切身份([selectPersonaForCurrentConversation]) → 直接写当前会话的 personaId,
     *   并把这里同步上;
     * - 开新会话([startNewConversation]) → 重置成默认身份 id,作为下一条新会话的初值;
     * - 创建新会话([ensureCurrentConversation]) → 把这里的值落到 `conversations.personaId`。
     *
     * `null` 表示"无身份"(包括"无"伪卡 / 老数据迁移上来的会话):拼接管线跳过用户身份块。
     */
    private val _currentPersonaId = MutableStateFlow<String?>(null)
    val currentPersonaId: StateFlow<String?> = _currentPersonaId.asStateFlow()

    /**
     * 当前选中身份的快照。null = "无身份"(包括"无"伪卡)。
     *
     * 只用来给抽屉标题、Picker 选中标记等 UI 读;拼接管线在 send 时按 id 查身份。
     */
    private val _currentPersona = MutableStateFlow<UserPersona?>(null)
    val currentPersona: StateFlow<UserPersona?> = _currentPersona.asStateFlow()

    /**
     * 用户在"开始新会话"前选好的预设 id,以及"已在会话内时"该会话锁定的预设 id。
     *
     * 切换路径与 [currentPersonaId] 同模式:
     * - 进入已有会话 → 同步成该会话持久化的 presetId;
     * - 抽屉切预设([selectPresetForCurrentConversation]) → 直接写当前会话的 presetId;
     * - 开新会话 → 重置成默认预设 id,作为下一条新会话的初值;
     * - 创建新会话 → 把这里的值落到 `conversations.presetId`。
     *
     * `null` 表示"未锁定预设":拼接管线退化为 [PresetRepository.defaultPresetId]。
     */
    private val _currentPresetId = MutableStateFlow<String?>(null)
    val currentPresetId: StateFlow<String?> = _currentPresetId.asStateFlow()

    /**
     * 当前选中预设的快照。null = "未锁定 / 仍在加载",消费方退化到默认预设。
     */
    private val _currentPreset = MutableStateFlow<Preset?>(null)
    val currentPreset: StateFlow<Preset?> = _currentPreset.asStateFlow()

    /**
     * 当前用户的全局正则脚本列表(已展开 + 已过滤启用)。RegexPicker 抽屉与 PromptComposer
     * 用于"实际执行"。SettingsDrawer 显示"总数 / 启用数"用 [regexCounts]:这里展开列表里
     * 启用 = 总数,无法体现"用户级总共多少条规则",所以分两路。
     */
    val globalRegexScripts: StateFlow<List<com.nuttavern.data.regex.RegexScript>> =
        regexScriptRepository.expandedEnabledScripts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * 用户级正则的总数 / 启用数,供 SettingsDrawer 副标显示。
     *
     * - 总数 = 所有组的所有规则 + 所有散规则(不管组 / 散规则启用与否);
     * - 启用 = `expandedEnabledScripts`(启用组里的全部规则 + 启用的散规则)。
     *
     * 用 Pair 不引入新类型;消费方按 `(total, enabled)` 解构。
     */
    val regexCounts: StateFlow<Pair<Int, Int>> = combine(
        regexScriptRepository.snapshot,
        regexScriptRepository.expandedEnabledScripts,
    ) { snapshot, enabled ->
        val totalScripts = snapshot.groups.sumOf { it.scripts.size } + snapshot.orphanScripts.size
        totalScripts to enabled.size
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0 to 0,
    )

    /** 世界书总数 / 当前会话选中数,供 SettingsDrawer 显示。 */
    val lorebookCounts: StateFlow<Pair<Int, Int>> = combine(
        lorebookRepository.lorebooks,
        _currentEnabledLorebookIds,
    ) { books, selectedIds ->
        books.size to books.count { it.id in selectedIds }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0 to 0,
    )

    /**
     * 内置工具总数 / 当前会话启用数,供 SettingsDrawer 副标显示。
     *
     * 按"展示单元"计数而非工具个数:同一分组的工具(如世界书的 list/read)在 UI 里合并成一张卡、
     * 算作一个工具单元;无分组工具各算一个。启用数同理——组内工具全部启用才算该组"已启用"。
     */
    val toolCounts: StateFlow<Pair<Int, Int>> = _currentEnabledToolIds.map { enabledIds ->
        countToolUnits(enabledIds)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = countToolUnits(emptySet()),
    )

    /** 把工具按分组聚合成展示单元,返回 (单元总数, 已启用单元数)。 */
    private fun countToolUnits(enabledIds: Set<String>): Pair<Int, Int> {
        // 分组桶:同组工具归一桶(key=group:id),无组工具各自一桶(key=tool:id)。
        val units = chatTools.groupBy { it.group?.id?.let { gid -> "group:$gid" } ?: "tool:${it.id}" }
        val enabledUnits = units.count { (_, tools) -> tools.all { it.id in enabledIds } }
        return units.size to enabledUnits
    }


    private var streamingJob: Job? = null
    private var streamingReasoningStartedAtMillis: Long? = null
    private var streamingReasoningEndedAtMillis: Long? = null
    private var streamingReasoningTimerJob: Job? = null

    /**
     * 是否已完成"启动持久化恢复"。在恢复完成之前,自动恢复路径
     * ([nonArchivedConversations.collect] / [selectLatestConversationForAssistant])
     * 不去主动挑会话,避免覆盖用户上次的占位态(典型场景:上次切到了没会话的角色)。
     *
     * 恢复完成后才让自动恢复路径接管"会话被删 / 归档导致需要切到下一条"等场景。
     */
    private var hasRestoredFromPersistence = false

    init {
        viewModelScope.launch {
            providerRepository.initialize()
            assistantRepository.initialize()
            _currentProvider.value = providerRepository.currentProvider()
            _currentModel.value = providerRepository.currentModel()
        }
        viewModelScope.launch {
            providerRepository.providers.collect {
                _currentProvider.value = providerRepository.currentProvider()
                _currentModel.value = providerRepository.currentModel()
            }
        }
        viewModelScope.launch {
            providerRepository.selectedProviderId.collect {
                _currentProvider.value = providerRepository.currentProvider()
                _currentModel.value = providerRepository.currentModel()
            }
        }
        viewModelScope.launch {
            providerRepository.selectedModelInternalId.collect {
                _currentModel.value = providerRepository.currentModel()
            }
        }
        viewModelScope.launch {
            assistantRepository.assistants.collect { assistantsList ->
                _assistants.value = assistantsList
            }
        }
        viewModelScope.launch {
            assistantRepository.defaultAssistantId.collect { assistantId ->
                _currentAssistantId.value = assistantId
                refreshVisibleConversationsForAssistant(assistantId)
                if (hasRestoredFromPersistence) {
                    selectLatestConversationForAssistant(assistantId)
                }
            }
        }
        viewModelScope.launch {
            conversationRepository.nonArchivedConversations.collect { conversations ->
                _nonArchivedConversations.value = conversations
                refreshVisibleConversationsForAssistant(_currentAssistantId.value)
                if (!hasRestoredFromPersistence) return@collect
                val currentId = _currentConversationId.value
                if (currentId.isNotBlank() && conversations.any {
                        it.id == currentId && it.assistantId == _currentAssistantId.value && !it.archived
                    }) {
                    return@collect
                }
                // 当前会话不存在(被删 / 归档 / assistant 切换)→ 主动挑同 assistant 下最新一条;
                // 都没有就保持空 id,让用户在 picker 里预选的"下一条新会话"角色 / 身份 / 预设继续生效,
                // 与 [startNewConversation] 的契约一致。
                val nextConversation = latestConversationForAssistant(_currentAssistantId.value)
                _currentConversationId.value = nextConversation?.id.orEmpty()
                if (nextConversation != null) {
                    _currentCharacterId.value = nextConversation.characterId
                    _currentPersonaId.value = nextConversation.personaId
                    _currentPresetId.value = nextConversation.presetId
                    _currentThinkingLevel.value = nextConversation.thinkingLevel
                    _currentToolMode.value = nextConversation.toolMode
                    _currentEnabledToolIds.value = enabledToolIdsForConversation(nextConversation)
                    _currentEnabledLorebookIds.value = enabledLorebookIdsForConversation(nextConversation)
                } else {
                    _currentMessages.value = emptyList()
                }
            }
        }
        viewModelScope.launch {
            conversationRepository.conversations.collect { conversations ->
                _allConversations.value = sortConversations(conversations)
            }
        }
        // currentCharacterId 变 / 角色仓库变(比如改名 / 删除)都重新解析当前角色。
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _currentCharacterId,
                characterRepository.characters,
            ) { id, characters ->
                id?.let { targetId -> characters.firstOrNull { it.id == targetId } }
            }.collect { character -> _currentCharacter.value = character }
        }
        // currentPersonaId 变 / 身份仓库变都重新解析当前身份。
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _currentPersonaId,
                personaRepository.personas,
            ) { id, personas ->
                if (id == null || id == UserPersona.NONE_PERSONA_ID) null
                else personas.firstOrNull { it.id == id }
            }.collect { persona -> _currentPersona.value = persona }
        }
        // currentPresetId 变 / 预设仓库变都重新解析当前预设。
        // null 时退化为默认预设(永远非空,由仓库兜底)。
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _currentPresetId,
                presetRepository.presets,
                presetRepository.defaultPresetId,
            ) { id, presets, defaultId ->
                val targetId = id ?: defaultId
                presets.firstOrNull { it.id == targetId }
                    ?: presets.firstOrNull { it.id == defaultId }
                    ?: presets.firstOrNull()
            }.collect { preset -> _currentPreset.value = preset }
        }
        // 启动持久化恢复:把上次"当前会话 / 角色 / 身份 / 预设"占位态读回来,然后启用自动恢复。
        // 必须**等首帧 nonArchivedConversations 到位后**再决定:
        // - 上次的会话仍存在 → 切回那条;
        // - 上次会话被删 / 归档 → 退化为"角色 / 身份 / 预设占位态",让用户继续在那个角色下新建。
        viewModelScope.launch {
            val saved = settingsDataStore.getLastChatState()
            // 等首帧会话数据 + assistant id 就绪,避免在空列表上做"保留 / 退化"决策。
            assistantRepository.defaultAssistantId.first()
            val firstConversations = conversationRepository.nonArchivedConversations.first()
            applyRestoredChatState(saved, firstConversations)
            hasRestoredFromPersistence = true
        }
        // 当前会话 / 角色 / 身份 / 预设 / 思考量变化时立即写回持久化,关 app 不丢占位态。
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _currentConversationId,
                _currentCharacterId,
                _currentPersonaId,
                _currentPresetId,
                _currentThinkingLevel,
                _currentToolMode,
            ) { values ->
                val conversationId = values[0] as String
                val characterId = values[1] as String?
                val personaId = values[2] as String?
                val presetId = values[3] as String?
                val thinkingLevel = values[4] as ThinkingLevel
                val toolMode = values[5] as com.nuttavern.data.tools.ConversationToolMode
                SettingsDataStore.LastChatState(
                    conversationId = conversationId.takeIf { it.isNotBlank() },
                    characterId = characterId,
                    personaId = personaId,
                    presetId = presetId,
                    thinkingLevel = ThinkingLevel.serialize(thinkingLevel),
                    toolMode = toolMode.storageValue,
                )
            }.collect { state ->
                if (hasRestoredFromPersistence) {
                    settingsDataStore.saveLastChatState(state)
                }
            }
        }
        observeCurrentMessages()
    }

    /**
     * 把持久化里的"上次占位态"应用到当前 ViewModel state。
     *
     * 三种情况:
     * 1. 持久化里有会话 id 且仍在非归档列表里 → 切回那条会话(同步 character / persona / preset);
     * 2. 持久化里有 character / persona / preset 但会话已不在 → 进入"占位态"(空 currentConversationId,
     *    保留 character / persona / preset),让用户在该角色下继续新建;
     * 3. 持久化整体为空 / 都失效 → 走原本的"挑当前 assistant 最新一条"自动恢复。
     */
    private fun applyRestoredChatState(
        saved: SettingsDataStore.LastChatState,
        firstConversations: List<ConversationSummary>,
    ) {
        val savedConversation = saved.conversationId
            ?.let { id -> firstConversations.firstOrNull { it.id == id && !it.archived } }

        if (savedConversation != null) {
            _currentConversationId.value = savedConversation.id
            _currentCharacterId.value = savedConversation.characterId
            _currentPersonaId.value = savedConversation.personaId
            _currentPresetId.value = savedConversation.presetId
            _currentThinkingLevel.value = savedConversation.thinkingLevel
            _currentToolMode.value = savedConversation.toolMode
            _currentEnabledToolIds.value = enabledToolIdsForConversation(savedConversation)
            _currentEnabledLorebookIds.value = enabledLorebookIdsForConversation(savedConversation)
            return
        }

        val hasCharacterPlaceholder = saved.characterId != null
        val hasPersonaPlaceholder = saved.personaId != null
        val hasPresetPlaceholder = saved.presetId != null
        if (hasCharacterPlaceholder || hasPersonaPlaceholder || hasPresetPlaceholder) {
            // 占位态:保留预选,清空当前会话;新会话发首条时由 ensureCurrentConversation 落库。
            _currentConversationId.value = ""
            _currentCharacterId.value = saved.characterId
            _currentPersonaId.value = saved.personaId
            _currentPresetId.value = saved.presetId
            _currentThinkingLevel.value = ThinkingLevel.parse(saved.thinkingLevel)
            val restoredToolMode = com.nuttavern.data.tools.ConversationToolMode.fromStorage(saved.toolMode)
            _currentToolMode.value = restoredToolMode
            _currentEnabledToolIds.value = defaultToolIdsForPlaceholder(restoredToolMode)
            _currentEnabledLorebookIds.value = emptySet()
            _currentMessages.value = emptyList()
            return
        }

        // 没有持久化数据 → 与启动前同行为:挑同 assistant 下最新一条。
        val nextConversation = latestConversationForAssistant(_currentAssistantId.value)
        _currentConversationId.value = nextConversation?.id.orEmpty()
        if (nextConversation != null) {
            _currentCharacterId.value = nextConversation.characterId
            _currentPersonaId.value = nextConversation.personaId
            _currentPresetId.value = nextConversation.presetId
            _currentThinkingLevel.value = nextConversation.thinkingLevel
            _currentToolMode.value = nextConversation.toolMode
            _currentEnabledToolIds.value = enabledToolIdsForConversation(nextConversation)
            _currentEnabledLorebookIds.value = enabledLorebookIdsForConversation(nextConversation)
        } else {
            _currentMessages.value = emptyList()
        }
    }

    /**
     * 把用户选中的图片字节存为待发附件。UI 读 URI→bytes→mime 后调用(IO 在 UI 协程里做)。
     *
     * 超过 [MAX_IMAGE_BYTES] 的图直接拒绝并给错误提示(原图直发,不压缩),避免请求体过大被后端拒。
     * 当前模型不支持图片输入时也拒绝。落盘走 [ConversationRepository.saveImageBytes]。
     */
    fun addImageAttachment(bytes: ByteArray, mimeType: String) {
        if (!isImageInputSupported()) {
            _errorMessage.value = "当前模型不支持图片输入"
            return
        }
        if (bytes.size > MAX_IMAGE_BYTES) {
            _errorMessage.value = "图片过大,单张不能超过 ${MAX_IMAGE_BYTES / (1024 * 1024)} MB"
            return
        }
        val extension = imageExtensionForMime(mimeType)
        if (extension == null) {
            _errorMessage.value = "不支持的图片格式"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val attachmentId = createMessageId("img")
            val path = runCatching {
                conversationRepository.saveImageBytes(attachmentId, bytes, extension)
            }.getOrNull()
            if (path == null) {
                _errorMessage.value = "图片保存失败"
                return@launch
            }
            _pendingAttachments.update { it + ImageAttachment(attachmentId, path, mimeType) }
        }
    }

    /** 移除一个待发附件(用户在 Composer 预览里点删除)。已落盘文件留着,孤儿文件影响可忽略。 */
    fun removeImageAttachment(attachmentId: String) {
        _pendingAttachments.update { list -> list.filterNot { it.id == attachmentId } }
    }

    /** 当前模型是否支持图片输入(inputModalities 含 IMAGE)。UI 据此启用/禁用选图入口。 */
    fun isImageInputSupported(): Boolean {
        return _currentModel.value?.inputModalities?.contains(Modality.IMAGE) == true
    }

    fun sendMessage(text: String) {
        if (_isReplying.value) return

        val trimmedText = text.trim()
        val attachments = _pendingAttachments.value
        // 纯文本且无附件才拦截;带图可以只发图不带文字。
        if (trimmedText.isBlank() && attachments.isEmpty()) return

        _draft.value = ""
        _isReplying.value = true

        streamingJob = viewModelScope.launch {
            var conversationId = ""
            try {
                val createdAt = System.currentTimeMillis()
                conversationId = ensureCurrentConversation(trimmedText.ifBlank { ATTACHMENT_ONLY_TITLE_SEED }, createdAt)
                // USER_INPUT 改文件场景:对齐酒馆 sendMessageAsUser 的 getRegexedString 调用,
                // 落库前跑一次 USER_INPUT 正则,只跑两个 Ephemerality 都不勾的脚本(永久改写)。
                // 短暂模式(promptOnly=true)的脚本在 PromptComposer A0 阶段再跑。
                val persistedUserText = applyUserInputRegexForChatFile(
                    conversationId = conversationId,
                    raw = trimmedText,
                )
                val userMessage = Message(
                    id = createMessageId("user"),
                    role = "user",
                    parts = listOf(MessagePart.Text(persistedUserText)),
                    attachments = attachments,
                )
                appendMessage(conversationId, userMessage, createdAt)
                _pendingAttachments.value = emptyList()
                refreshConversationTime(conversationId, createdAt)

                providerRepository.initialize()
                val provider = providerRepository.currentProvider()
                val model = providerRepository.currentModel()

                if (provider == null || model == null) {
                    _errorMessage.value = "请先在设置中配置提供商和模型"
                    _isReplying.value = false
                    return@launch
                }

                if (provider.apiKey.isBlank()) {
                    _errorMessage.value = "请先在设置中填写 ${provider.name} 的 API Key"
                    _isReplying.value = false
                    return@launch
                }
                if (provider.baseUrl.isBlank()) {
                    _errorMessage.value = "请先在设置中填写 ${provider.name} 的 Base URL"
                    _isReplying.value = false
                    return@launch
                }

                val prepared = buildPromptForSend(
                    conversationId = conversationId,
                    provider = provider,
                    model = model,
                    pendingUserMessage = null,
                )
                persistLorebookTimedEffects(conversationId, prepared.lorebookTimedEffectsJson)

                _streamingConversationId.value = conversationId
                _streamingContent.value = ""
                _streamingRawContent.value = ""
                _streamingExplicitReasoningContent.value = ""
                _streamingReasoningContent.value = ""
                _streamingReasoningDurationMillis.value = 0L
                streamingReasoningStartedAtMillis = null
                streamingReasoningEndedAtMillis = null
                val toolsSettings = toolsSettingsRepository.settings.first()
                val localToolsConfig = localToolsRepository.settings.first()
                val activeTools = resolveActiveTools(localToolsConfig)
                val toolContext = buildToolContext(conversationId)

                chatApiClient.streamChat(
                    provider = provider,
                    model = model,
                    messages = prepared.messages,
                    systemPrompt = prepared.systemPrompt,
                    thinkingLevel = _currentThinkingLevel.value,
                    generationParams = prepared.generationParams,
                    tools = activeTools,
                    toolCallRecurseLimit = toolsSettings.toolCallRecurseLimit,
                    requireToolApproval = false,
                    approveToolCall = buildToolApprover(),
                    toolContext = toolContext,
                ).collect { chunk ->
                    when {
                        chunk.error != null -> {
                            _errorMessage.value = chunk.error
                            clearStreamingState(conversationId)
                            _isReplying.value = false
                        }
                        chunk.isDone -> {
                            updateReasoningDurationBeforeSaving()
                            val finalContent = _streamingContent.value
                            val finalReasoningContent = _streamingReasoningContent.value
                            val finalReasoningDurationMillis = _streamingReasoningDurationMillis.value
                            val finalToolMarks = _streamingToolMarks.value
                            clearStreamingState(conversationId)
                            saveAssistantReplyIfConversationExists(
                                conversationId,
                                finalContent,
                                finalReasoningContent,
                                finalReasoningDurationMillis,
                                finalToolMarks,
                            )
                            _isReplying.value = false
                        }
                        else -> {
                            if (_streamingConversationId.value == conversationId) {
                                _currentToolActivity.value = chunk.toolActivity
                                chunk.toolCall?.let { appendStreamingToolCall(it) }
                                appendStreamingChunk(chunk.content, chunk.reasoningContent)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (conversationId.isNotBlank()) clearStreamingState(conversationId)
                _isReplying.value = false
            } catch (e: Exception) {
                _errorMessage.value = "发送失败，请检查网络、提供商配置或模型名称"
                if (conversationId.isNotBlank()) clearStreamingState(conversationId)
                _isReplying.value = false
            }
        }
    }

    fun stopGeneration() {
        streamingJob?.cancel()
        streamingJob = null
        clearPendingToolApproval()
        _streamingConversationId.value?.let { conversationId ->
            val partialContent = _streamingContent.value.trimEnd()
            val partialReasoningContent = _streamingReasoningContent.value.trimEnd()
            val partialReasoningDurationMillis = _streamingReasoningDurationMillis.value
            val partialToolMarks = _streamingToolMarks.value
            clearStreamingState(conversationId)
            if (partialContent.isNotBlank() || partialReasoningContent.isNotBlank() || partialToolMarks.isNotEmpty()) {
                viewModelScope.launch {
                    saveAssistantReplyIfConversationExists(
                        conversationId,
                        partialContent,
                        partialReasoningContent,
                        partialReasoningDurationMillis,
                        partialToolMarks,
                    )
                }
            }
        }
        _isReplying.value = false
    }

    fun selectConversation(id: String) {
        val conversation = _conversationList.value.firstOrNull { it.id == id && !it.archived }
        if (conversation == null) return

        _currentConversationId.value = conversation.id
        _currentCharacterId.value = conversation.characterId
        _currentPersonaId.value = conversation.personaId
        _currentPresetId.value = conversation.presetId
        _currentThinkingLevel.value = conversation.thinkingLevel
        _currentToolMode.value = conversation.toolMode
        _currentEnabledToolIds.value = enabledToolIdsForConversation(conversation)
        _currentEnabledLorebookIds.value = enabledLorebookIdsForConversation(conversation)
        _draft.value = ""
    }

    /**
     * 开启一个新会话:清空当前选中 + 草稿。真正的会话记录会在用户发出第一条消息时由
     * [ensureCurrentConversation] 创建,避免产生空会话。
     *
     * **保留** [currentCharacterId]:用户开新会话前已经选好的角色,在新会话里继续生效。
     * **重置** [currentPersonaId] / [currentPresetId] **为当前默认值**:与酒馆"新会话默认 = 全局默认"
     * 行为一致,用户在抽屉里改身份 / 预设会立即写到新建出来的会话上。
     * **重置** [currentThinkingLevel] **为默认**([ThinkingLevel.Default]):新会话默认"自动"。
     */
    fun startNewConversation() {
        _currentConversationId.value = ""
        _currentMessages.value = emptyList()
        _draft.value = ""
        _currentThinkingLevel.value = ThinkingLevel.Default
        // 新会话占位态先用当前默认工具集初始化;用户可在右侧栏继续按工具切换。
        // 真正落库在 ensureCurrentConversation,会写入 _currentEnabledToolIds 的当前值。
        _currentEnabledToolIds.value = defaultEnabledToolIdsForNewConversation()
        _currentToolMode.value = toolModeForEnabledToolIds(_currentEnabledToolIds.value)
        _currentEnabledLorebookIds.value = emptySet()
        viewModelScope.launch {
            _currentPersonaId.value = resolveDefaultPersonaIdOrNull()
            _currentPresetId.value = resolveDefaultPresetId()
        }
    }

    /**
     * 设置"下一条新会话"使用的角色。传 null = 不绑定角色(默认助手聊天)。
     *
     * **不**改写已有会话的 characterId:当前会话已经创建过的话,角色锁定在那一刻的选择,
     * 这里只影响后续 [ensureCurrentConversation] 创建的新会话。
     */
    fun selectCharacterForNewConversation(characterId: String?) {
        _currentCharacterId.value = characterId
    }

    /**
     * 在抽屉里切到指定角色的入口。优先回到该角色最近一条非归档会话;没有就进入新会话占位状态
     * (lock 住下一条新会话的 characterId)。
     *
     * 这条路径是"角色级最后访问会话记忆"的实现:对每个角色,用户最近聊到哪条就回到哪条,
     * 不会因为切角色就丢失上下文。无角色聊天(传 null)走"无角色"分支,挑该 assistant 下
     * 最近一条无 character 绑定的会话;都没有就进入新会话占位。
     */
    fun selectCharacter(characterId: String?) {
        val target = latestConversationForCharacter(_currentAssistantId.value, characterId)
        if (target != null) {
            selectConversation(target.id)
        } else {
            _currentCharacterId.value = characterId
            startNewConversation()
        }
    }

    /**
     * 找指定 assistant + character 组合下最近一条非归档会话。pinned 优先,再按时间倒序。
     *
     * - [characterId] = null 时只匹配"未绑定角色"的会话(`characterId IS NULL`),
     *   保持"无角色聊天"独立成一条入口,不会被任意角色会话顶上。
     */
    private fun latestConversationForCharacter(
        assistantId: String,
        characterId: String?,
    ): ConversationSummary? {
        return sortConversations(
            _nonArchivedConversations.value.filter { conversation ->
                conversation.assistantId == assistantId &&
                    !conversation.archived &&
                    conversation.characterId == characterId
            },
        ).firstOrNull()
    }

    /**
     * 抽屉切换会话身份:
     *
     * - 当前已经在某个会话里 → 把会话表的 personaId 直接覆盖,落库;
     * - 当前是"新会话"占位状态(还没发首条消息) → 只更新内存里的 [currentPersonaId],
     *   等 [ensureCurrentConversation] 创建会话时落库。
     *
     * 传 [UserPersona.NONE_PERSONA_ID] = 切到"无"伪卡 = 该会话不拼接用户身份提示词,
     * 仓库层会把 personaId 持久化为 null。
     */
    fun selectPersonaForCurrentConversation(personaId: String) {
        val normalizedId = personaId.takeIf { it != UserPersona.NONE_PERSONA_ID }
        _currentPersonaId.value = normalizedId

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        val conversation = _conversationList.value.firstOrNull { it.id == conversationId } ?: return
        if (conversation.personaId == normalizedId) return

        viewModelScope.launch {
            val updated = conversation.copy(personaId = normalizedId)
            conversationRepository.updateConversation(updated)
            _conversationList.update { list ->
                list.map { if (it.id == conversationId) updated else it }
            }
        }
    }

    /**
     * 抽屉切换会话预设:
     *
     * - 当前已经在某个会话里 → 把会话表的 presetId 直接覆盖,落库;
     * - 当前是"新会话"占位状态 → 只更新内存里的 [currentPresetId],等创建会话时落库。
     */
    fun selectPresetForCurrentConversation(presetId: String) {
        _currentPresetId.value = presetId

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        val conversation = _conversationList.value.firstOrNull { it.id == conversationId } ?: return
        if (conversation.presetId == presetId) return

        viewModelScope.launch {
            val updated = conversation.copy(presetId = presetId)
            conversationRepository.updateConversation(updated)
            _conversationList.update { list ->
                list.map { if (it.id == conversationId) updated else it }
            }
        }
    }

    /** 批量更新正则启用状态(Picker Sheet "应用"时调用)。 */
    fun updateRegexSelection(enabledGroupIds: Set<String>, enabledOrphanIds: Set<String>) {
        viewModelScope.launch {
            regexScriptRepository.applyEnabledState(enabledGroupIds, enabledOrphanIds)
        }
    }

    /** 批量更新当前会话启用世界书(Picker Sheet "应用"时调用)。 */
    fun updateLorebookSelection(selectedIds: Set<String>) {
        val validIds = selectedIds.filter { id -> id.isNotBlank() }.toSet()
        _currentEnabledLorebookIds.value = validIds

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        val conversation = _conversationList.value.firstOrNull { it.id == conversationId } ?: return
        val nextJson = encodeStringListToJson(validIds.toList())
        if (conversation.enabledLorebookIdsJson == nextJson) return

        viewModelScope.launch {
            val updated = conversation.copy(enabledLorebookIdsJson = nextJson)
            conversationRepository.updateConversation(updated)
            _conversationList.update { list ->
                list.map { if (it.id == conversationId) updated else it }
            }
        }
    }

    fun selectAssistant(id: String) {
        viewModelScope.launch {
            assistantRepository.setDefaultAssistant(id)
            _currentAssistantId.value = id
            refreshVisibleConversationsForAssistant(id)
            selectLatestConversationForAssistant(id)
        }
    }

    fun selectProvider(providerId: String) {
        viewModelScope.launch {
            providerRepository.selectProvider(providerId)
        }
    }

    fun selectProviderAndModel(providerId: String, modelInternalId: String) {
        viewModelScope.launch {
            providerRepository.selectProviderAndModel(providerId, modelInternalId)
        }
    }

    fun selectModel(modelInternalId: String) {
        viewModelScope.launch {
            providerRepository.selectModel(modelInternalId)
        }
    }

    fun selectChatRunMode(mode: ChatRunMode) {
        _chatRunMode.value = mode
        if (mode == ChatRunMode.CHAT) {
            _workspaceAccessMode.value = WorkspaceAccessMode.NO_WORKSPACE
        }
    }

    fun selectWorkspaceAccessMode(mode: WorkspaceAccessMode) {
        _workspaceAccessMode.value = mode
        if (mode != WorkspaceAccessMode.NO_WORKSPACE) {
            _chatRunMode.value = ChatRunMode.AGENTS
        }
    }

    /**
     * 切换当前会话思考量:
     *
     * - 当前已经在某个会话里 → 把会话表的 thinkingLevel 直接覆盖,落库;
     * - 当前是"新会话"占位状态 → 只更新内存里的 [currentThinkingLevel],等创建会话时落库。
     *
     * 与 [selectPresetForCurrentConversation] 同模式:会话级,切档位不影响其他会话。
     */
    fun selectThinkingLevel(level: ThinkingLevel) {
        _currentThinkingLevel.value = level

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        val conversation = _conversationList.value.firstOrNull { it.id == conversationId } ?: return
        if (conversation.thinkingLevel == level) return

        viewModelScope.launch {
            val updated = conversation.copy(thinkingLevel = level)
            conversationRepository.updateConversation(updated)
            _conversationList.update { list ->
                list.map { if (it.id == conversationId) updated else it }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** 切换当前会话是否启用某个内置工具。空会话占位态只改内存,首条消息创建会话时落库。 */
    fun setToolEnabledForCurrentConversation(toolId: String, enabled: Boolean) {
        if (toolId.isBlank()) return
        val nextEnabledToolIds = if (enabled) {
            _currentEnabledToolIds.value + toolId
        } else {
            _currentEnabledToolIds.value - toolId
        }
        applyEnabledToolIdsToCurrentConversation(nextEnabledToolIds)
    }

    /** 工具选择 Sheet "应用"时调用,一次性替换当前会话启用的工具集。只保留注册表里存在的工具 id。 */
    fun updateToolSelection(selectedToolIds: Set<String>) {
        val validIds = selectedToolIds.filter { id -> chatTools.any { it.id == id } }.toSet()
        applyEnabledToolIdsToCurrentConversation(validIds)
    }

    private fun applyEnabledToolIdsToCurrentConversation(nextEnabledToolIds: Set<String>) {
        val nextToolMode = toolModeForEnabledToolIds(nextEnabledToolIds)
        _currentEnabledToolIds.value = nextEnabledToolIds
        _currentToolMode.value = nextToolMode

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        val conversation = _conversationList.value.firstOrNull { it.id == conversationId } ?: return
        val nextJson = encodeStringListToJson(nextEnabledToolIds.toList())
        if (conversation.enabledToolIdsJson == nextJson) return

        viewModelScope.launch {
            val updated = conversation.copy(
                enabledToolIdsJson = nextJson,
                toolMode = nextToolMode,
            )
            conversationRepository.updateConversation(updated)
            _conversationList.update { list ->
                list.map { if (it.id == conversationId) updated else it }
            }
        }
    }

    /** 用户对待确认工具调用点了"允许 / 拒绝"。回填给挂起的 tool loop 并清空待确认状态。 */
    fun resolveToolApproval(approved: Boolean) {
        toolApprovalDeferred?.complete(approved)
        toolApprovalDeferred = null
        _pendingToolApproval.value = null
    }

    /**
     * 取消 / 停止生成时清理悬挂的工具确认。把未决 deferred 以"拒绝"完成,避免 tool loop 协程
     * 永久挂起;同时关掉弹窗状态,避免残留一个已无对应生成任务的确认框。
     */
    private fun clearPendingToolApproval() {
        toolApprovalDeferred?.complete(false)
        toolApprovalDeferred = null
        _pendingToolApproval.value = null
    }

    /**
     * 本次发送实际要带的内置工具。三重门控:
     * 1. 当前会话 enabledToolIds → 决定本会话启用哪些工具;
     * 2. settings.approvalRequiredToolIds → 在工具自身强制确认之外,允许用户额外要求确认;
     * 3. 注册表里存在的工具定义。
     */
    private fun resolveActiveTools(
        settings: com.nuttavern.data.tools.LocalToolsSettings,
    ): List<com.nuttavern.network.ChatTool> {
        val enabledToolIds = _currentEnabledToolIds.value
        if (enabledToolIds.isEmpty()) return emptyList()
        return chatToolRegistry.tools
            .filter { it.id in enabledToolIds }
            .map { tool ->
                tool.copy(
                    // 工具自身标注的高风险确认不能被设置页关闭;设置页只能给低风险工具额外加确认。
                    needsApproval = com.nuttavern.network.shouldRequireToolApproval(
                        toolRequiresApproval = tool.needsApproval,
                        userRequiresApproval = settings.isApprovalRequiredForTool(tool.id),
                    ),
                )
            }
    }

    /**
     * 构造人工确认回调。是否真正拦截由网络层按工具自身 needsApproval 决定;
     * 这里只负责把待确认信息抛给 UI、挂起等待用户点按。始终返回非空,保证高风险工具即使全局确认
     * 关闭也能被拦截。
     */
    private fun buildToolApprover(): com.nuttavern.network.ToolCallApprover {
        return { displayName, toolName, argumentsJson, details ->
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            toolApprovalDeferred = deferred
            _pendingToolApproval.value = PendingToolApproval(displayName, toolName, argumentsJson, details)
            try {
                deferred.await()
            } finally {
                // 无论是用户点按、协程取消还是异常退出,都清掉本次确认状态,避免弹窗 / deferred 悬挂。
                if (toolApprovalDeferred === deferred) {
                    toolApprovalDeferred = null
                    _pendingToolApproval.value = null
                }
            }
        }
    }

    private val _clipboardMessage = MutableStateFlow<String?>(null)
    val clipboardMessage: StateFlow<String?> = _clipboardMessage.asStateFlow()

    /**
     * 构造本次发送的工具会话上下文。把"当前会话已启用世界书集合"快照进 [com.nuttavern.network.ToolContext],
     * 供世界书编辑工具做作用范围校验。来源口径与运行时激活一致(见 SessionLorebookResolver)。
     *
     * 无会话(占位态发送)时 conversationId 为 null,世界书集合按当前 character / persona 仍可解析。
     */
    private suspend fun buildToolContext(conversationId: String?): com.nuttavern.network.ToolContext {
        val conversation = conversationId?.let { id ->
            _conversationList.value.firstOrNull { it.id == id }
                ?: _allConversations.value.firstOrNull { it.id == id }
        }
        val selectedLorebookIds = conversation?.let(::enabledLorebookIdsForConversation)
            ?: _currentEnabledLorebookIds.value
        val character = conversation?.characterId
            ?.let { id -> runCatching { characterRepository.getCharacterById(id) }.getOrNull() }
            ?: if (conversationId == null) _currentCharacter.value else null
        val persona = conversation?.personaId?.let { id -> findPersonaById(id) }
            ?: if (conversationId == null) _currentPersona.value else null
        val sessionLorebooks = sessionLorebookResolver
            .resolve(selectedLorebookIds.toList(), character, persona)
            .map { it.book }
        return object : com.nuttavern.network.ToolContext {
            override val conversationId: String? = conversationId
            override val sessionLorebooks = sessionLorebooks
        }
    }

    fun requestCopyMessage(message: Message) {
        _clipboardMessage.value = message.text
    }

    fun clearClipboardMessage() {
        _clipboardMessage.value = null
    }

    fun editMessage(messageId: String, content: String) {
        val conversationId = _currentConversationId.value
        if (conversationId.isBlank() || messageId.isBlank()) return

        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) return

        val existingMessage = _currentMessages.value.firstOrNull { it.id == messageId } ?: return
        // 编辑只改正文,保留思考 / 工具调用块**及其原始位置**:把编辑后的文本写回第一个 Text part 原位,
        // 删除其余 Text part(编辑框是单一文本框,多段正文合并到首个 Text 的位置)。
        // 没有 Text part(纯思考 / 工具消息)则在末尾追加一个 Text。
        val updatedParts = replaceTextParts(existingMessage.parts, normalizedContent)
        val updatedMessage = existingMessage.copy(parts = updatedParts)

        viewModelScope.launch {
            conversationRepository.updateMessageParts(
                messageId = messageId,
                parts = updatedParts,
            )
            _messagesByConversationId.update { messagesByConversationId ->
                val nextMessages = messagesByConversationId[conversationId].orEmpty()
                    .map { message -> if (message.id == messageId) updatedMessage else message }
                messagesByConversationId + (conversationId to nextMessages)
            }
            _currentMessages.value = _messagesByConversationId.value[conversationId].orEmpty()
        }
    }

    /**
     * 把编辑后的正文写回 parts:替换第一个 Text part(保留其原始位置),删除其余 Text part,
     * 其它块(思考 / 工具)位置不变。没有 Text part 时在末尾追加一个 Text。
     */
    private fun replaceTextParts(parts: List<MessagePart>, newText: String): List<MessagePart> {
        if (parts.none { it is MessagePart.Text }) {
            return parts + MessagePart.Text(newText)
        }
        var textWritten = false
        return parts.mapNotNull { part ->
            if (part !is MessagePart.Text) return@mapNotNull part
            if (textWritten) {
                null
            } else {
                textWritten = true
                MessagePart.Text(newText)
            }
        }
    }

    fun regenerateFromMessage(message: Message) {
        if (_isReplying.value) return

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        if (message.role == "user") {
            val trimmedText = message.text.trim()
            if (trimmedText.isBlank()) return

            _isReplying.value = true

            streamingJob = viewModelScope.launch {
                try {
                    streamAssistantReplyForConversation(conversationId) {
                        conversationRepository.deleteMessagesAfter(conversationId, message.id)
                        keepMessagesThrough(conversationId, message.id)
                    }
                } catch (e: CancellationException) {
                    clearStreamingState(conversationId)
                    _isReplying.value = false
                } catch (e: Exception) {
                    _errorMessage.value = "重新生成失败，请检查网络、提供商配置或模型名称"
                    clearStreamingState(conversationId)
                    _isReplying.value = false
                }
            }
        } else {
            val messages = _currentMessages.value
            val precedingUserMessage = messages
                .takeWhile { it.id != message.id }
                .lastOrNull { it.role == "user" }

            if (precedingUserMessage == null) {
                _errorMessage.value = "无法重新生成：没有对应的用户消息"
                return
            }

            _isReplying.value = true

            streamingJob = viewModelScope.launch {
                try {
                    streamAssistantReplyForConversation(conversationId) {
                        conversationRepository.deleteMessagesFrom(conversationId, message.id)
                        keepMessagesBefore(conversationId, message.id)
                    }
                } catch (e: CancellationException) {
                    clearStreamingState(conversationId)
                    _isReplying.value = false
                } catch (e: Exception) {
                    _errorMessage.value = "重新生成失败，请检查网络、提供商配置或模型名称"
                    clearStreamingState(conversationId)
                    _isReplying.value = false
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        cancelStreamingForConversation(id)
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
            _messagesByConversationId.update { currentMessagesByConversationId ->
                currentMessagesByConversationId - id
            }
        }

        if (_currentConversationId.value == id) {
            selectNextAvailableConversation()
        }
    }

    fun deleteMessage(messageId: String) {
        val conversationId = _currentConversationId.value
        if (conversationId.isBlank() || messageId.isBlank()) return

        viewModelScope.launch {
            conversationRepository.deleteMessage(messageId)
            _messagesByConversationId.update { messagesByConversationId ->
                val nextMessages = messagesByConversationId[conversationId].orEmpty()
                    .filterNot { it.id == messageId }
                messagesByConversationId + (conversationId to nextMessages)
            }
            _currentMessages.value = _messagesByConversationId.value[conversationId].orEmpty()
        }
    }

    fun renameConversation(id: String, title: String) {
        val normalizedTitle = title.trim().take(MAX_CONVERSATION_TITLE_LENGTH)
        if (normalizedTitle.isBlank()) return

        val conversation = _allConversations.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            conversationRepository.renameConversation(conversation, normalizedTitle)
        }
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    private suspend fun ensureCurrentConversation(firstMessage: String, createdAt: Long): String {
        val currentConversationId = _currentConversationId.value
        val currentConversation = _conversationList.value.firstOrNull {
            it.id == currentConversationId && it.assistantId == _currentAssistantId.value && !it.archived
        }
        if (currentConversation != null) return currentConversation.id

        val characterId = _currentCharacterId.value
        val character = characterId?.let { runCatching { characterRepository.getCharacterById(it) }.getOrNull() }
        // 新会话 persona / preset 初值优先级:用户在抽屉里预选(`_currentXxx.value`) > 默认值。
        // 进入这里通常 _currentXxx 已经是 startNewConversation 设置好的默认;用户在新会话占位
        // 状态切了对应字段才会偏离默认。
        val personaId = _currentPersonaId.value ?: resolveDefaultPersonaIdOrNull()
        val presetId = _currentPresetId.value ?: resolveDefaultPresetId()
        val thinkingLevel = _currentThinkingLevel.value
        val enabledRegexGroupIds = snapshotEnabledRegexGroupIdsJson()
        val enabledOrphanRegexIds = snapshotEnabledOrphanRegexIdsJson()
        val enabledToolIds = _currentEnabledToolIds.value
        val enabledToolIdsJson = snapshotEnabledToolIdsJson()
        val enabledLorebookIds = _currentEnabledLorebookIds.value
        val enabledLorebookIdsJson = snapshotEnabledLorebookIdsJson()
        val toolMode = toolModeForEnabledToolIds(enabledToolIds)
        val newConversationId = "conv-${System.currentTimeMillis()}"
        val titleSeed = character?.name?.takeIf { it.isNotBlank() } ?: firstMessage
        val conversation = ConversationSummary(
            id = newConversationId,
            title = titleSeed.take(20),
            lastMessageTime = "刚刚",
            assistantId = getCurrentAssistantId(),
            groupLabel = "今天",
            characterId = character?.id,
            personaId = personaId,
            presetId = presetId,
            enabledRegexGroupIds = enabledRegexGroupIds,
            enabledOrphanRegexIds = enabledOrphanRegexIds,
            enabledLorebookIdsJson = enabledLorebookIdsJson,
            enabledToolIdsJson = enabledToolIdsJson,
            thinkingLevel = thinkingLevel,
            toolMode = toolMode,
        )

        conversationRepository.createConversation(conversation, createdAt)
        _conversationList.update { sortConversations(listOf(conversation) + it) }
        _messagesByConversationId.update { it + (newConversationId to emptyList()) }
        _currentConversationId.value = newConversationId
        _currentPersonaId.value = personaId
        _currentPresetId.value = presetId
        _currentThinkingLevel.value = thinkingLevel
        _currentToolMode.value = toolMode
        _currentEnabledToolIds.value = enabledToolIds
        _currentEnabledLorebookIds.value = enabledLorebookIds
        _currentMessages.value = emptyList()

        // 角色绑定时插入 greeting 作为新会话的首条 assistant 消息(对齐酒馆 first_mes 行为)。
        // 当前 MVP 只用 firstMessage,alternate_greetings 等 swipe 模块上线后再消费。
        val greeting = character?.firstMessage?.takeIf { it.isNotBlank() }
        if (greeting != null) {
            val greetingMessage = Message(
                id = createMessageId("assistant"),
                role = "assistant",
                parts = listOf(MessagePart.Text(greeting)),
            )
            // greeting 必须在 user 消息之前落库,createdAt 减 1 ms 保证排序稳定。
            appendMessage(newConversationId, greetingMessage, createdAt - 1)
        }

        return newConversationId
    }

    private suspend fun appendMessage(conversationId: String, message: Message, createdAt: Long) {
        conversationRepository.appendMessage(conversationId, message, createdAt)
        _messagesByConversationId.update { messagesByConversationId ->
            val nextMessages = messagesByConversationId[conversationId].orEmpty() + message
            messagesByConversationId + (conversationId to nextMessages)
        }

        if (_currentConversationId.value == conversationId) {
            _currentMessages.value = _messagesByConversationId.value[conversationId].orEmpty()
        }
    }

    private suspend fun refreshConversationTime(conversationId: String, updatedAt: Long) {
        val updatedConversation = _conversationList.value.firstOrNull { it.id == conversationId }
            ?.copy(lastMessageTime = "刚刚", groupLabel = "今天")
            ?: return

        conversationRepository.updateConversation(updatedConversation, updatedAt)
        _conversationList.update { list ->
            sortConversations(list.map { conversation ->
                if (conversation.id == conversationId) updatedConversation else conversation
            })
        }
    }

    private fun selectNextAvailableConversation() {
        val nextConversation = latestConversationForAssistant(_currentAssistantId.value)
        if (nextConversation == null) {
            _currentConversationId.value = ""
            _currentMessages.value = emptyList()
            _draft.value = ""
            _isReplying.value = false
            return
        }

        // 同步切到 next 会话锁定的 character / persona / preset / thinkingLevel,与 selectConversation
        // 一致;否则删当前会话回落后这几项仍停留在已删会话,UI 显示错值。
        _currentConversationId.value = nextConversation.id
        _currentCharacterId.value = nextConversation.characterId
        _currentPersonaId.value = nextConversation.personaId
        _currentPresetId.value = nextConversation.presetId
        _currentThinkingLevel.value = nextConversation.thinkingLevel
        _currentToolMode.value = nextConversation.toolMode
        _currentEnabledToolIds.value = enabledToolIdsForConversation(nextConversation)
        _currentEnabledLorebookIds.value = enabledLorebookIdsForConversation(nextConversation)
        _draft.value = ""
        _isReplying.value = false
    }

    private fun isActiveConversation(conversationId: String): Boolean {
        return _conversationList.value.any { it.id == conversationId && !it.archived }
    }

    private fun refreshVisibleConversationsForAssistant(assistantId: String) {
        _conversationList.value = sortConversations(
            _nonArchivedConversations.value.filter { conversation ->
                conversation.assistantId == assistantId && !conversation.archived
            },
        )
    }

    private fun selectLatestConversationForAssistant(assistantId: String) {
        val currentConversation = _nonArchivedConversations.value.firstOrNull { conversation ->
            conversation.id == _currentConversationId.value &&
                conversation.assistantId == assistantId &&
                !conversation.archived
        }
        if (currentConversation != null) return

        val nextConversation = latestConversationForAssistant(assistantId)
        _currentConversationId.value = nextConversation?.id.orEmpty()
        // 同步切到新会话锁定的 character / persona / preset id,与 nonArchivedConversations
        // 自动恢复路径一致。nextConversation 为 null 时不清:保留用户预选的"下一条新会话"。
        if (nextConversation != null) {
            _currentCharacterId.value = nextConversation.characterId
            _currentPersonaId.value = nextConversation.personaId
            _currentPresetId.value = nextConversation.presetId
            _currentThinkingLevel.value = nextConversation.thinkingLevel
            _currentToolMode.value = nextConversation.toolMode
            _currentEnabledToolIds.value = enabledToolIdsForConversation(nextConversation)
            _currentEnabledLorebookIds.value = enabledLorebookIdsForConversation(nextConversation)
        }
        _currentMessages.value = emptyList()
        _draft.value = ""
    }

    private fun latestConversationForAssistant(assistantId: String): ConversationSummary? {
        return sortConversations(
            _nonArchivedConversations.value.filter { conversation ->
                conversation.assistantId == assistantId && !conversation.archived
            },
        ).firstOrNull()
    }

    private suspend fun saveAssistantReplyIfConversationExists(
        conversationId: String,
        content: String,
        reasoningContent: String = "",
        reasoningDurationMillis: Long = 0L,
        toolMarks: List<StreamingToolMark> = emptyList(),
    ) {
        val reasoningSplit = GeneratedContentSanitizer.splitReasoningFromAnswer(content)
        // answerContent 不在这里 trimEnd:工具切点 contentOffset 是针对未裁剪的正文记录的,
        // 提前 trim 会让切点错位。裁剪只在最后一段文本上做。
        val answerContent = reasoningSplit.answerContent
        val normalizedReasoningContent = buildString {
            append(reasoningContent.trimEnd())
            if (reasoningSplit.reasoningContent.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(reasoningSplit.reasoningContent.trimEnd())
            }
        }.trimEnd()
        if (answerContent.isBlank() && normalizedReasoningContent.isBlank() && toolMarks.isEmpty()) return
        if (!conversationRepository.nonArchivedConversationExists(conversationId)) return

        val createdAt = System.currentTimeMillis()
        val assistantParts = buildList {
            if (normalizedReasoningContent.isNotBlank()) {
                add(
                    MessagePart.Reasoning(
                        text = normalizedReasoningContent,
                        durationMillis = maxOf(100L, reasoningDurationMillis),
                    )
                )
            }
            addAll(buildInterleavedToolAndTextParts(conversationId, answerContent, toolMarks))
        }
        // 口径收口:AI_OUTPUT 正则可能把正文替换成空串,reasoning / 工具又都为空时 assistantParts 会空。
        // 不落空消息(否则渲染出一条只能长按、内容空白的行)。
        if (assistantParts.isEmpty()) return
        appendMessage(
            conversationId = conversationId,
            message = Message(
                id = createMessageId("assistant"),
                role = "assistant",
                parts = assistantParts,
            ),
            createdAt = createdAt,
        )
        refreshConversationTime(conversationId, createdAt)
    }

    /**
     * 按工具到达切点把正文切段,与工具调用交错,还原"文字 → 工具 → 文字"的真实顺序。
     *
     * 切点编排走纯函数 [interleaveContentWithTools];本方法只负责把切出来的文字段跑
     * AI_OUTPUT 正则(切点处前后文本是模型分别产出的独立片段,逐段匹配比跨工具拼接更符合语义),
     * 并把工具片段配上对应 [MessagePart.ToolCall]。
     *
     * AI_OUTPUT 正则在落库前跑;流式期间不跑(需完整文本才能正确匹配)。无工具时与历史行为一致。
     */
    private suspend fun buildInterleavedToolAndTextParts(
        conversationId: String,
        answerContent: String,
        toolMarks: List<StreamingToolMark>,
    ): List<MessagePart> {
        val sortedMarks = toolMarks.sortedBy { it.contentOffset }
        val slices = interleaveContentWithTools(answerContent, sortedMarks.map { it.contentOffset })

        val parts = mutableListOf<MessagePart>()
        for (slice in slices) {
            when (slice) {
                is ContentSlice.Text -> {
                    val segment = if (slice.isTail) slice.raw.trimEnd() else slice.raw
                    if (segment.isEmpty()) continue
                    val processed = applyAiOutputRegex(conversationId, segment)
                    if (processed.isNotEmpty()) parts += MessagePart.Text(processed)
                }
                is ContentSlice.Tool -> parts += sortedMarks[slice.toolIndex].part
            }
        }
        return parts
    }

    /**
     * 对模型回复跑一次 AI_OUTPUT 阶段正则。会话查不到 / 正则脚本为空时直接返回原文,
     * 不阻塞落库;脚本运行抛异常 [RegexEngine] 内部已兜底原文输出。
     *
     * **改聊天文件场景**:`isMarkdown=false && isPrompt=false`,只跑两个 Ephemerality 开关都不勾的脚本。
     * 短暂模式(`promptOnly=true`)的脚本不在这里跑 —— 它们要在 PromptComposer 拼接时跑。
     */
    private suspend fun applyAiOutputRegex(conversationId: String, raw: String): String {
        if (raw.isEmpty()) return raw
        val conversation = _conversationList.value.firstOrNull { it.id == conversationId }
            ?: _allConversations.value.firstOrNull { it.id == conversationId }
            ?: return raw
        val character = conversation.characterId
            ?.let { runCatching { characterRepository.getCharacterById(it) }.getOrNull() }
        val preset = resolvePresetForConversation(conversation.presetId)
        val globalScripts = resolveRegexScriptsForConversation(
            conversation.enabledRegexGroupIds,
            conversation.enabledOrphanRegexIds,
        )
        val presetScripts = preset.presetRegexScripts()
        val (characterAllowed, presetAllowed) = currentRegexScopeFlags()

        return regexEngine.getRegexedString(
            raw = raw,
            placement = RegexPlacement.AI_OUTPUT,
            globalScripts = globalScripts,
            scopedScripts = character?.regexScripts.orEmpty(),
            presetScripts = presetScripts,
            characterAllowed = characterAllowed,
            presetAllowed = presetAllowed,
        )
    }

    /**
     * 用户输入落库前先跑 USER_INPUT 改文件正则,对齐酒馆 `sendMessageAsUser` 调用。
     *
     * **改聊天文件场景**:`isMarkdown=false && isPrompt=false`,只跑两个 Ephemerality 都不勾的脚本。
     * 短暂模式(`promptOnly=true`)在 PromptComposer A0 阶段再跑一次,各管各的脚本。
     */
    private suspend fun applyUserInputRegexForChatFile(conversationId: String, raw: String): String {
        if (raw.isEmpty()) return raw
        val conversation = _conversationList.value.firstOrNull { it.id == conversationId }
            ?: _allConversations.value.firstOrNull { it.id == conversationId }
            ?: return raw
        val character = conversation.characterId
            ?.let { runCatching { characterRepository.getCharacterById(it) }.getOrNull() }
        val preset = resolvePresetForConversation(conversation.presetId)
        val globalScripts = resolveRegexScriptsForConversation(
            conversation.enabledRegexGroupIds,
            conversation.enabledOrphanRegexIds,
        )
        val presetScripts = preset.presetRegexScripts()
        val (characterAllowed, presetAllowed) = currentRegexScopeFlags()

        return regexEngine.getRegexedString(
            raw = raw,
            placement = RegexPlacement.USER_INPUT,
            globalScripts = globalScripts,
            scopedScripts = character?.regexScripts.orEmpty(),
            presetScripts = presetScripts,
            characterAllowed = characterAllowed,
            presetAllowed = presetAllowed,
        )
    }

    /**
     * 当前生效的 SCOPED / PRESET 正则总开关(对齐酒馆 character_allowed_regex / preset_allowed_regex)。
     *
     * 暂未接入 UI(总开关默认全开)。后续若加全局设置或会话级覆盖,这里是唯一改动点 —
     * 三路(applyAiOutputRegex / applyUserInputRegexForChatFile / buildPromptForSend)
     * 都从本 helper 取,保证口径一致。
     */
    private fun currentRegexScopeFlags(): Pair<Boolean, Boolean> = true to true

    /**
     * 按会话引用的正则 id 列表展开启用的规则列表。
     *
     * 会话上存的是创建时快照的"启用组 id + 启用散规则 id"。运行时按这两份 id 列表从
     * [RegexScriptRepository] 当前快照里取对应规则:
     * - 组 id 找到 → 展开组内全部规则(组内顺序保留)
     * - 散规则 id 找到 → 直接加入
     * - id 找不到(规则 / 组已被删除)→ 静默忽略
     *
     * 顺序:组 id 列表顺序 → 散规则 id 列表顺序。
     *
     * [enabledGroupIdsJson] / [enabledOrphanIdsJson] 为 null 时退化为用户级当前启用列表
     * (兼容老会话 / 迁移前数据)。
     */
    private suspend fun resolveRegexScriptsForConversation(
        enabledGroupIdsJson: String?,
        enabledOrphanIdsJson: String?,
    ): List<com.nuttavern.data.regex.RegexScript> {
        val snapshot = regexScriptRepository.snapshot.first()

        // null = 老会话兼容:退化为用户级当前启用列表
        if (enabledGroupIdsJson == null && enabledOrphanIdsJson == null) {
            return regexScriptRepository.expandedEnabledScripts.first()
        }

        val groupIds = parseJsonStringList(enabledGroupIdsJson)
        val orphanIds = parseJsonStringList(enabledOrphanIdsJson)
        val groupById = snapshot.groups.associateBy { it.id }
        val orphanById = snapshot.orphanScripts.associateBy { it.id }

        val result = mutableListOf<com.nuttavern.data.regex.RegexScript>()
        for (id in groupIds) {
            val group = groupById[id] ?: continue
            result.addAll(group.scripts)
        }
        for (id in orphanIds) {
            val script = orphanById[id] ?: continue
            result.add(script)
        }
        return result
    }

    private fun parseJsonStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
        } catch (e: Throwable) {
            // 数据库里 JSON 损坏 / schema 变更 → 至少打 Log,避免"所有正则不生效"无感知。
            android.util.Log.w(
                "ChatViewModel",
                "parseJsonStringList failed, treating as empty: $json",
                e,
            )
            emptyList()
        }
    }

    /**
     * 把当前用户级启用的正则组 / 散规则 id 序列化为 JSON 字符串,用于新会话快照。
     */
    private suspend fun snapshotEnabledRegexGroupIdsJson(): String {
        val snapshot = regexScriptRepository.snapshot.first()
        val ids = snapshot.groups.filter { it.enabled }.map { it.id }
        return encodeStringListToJson(ids)
    }

    private suspend fun snapshotEnabledOrphanRegexIdsJson(): String {
        val snapshot = regexScriptRepository.snapshot.first()
        val ids = snapshot.orphanScripts.filter { !it.disabled }.map { it.id }
        return encodeStringListToJson(ids)
    }

    private fun snapshotEnabledToolIdsJson(): String {
        return encodeStringListToJson(_currentEnabledToolIds.value.toList())
    }

    private fun snapshotEnabledLorebookIdsJson(): String {
        return encodeStringListToJson(_currentEnabledLorebookIds.value.toList())
    }

    private fun enabledLorebookIdsForConversation(conversation: ConversationSummary): Set<String> {
        return parseJsonStringList(conversation.enabledLorebookIdsJson).toSet()
    }

    private fun enabledToolIdsForConversation(conversation: ConversationSummary): Set<String> {
        val storedIds = parseJsonStringList(conversation.enabledToolIdsJson)
        if (storedIds.isNotEmpty() || conversation.enabledToolIdsJson == "[]") return storedIds.toSet()
        if (conversation.toolMode == com.nuttavern.data.tools.ConversationToolMode.FORCE_OFF) return emptySet()
        return localToolsSettings.value.enabledToolIds
    }

    private fun defaultToolIdsForPlaceholder(
        toolMode: com.nuttavern.data.tools.ConversationToolMode,
    ): Set<String> {
        return when (toolMode) {
            com.nuttavern.data.tools.ConversationToolMode.FORCE_OFF -> emptySet()
            com.nuttavern.data.tools.ConversationToolMode.FORCE_ON -> localToolsSettings.value.enabledToolIds
            com.nuttavern.data.tools.ConversationToolMode.FOLLOW_GLOBAL -> defaultEnabledToolIdsForNewConversation()
        }
    }

    private fun defaultEnabledToolIdsForNewConversation(): Set<String> {
        val settings = localToolsSettings.value
        if (!settings.defaultEnabled) return emptySet()
        return settings.enabledToolIds
    }

    private fun toolModeForEnabledToolIds(
        enabledToolIds: Set<String>,
    ): com.nuttavern.data.tools.ConversationToolMode {
        return if (enabledToolIds.isEmpty()) {
            com.nuttavern.data.tools.ConversationToolMode.FORCE_OFF
        } else {
            com.nuttavern.data.tools.ConversationToolMode.FORCE_ON
        }
    }

    private fun encodeStringListToJson(ids: List<String>): String {
        val array = kotlinx.serialization.json.buildJsonArray {
            ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        return array.toString()
    }

    private fun cancelStreamingForConversation(conversationId: String) {
        if (_streamingConversationId.value != conversationId) return

        streamingJob?.cancel()
        streamingJob = null
        clearPendingToolApproval()
        clearStreamingState(conversationId)
        _isReplying.value = false
    }

    private fun keepMessagesThrough(conversationId: String, messageId: String) {
        val messages = _messagesByConversationId.value[conversationId].orEmpty()
        val anchorIndex = messages.indexOfFirst { it.id == messageId }
        if (anchorIndex < 0) return

        val retainedMessages = messages.take(anchorIndex + 1)
        _messagesByConversationId.update { it + (conversationId to retainedMessages) }
        if (_currentConversationId.value == conversationId) {
            _currentMessages.value = retainedMessages
        }
    }

    private fun keepMessagesBefore(conversationId: String, messageId: String) {
        val messages = _messagesByConversationId.value[conversationId].orEmpty()
        val anchorIndex = messages.indexOfFirst { it.id == messageId }
        if (anchorIndex < 0) return

        val retainedMessages = messages.take(anchorIndex)
        _messagesByConversationId.update { it + (conversationId to retainedMessages) }
        if (_currentConversationId.value == conversationId) {
            _currentMessages.value = retainedMessages
        }
    }

    private suspend fun streamAssistantReplyForConversation(
        conversationId: String,
        beforeBuildMessages: suspend () -> Unit = {},
    ) {
        providerRepository.initialize()
        val provider = providerRepository.currentProvider()
        val model = providerRepository.currentModel()

        if (provider == null || model == null) {
            _errorMessage.value = "请先在设置中配置提供商和模型"
            _isReplying.value = false
            return
        }

        if (provider.apiKey.isBlank()) {
            _errorMessage.value = "请先在设置中填写 ${provider.name} 的 API Key"
            _isReplying.value = false
            return
        }
        if (provider.baseUrl.isBlank()) {
            _errorMessage.value = "请先在设置中填写 ${provider.name} 的 Base URL"
            _isReplying.value = false
            return
        }

        beforeBuildMessages()

        val prepared = buildPromptForSend(
            conversationId = conversationId,
            provider = provider,
            model = model,
            pendingUserMessage = null,
        )
        persistLorebookTimedEffects(conversationId, prepared.lorebookTimedEffectsJson)

        _streamingConversationId.value = conversationId
        _streamingContent.value = ""
        _streamingRawContent.value = ""
        _streamingExplicitReasoningContent.value = ""
        _streamingReasoningContent.value = ""
        _streamingReasoningDurationMillis.value = 0L
        streamingReasoningStartedAtMillis = null
        streamingReasoningEndedAtMillis = null
        val toolsSettings = toolsSettingsRepository.settings.first()
        val localToolsConfig = localToolsRepository.settings.first()
        val activeTools = resolveActiveTools(localToolsConfig)
        val toolContext = buildToolContext(conversationId)

        chatApiClient.streamChat(
            provider = provider,
            model = model,
            messages = prepared.messages,
            systemPrompt = prepared.systemPrompt,
            thinkingLevel = _currentThinkingLevel.value,
            generationParams = prepared.generationParams,
            tools = activeTools,
            toolCallRecurseLimit = toolsSettings.toolCallRecurseLimit,
            requireToolApproval = false,
            approveToolCall = buildToolApprover(),
            toolContext = toolContext,
        ).collect { chunk ->
            when {
                chunk.error != null -> {
                    _errorMessage.value = chunk.error
                    clearStreamingState(conversationId)
                    _isReplying.value = false
                }
                chunk.isDone -> {
                    updateReasoningDurationBeforeSaving()
                    val finalContent = _streamingContent.value
                    val finalReasoningContent = _streamingReasoningContent.value
                    val finalReasoningDurationMillis = _streamingReasoningDurationMillis.value
                    val finalToolMarks = _streamingToolMarks.value
                    clearStreamingState(conversationId)
                    saveAssistantReplyIfConversationExists(
                        conversationId,
                        finalContent,
                        finalReasoningContent,
                        finalReasoningDurationMillis,
                        finalToolMarks,
                    )
                    _isReplying.value = false
                }
                else -> {
                    if (_streamingConversationId.value == conversationId) {
                        _currentToolActivity.value = chunk.toolActivity
                        chunk.toolCall?.let { appendStreamingToolCall(it) }
                        appendStreamingChunk(chunk.content, chunk.reasoningContent)
                    }
                }
            }
        }
    }

    private fun buildChatMessages(conversationId: String): List<ChatMessage> {
        val messages = _messagesByConversationId.value[conversationId].orEmpty()
        return messages.map { ChatMessage(role = it.role, content = it.text) }
    }

    /**
     * 把消息的图片附件读成 base64,供拼接管线透传到请求体。文件读不出(被删 / 损坏)的附件跳过。
     * 只在图片注入门控通过时调用(模型支持 IMAGE + 预设 media_inlining 开)。
     */
    private fun encodeAttachmentsForRequest(attachments: List<ImageAttachment>): List<ChatImage> {
        if (attachments.isEmpty()) return emptyList()
        return attachments.mapNotNull { attachment ->
            val bytes = conversationRepository.readImageBytes(attachment.path) ?: return@mapNotNull null
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            ChatImage(base64Data = base64, mimeType = attachment.mimeType)
        }
    }

    /**
     * 取当前会话的 PromptComposer 输入快照。
     *
     * 角色绑定:[ConversationSummary.characterId] 是会话创建时锁定的角色,后续切角色不会改写。
     * 找不到角色(比如角色被删了)时退化成 null,走"无角色"路径,保持历史会话仍可继续聊。
     *
     * 用户身份:[ConversationSummary.personaId] 是会话锁定的身份。null = "无身份";
     * 非 null 但查不到对应身份(被删了)时,退化为"无身份",避免拼接管线引用失效 id。
     *
     * 预设:[ConversationSummary.presetId] 是会话锁定的预设;查不到则退化为全局默认预设
     * (仓库永远兜底有一份默认预设)。
     */
    private suspend fun resolvePromptInputs(
        conversationId: String,
        userMessage: String?,
    ): PromptComposerInput {
        val conversation = _conversationList.value.firstOrNull { it.id == conversationId }
            ?: _allConversations.value.firstOrNull { it.id == conversationId }

        val character = conversation?.characterId
            ?.let { runCatching { characterRepository.getCharacterById(it) }.getOrNull() }

        val persona = conversation?.personaId?.let { id -> findPersonaById(id) }
        val preset = resolvePresetForConversation(conversation?.presetId)
        // 图片注入门控:模型支持 IMAGE 输入 + 预设 media_inlining 开。任一不满足则只发文本,
        // 附件元数据仍保留在消息上(气泡照常显示图),只是不拼进请求体。
        val allowImageInlining = isImageInputSupported() && preset.mediaInlining
        val history = _messagesByConversationId.value[conversationId].orEmpty()
            .map { message ->
                HistoryMessage(
                    role = message.role,
                    content = message.text,
                    images = if (allowImageInlining) encodeAttachmentsForRequest(message.attachments) else emptyList(),
                )
            }
        val globalRegexScripts = resolveRegexScriptsForConversation(
            conversation?.enabledRegexGroupIds,
            conversation?.enabledOrphanRegexIds,
        )
        val (characterAllowed, presetAllowed) = currentRegexScopeFlags()

        // 世界书激活:合并当前会话选中 + 角色世界书/辅助世界书 + persona 世界书
        val lorebookResult = runLorebookActivation(conversation, history, character, preset, persona)

        return PromptComposerInput(
            userMessage = userMessage,
            history = history,
            character = character,
            userPersona = persona,
            preset = preset,
            globalRegexScripts = globalRegexScripts,
            characterAllowedRegex = characterAllowed,
            presetAllowedRegex = presetAllowed,
            lorebookResult = lorebookResult,
        )
    }

    /** 执行世界书激活扫描。合并当前会话选中 + 角色世界书/辅助世界书 + persona 世界书。 */
    private suspend fun runLorebookActivation(
        conversation: ConversationSummary?,
        history: List<HistoryMessage>,
        character: com.nuttavern.data.character.Character?,
        preset: com.nuttavern.data.preset.Preset,
        persona: com.nuttavern.data.persona.UserPersona?,
    ): com.nuttavern.lorebook.LorebookEngine.ActivationResult? {
        // 三来源合并去重与世界书编辑工具共用同一口径,见 SessionLorebookResolver。
        val selectedLorebookIds = conversation?.let(::enabledLorebookIdsForConversation)
            ?: _currentEnabledLorebookIds.value
        val taggedLorebooks = sessionLorebookResolver.resolve(selectedLorebookIds.toList(), character, persona)
        if (taggedLorebooks.isEmpty()) {
            return com.nuttavern.lorebook.LorebookEngine.ActivationResult(
                nextTimedEffects = com.nuttavern.lorebook.LorebookTimedEffectState.Empty,
            )
        }


        val messages = history.map { it.content }.reversed()
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: "User"
        val charName = character?.name?.takeIf { it.isNotBlank() } ?: "Assistant"
        val messageNames = history.map { msg ->
            when (msg.role) {
                "user" -> userName
                "assistant" -> charName
                else -> "System"
            }
        }.reversed()

        val scanContext = com.nuttavern.lorebook.LorebookEngine.ScanContext(
            currentCharacterId = character?.id,
            personaDescription = persona?.description.orEmpty(),
            characterDescription = character?.description.orEmpty(),
            characterPersonality = character?.personality.orEmpty(),
            characterDepthPrompt = "", // V3 extensions.depth_prompt.prompt,当前未解析
            scenario = character?.scenario.orEmpty(),
            creatorNotes = character?.creatorNotes.orEmpty(),
            maxContextTokens = preset.openaiMaxContext,
        )

        return lorebookEngine.activate(
            messages = messages,
            messageNames = messageNames,
            lorebooks = taggedLorebooks,
            wiFormat = preset.wiFormat,
            scanContext = scanContext,
            messageCount = history.size,
            timedEffects = decodeLorebookTimedEffects(conversation?.lorebookTimedEffectsJson),
        )
    }

    private suspend fun persistLorebookTimedEffects(conversationId: String, timedEffectsJson: String?) {
        if (timedEffectsJson == null) return

        conversationRepository.updateLorebookTimedEffects(conversationId, timedEffectsJson)
        updateConversationTimedEffects(_conversationList, conversationId, timedEffectsJson)
        updateConversationTimedEffects(_nonArchivedConversations, conversationId, timedEffectsJson)
        updateConversationTimedEffects(_allConversations, conversationId, timedEffectsJson)
    }

    private fun updateConversationTimedEffects(
        state: MutableStateFlow<List<ConversationSummary>>,
        conversationId: String,
        timedEffectsJson: String,
    ) {
        state.update { conversations ->
            conversations.map { conversation ->
                if (conversation.id == conversationId) {
                    conversation.copy(lorebookTimedEffectsJson = timedEffectsJson)
                } else {
                    conversation
                }
            }
        }
    }

    private fun decodeLorebookTimedEffects(timedEffectsJson: String?): com.nuttavern.lorebook.LorebookTimedEffectState {
        if (timedEffectsJson.isNullOrBlank()) return com.nuttavern.lorebook.LorebookTimedEffectState.Empty
        return runCatching {
            lorebookTimedEffectJson.decodeFromString<com.nuttavern.lorebook.LorebookTimedEffectState>(timedEffectsJson)
        }.getOrElse {
            com.nuttavern.lorebook.LorebookTimedEffectState.Empty
        }
    }

    private fun encodeLorebookTimedEffects(
        timedEffects: com.nuttavern.lorebook.LorebookTimedEffectState,
    ): String {
        return lorebookTimedEffectJson.encodeToString(timedEffects)
    }

    /**
     * 按 id 在 [PersonaRepository] 里查身份。空 / "无"伪卡 id / 找不到都返回 null,
     * 让 PromptComposer 跳过用户身份块。
     */
    private suspend fun findPersonaById(id: String): UserPersona? {
        if (id == UserPersona.NONE_PERSONA_ID) return null
        val personas = personaRepository.personas.first()
        return personas.firstOrNull { it.id == id && !it.isNonePersona }
    }

    /**
     * 默认身份的 id。设置中没设默认 / 默认指向"无"伪卡 → 返回 null,新会话语义上"无身份"。
     */
    private suspend fun resolveDefaultPersonaIdOrNull(): String? {
        val defaultId = personaRepository.defaultPersonaId.first()
        return defaultId.takeIf { it != UserPersona.NONE_PERSONA_ID }
    }

    /**
     * 默认预设的 id。仓库永远兜底有一份默认预设,所以这里永远返回非 null。
     */
    private suspend fun resolveDefaultPresetId(): String {
        return presetRepository.defaultPresetId.first()
    }

    /**
     * 解析会话锁定的预设。null id / 查不到时退化为全局默认预设;默认预设也找不到则取首条。
     * 仓库的兜底机制保证至少有一份预设可用,这里不会返回 null。
     */
    private suspend fun resolvePresetForConversation(presetId: String?): Preset {
        val presets = presetRepository.presets.first()
        val targetId = presetId ?: presetRepository.defaultPresetId.first()
        return presets.firstOrNull { it.id == targetId }
            ?: presets.firstOrNull { it.id == Preset.DEFAULT_PRESET_ID }
            ?: presets.first()
    }

    /**
     * 走 PromptComposer 拼接当前会话的 prompt + messages。
     *
     * pendingUserMessage 为 null 时(regenerate / retry),PromptComposer 不会追加新用户消息,
     * 只用历史。预设永远存在(仓库兜底),所以**所有会话都走同一条 PromptComposer 路径**,
     * 不再有"无角色 + 无身份退回旧路径"分支。
     *
     * customPostProcessing 字段在 PromptComposer 阶段留空,这里从当前 [provider] 注入,
     * 避免 PromptComposer 持有 Provider 引用 — 后处理与连接绑定,与拼接逻辑解耦。
     */
    private suspend fun buildPromptForSend(
        conversationId: String,
        provider: Provider,
        model: Model,
        pendingUserMessage: String?,
    ): PreparedPrompt {
        val input = resolvePromptInputs(conversationId, pendingUserMessage)
        val composed = promptComposer.compose(input)
        val customPostProcessing = provider.customPromptPostProcessing.value
            .takeIf { it.isNotBlank() }
        return PreparedPrompt(
            systemPrompt = composed.systemPrompt ?: getSystemPrompt(provider, model),
            messages = composed.messages,
            generationParams = composed.generationParams.copy(
                customPostProcessing = customPostProcessing,
            ),
            lorebookTimedEffectsJson = input.lorebookResult?.nextTimedEffects?.let(::encodeLorebookTimedEffects),
        )
    }

    private data class PreparedPrompt(
        val systemPrompt: String,
        val messages: List<ChatMessage>,
        val generationParams: com.nuttavern.network.GenerationParams,
        val lorebookTimedEffectsJson: String?,
    )

    /**
     * 流式工具调用标记:工具 part + 它到达时已累积的正文字符数([contentOffset])。
     * 切点用于落库时把最终正文切成"工具前 / 工具后"两段,实现有序穿插。
     */
    private data class StreamingToolMark(
        val part: MessagePart.ToolCall,
        val contentOffset: Int,
    )

    /**
     * 把流式过程中执行完成的工具调用累积进时间线,并记录"此刻已累积的正文长度"作为切点。
     * 落库时按切点把正文切段,与工具交错还原真实顺序。
     */
    private fun appendStreamingToolCall(record: com.nuttavern.network.ToolCallRecord) {
        val contentOffset = _streamingContent.value.length
        _streamingToolMarks.update { marks ->
            marks + StreamingToolMark(
                part = MessagePart.ToolCall(
                    toolCallId = record.id,
                    toolName = record.name,
                    arguments = record.arguments,
                    result = record.result,
                    denied = record.denied,
                ),
                contentOffset = contentOffset,
            )
        }
    }

    private fun appendStreamingChunk(content: String, reasoningContent: String) {
        // 流式 chunk 必须**逐字符**保留:模型把段落分隔 / 列表换行 / 代码块前后空行
        // 拆成单独 token 下发(典型如 `\n` / `\n\n`),isNotBlank 会把它们全过滤掉,
        // 落到 UI 上就是 markdown 段落、列表、代码块全部塌成一行。
        // 这里只用 isNotEmpty 守住"真正的空字符串",其他一律拼进 raw buffer。
        val hasReasoningChunk = reasoningContent.isNotEmpty()
        if (hasReasoningChunk) {
            markReasoningStreamingActive()
        }

        if (reasoningContent.isNotEmpty()) {
            _streamingExplicitReasoningContent.update { it + reasoningContent }
        }
        if (content.isNotEmpty()) {
            _streamingRawContent.update { it + content }
        }

        val splitContent = GeneratedContentSanitizer.splitReasoningFromAnswer(_streamingRawContent.value)
        if (splitContent.reasoningContent.isNotBlank()) {
            markReasoningStreamingActive()
        }
        _streamingContent.value = splitContent.answerContent
        _streamingReasoningContent.value = buildString {
            append(_streamingExplicitReasoningContent.value)
            if (splitContent.reasoningContent.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(splitContent.reasoningContent)
            }
        }

        // 一旦开始输出正式回答,就锁死 reasoning 计时,避免回答阶段又错把 reasoning 计时的时长继续延长。
        if (_streamingContent.value.isNotBlank()) {
            finalizeReasoningDuration()
        }
    }

    private fun markReasoningStreamingActive() {
        // reasoning 已定格则不再延长,避免回答阶段又触发 reasoning 内容时把时长拉长。
        if (streamingReasoningEndedAtMillis != null) return

        val now = System.currentTimeMillis()
        val startedAt = streamingReasoningStartedAtMillis ?: now
        streamingReasoningStartedAtMillis = startedAt
        _streamingReasoningDurationMillis.value = now - startedAt
        startReasoningDurationTimer()
    }

    private fun startReasoningDurationTimer() {
        if (streamingReasoningTimerJob?.isActive == true) return

        streamingReasoningTimerJob = viewModelScope.launch {
            while (
                _streamingConversationId.value != null &&
                streamingReasoningStartedAtMillis != null &&
                streamingReasoningEndedAtMillis == null
            ) {
                streamingReasoningStartedAtMillis?.let { startedAt ->
                    _streamingReasoningDurationMillis.value = System.currentTimeMillis() - startedAt
                }
                delay(100L)
            }
        }
    }

    private fun finalizeReasoningDuration() {
        if (streamingReasoningEndedAtMillis != null) return
        val startedAt = streamingReasoningStartedAtMillis ?: return

        val endedAt = System.currentTimeMillis()
        streamingReasoningEndedAtMillis = endedAt
        _streamingReasoningDurationMillis.value = endedAt - startedAt
        streamingReasoningTimerJob?.cancel()
        streamingReasoningTimerJob = null
    }

    private fun updateReasoningDurationBeforeSaving() {
        if (_streamingReasoningContent.value.isBlank()) return
        // 流式整体结束时,如果之前没出现过正式回答(纯 reasoning 输出),也应在此定格。
        if (streamingReasoningEndedAtMillis == null) {
            finalizeReasoningDuration()
        }
    }

    private fun clearStreamingState(conversationId: String) {
        if (_streamingConversationId.value == conversationId) {
            _streamingConversationId.value = null
            _streamingContent.value = ""
            _streamingRawContent.value = ""
            _streamingExplicitReasoningContent.value = ""
            _streamingReasoningContent.value = ""
            _streamingReasoningDurationMillis.value = 0L
            _currentToolActivity.value = null
            _streamingToolMarks.value = emptyList()
            streamingReasoningStartedAtMillis = null
            streamingReasoningEndedAtMillis = null
            streamingReasoningTimerJob?.cancel()
            streamingReasoningTimerJob = null
        }
    }

    private fun getSystemPrompt(provider: Provider, model: Model): String {
        val currentConversation = _conversationList.value.firstOrNull { it.id == _currentConversationId.value }
        val assistantId = currentConversation?.assistantId ?: getCurrentAssistantId()
        val assistant = assistantRepository.assistantsState.firstOrNull { it.id == assistantId }
            ?: assistantRepository.assistantsState.firstOrNull { it.id == assistantRepository.defaultAssistantIdState }

        return assistant?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: "You are a helpful assistant. Respond clearly and concisely."
    }

    private fun createMessageId(prefix: String): String {
        return "msg-$prefix-${UUID.randomUUID()}"
    }

    private fun getCurrentAssistantId(): String {
        val selectedAssistantId = _currentAssistantId.value
        if (assistantRepository.assistantsState.any { it.id == selectedAssistantId }) {
            return selectedAssistantId
        }
        return assistantRepository.assistantsState.firstOrNull()?.id ?: "chat-assistant"
    }

    private fun sortConversations(conversations: List<ConversationSummary>): List<ConversationSummary> {
        return conversations.sortedWith(
            compareByDescending<ConversationSummary> { it.pinned }
                .thenBy { it.archived }
                .thenByDescending { it.lastMessageTime },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCurrentMessages() {
        viewModelScope.launch {
            _currentConversationId
                .flatMapLatest { conversationId ->
                    if (conversationId.isBlank()) {
                        MutableStateFlow(emptyList())
                    } else {
                        conversationRepository.observeMessages(conversationId)
                    }
                }
                .collectLatest { messages ->
                    val conversationId = _currentConversationId.value
                    _currentMessages.value = messages
                    if (conversationId.isNotBlank()) {
                        _messagesByConversationId.update { it + (conversationId to messages) }
                    }
                }
        }
    }
}

/**
 * 待人工确认的工具调用快照,供确认弹窗展示。
 *
 * @param displayName 工具中文名
 * @param toolName 工具函数名
 * @param argumentsJson 模型给出的实参 JSON 字符串
 */
data class PendingToolApproval(
    val displayName: String,
    val toolName: String,
    val argumentsJson: String,
    val details: com.nuttavern.network.ToolApprovalDetails? = null,
)
