package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.character.Character
import com.nuttavern.data.character.CharacterRepository
import com.nuttavern.data.model.AssistantConfig
import com.nuttavern.data.model.ChatRunMode
import com.nuttavern.data.model.ConversationSummary
import com.nuttavern.data.model.GeneratedContentSanitizer
import com.nuttavern.data.model.Message
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.Provider
import com.nuttavern.data.model.ThinkingLevel
import com.nuttavern.data.model.WorkspaceAccessMode
import com.nuttavern.data.persona.PersonaRepository
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.data.preset.Preset
import com.nuttavern.data.preset.PresetRepository
import com.nuttavern.data.regex.RegexPlacement
import com.nuttavern.data.regex.RegexScriptRepository
import com.nuttavern.data.local.SettingsDataStore
import com.nuttavern.data.repository.AssistantRepository
import com.nuttavern.data.repository.ConversationRepository
import com.nuttavern.data.repository.ProviderRepository
import com.nuttavern.network.ChatApiClient
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

private const val MAX_CONVERSATION_TITLE_LENGTH = 80

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
    private val settingsDataStore: SettingsDataStore,
    private val promptComposer: PromptComposer,
    private val regexEngine: RegexEngine,
    private val chatApiClient: ChatApiClient,
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

    private val _chatRunMode = MutableStateFlow(ChatRunMode.CHAT)
    val chatRunMode: StateFlow<ChatRunMode> = _chatRunMode.asStateFlow()

    private val _workspaceAccessMode = MutableStateFlow(WorkspaceAccessMode.NO_WORKSPACE)
    val workspaceAccessMode: StateFlow<WorkspaceAccessMode> = _workspaceAccessMode.asStateFlow()

    private val _draftThinkingLevel = MutableStateFlow(ThinkingLevel.MEDIUM)
    val draftThinkingLevel: StateFlow<ThinkingLevel> = _draftThinkingLevel.asStateFlow()

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

    /** 世界书总数 / 全局选中数,供 SettingsDrawer 显示。 */
    val lorebookCounts: StateFlow<Pair<Int, Int>> = combine(
        lorebookRepository.lorebooks,
        lorebookRepository.globalSelectedIds,
    ) { books, selectedIds ->
        books.size to selectedIds.size
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0 to 0,
    )

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
        // 当前会话 / 角色 / 身份 / 预设变化时立即写回持久化,关 app 不丢占位态。
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _currentConversationId,
                _currentCharacterId,
                _currentPersonaId,
                _currentPresetId,
            ) { conversationId, characterId, personaId, presetId ->
                SettingsDataStore.LastChatState(
                    conversationId = conversationId.takeIf { it.isNotBlank() },
                    characterId = characterId,
                    personaId = personaId,
                    presetId = presetId,
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
        } else {
            _currentMessages.value = emptyList()
        }
    }

    fun sendMessage(text: String) {
        if (_isReplying.value) return

        val trimmedText = text.trim()
        if (trimmedText.isBlank()) return

        _draft.value = ""
        _isReplying.value = true

        streamingJob = viewModelScope.launch {
            var conversationId = ""
            try {
                val createdAt = System.currentTimeMillis()
                conversationId = ensureCurrentConversation(trimmedText, createdAt)
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
                    content = persistedUserText,
                )
                appendMessage(conversationId, userMessage, createdAt)
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

                _streamingConversationId.value = conversationId
                _streamingContent.value = ""
                _streamingRawContent.value = ""
                _streamingExplicitReasoningContent.value = ""
                _streamingReasoningContent.value = ""
                _streamingReasoningDurationMillis.value = 0L
                streamingReasoningStartedAtMillis = null
                streamingReasoningEndedAtMillis = null

                chatApiClient.streamChat(
                    provider = provider,
                    model = model,
                    messages = prepared.messages,
                    systemPrompt = prepared.systemPrompt,
                    thinkingLevel = _draftThinkingLevel.value,
                    generationParams = prepared.generationParams,
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
                            clearStreamingState(conversationId)
                            saveAssistantReplyIfConversationExists(
                                conversationId,
                                finalContent,
                                finalReasoningContent,
                                finalReasoningDurationMillis,
                            )
                            _isReplying.value = false
                        }
                        else -> {
                            if (_streamingConversationId.value == conversationId) {
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
        _streamingConversationId.value?.let { conversationId ->
            val partialContent = _streamingContent.value.trimEnd()
            val partialReasoningContent = _streamingReasoningContent.value.trimEnd()
            val partialReasoningDurationMillis = _streamingReasoningDurationMillis.value
            clearStreamingState(conversationId)
            if (partialContent.isNotBlank() || partialReasoningContent.isNotBlank()) {
                viewModelScope.launch {
                    saveAssistantReplyIfConversationExists(
                        conversationId,
                        partialContent,
                        partialReasoningContent,
                        partialReasoningDurationMillis,
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
        _draft.value = ""
    }

    /**
     * 开启一个新会话:清空当前选中 + 草稿。真正的会话记录会在用户发出第一条消息时由
     * [ensureCurrentConversation] 创建,避免产生空会话。
     *
     * **保留** [currentCharacterId]:用户开新会话前已经选好的角色,在新会话里继续生效。
     * **重置** [currentPersonaId] / [currentPresetId] **为当前默认值**:与酒馆"新会话默认 = 全局默认"
     * 行为一致,用户在抽屉里改身份 / 预设会立即写到新建出来的会话上。
     */
    fun startNewConversation() {
        _currentConversationId.value = ""
        _currentMessages.value = emptyList()
        _draft.value = ""
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

    /** 批量更新世界书全局选中状态(Picker Sheet "应用"时调用)。 */
    fun updateLorebookSelection(selectedIds: Set<String>) {
        viewModelScope.launch {
            lorebookRepository.setGlobalSelected(selectedIds.toList())
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

    fun selectDraftThinkingLevel(level: ThinkingLevel) {
        _draftThinkingLevel.value = level
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private val _clipboardMessage = MutableStateFlow<String?>(null)
    val clipboardMessage: StateFlow<String?> = _clipboardMessage.asStateFlow()

    fun requestCopyMessage(message: Message) {
        _clipboardMessage.value = message.content
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
        val updatedMessage = existingMessage.copy(content = normalizedContent)

        viewModelScope.launch {
            conversationRepository.updateMessageContent(
                messageId = messageId,
                content = normalizedContent,
                reasoningContent = updatedMessage.reasoningContent,
                reasoningDurationMillis = updatedMessage.reasoningDurationMillis,
            )
            _messagesByConversationId.update { messagesByConversationId ->
                val nextMessages = messagesByConversationId[conversationId].orEmpty()
                    .map { message -> if (message.id == messageId) updatedMessage else message }
                messagesByConversationId + (conversationId to nextMessages)
            }
            _currentMessages.value = _messagesByConversationId.value[conversationId].orEmpty()
        }
    }

    fun regenerateFromMessage(message: Message) {
        if (_isReplying.value) return

        val conversationId = _currentConversationId.value
        if (conversationId.isBlank()) return

        if (message.role == "user") {
            val trimmedText = message.content.trim()
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
        val enabledRegexGroupIds = snapshotEnabledRegexGroupIdsJson()
        val enabledOrphanRegexIds = snapshotEnabledOrphanRegexIdsJson()
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
        )

        conversationRepository.createConversation(conversation, createdAt)
        _conversationList.update { sortConversations(listOf(conversation) + it) }
        _messagesByConversationId.update { it + (newConversationId to emptyList()) }
        _currentConversationId.value = newConversationId
        _currentPersonaId.value = personaId
        _currentPresetId.value = presetId
        _currentMessages.value = emptyList()

        // 角色绑定时插入 greeting 作为新会话的首条 assistant 消息(对齐酒馆 first_mes 行为)。
        // 当前 MVP 只用 firstMessage,alternate_greetings 等 swipe 模块上线后再消费。
        val greeting = character?.firstMessage?.takeIf { it.isNotBlank() }
        if (greeting != null) {
            val greetingMessage = Message(
                id = createMessageId("assistant"),
                role = "assistant",
                content = greeting,
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

        _currentConversationId.value = nextConversation.id
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
    ) {
        val reasoningSplit = GeneratedContentSanitizer.splitReasoningFromAnswer(content)
        // 只去掉**末尾**的多余空行,保留前导空行 / 中间所有换行,避免吞掉 markdown 段落分隔。
        // 流式期间不对中间态做任何裁剪;落库时只清理"模型最后一帧多甩出来的尾部 \n"。
        val normalizedContent = reasoningSplit.answerContent.trimEnd()
        val normalizedReasoningContent = buildString {
            append(reasoningContent.trimEnd())
            if (reasoningSplit.reasoningContent.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(reasoningSplit.reasoningContent.trimEnd())
            }
        }.trimEnd()
        if (normalizedContent.isBlank() && normalizedReasoningContent.isBlank()) return
        if (!conversationRepository.nonArchivedConversationExists(conversationId)) return

        // AI_OUTPUT 阶段正则:在落库前跑一次。流式期间不跑(完整文本才能正确匹配),
        // reasoning 暂不跑 REASONING placement(等第三批 reasoning 模块再接)。
        val regexProcessedContent = applyAiOutputRegex(conversationId, normalizedContent)

        val createdAt = System.currentTimeMillis()
        appendMessage(
            conversationId = conversationId,
            message = Message(
                id = createMessageId("assistant"),
                role = "assistant",
                content = regexProcessedContent,
                reasoningContent = normalizedReasoningContent,
                reasoningDurationMillis = if (normalizedReasoningContent.isNotBlank()) {
                    maxOf(100L, reasoningDurationMillis)
                } else {
                    0L
                },
            ),
            createdAt = createdAt,
        )
        refreshConversationTime(conversationId, createdAt)
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
        val presetScripts = extractPresetRegexScripts(preset)
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
        val presetScripts = extractPresetRegexScripts(preset)
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
     * 从 [Preset.extensions] 解出 `regex_scripts`。与 PromptComposer 同口径,失败返回空列表。
     */
    private fun extractPresetRegexScripts(preset: Preset): List<com.nuttavern.data.regex.RegexScript> {
        val node = preset.extensions["regex_scripts"] ?: return emptyList()
        return runCatching {
            REGEX_PRESET_JSON.decodeFromJsonElement(REGEX_PRESET_LIST_SERIALIZER, node)
        }.getOrDefault(emptyList())
    }

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

        _streamingConversationId.value = conversationId
        _streamingContent.value = ""
        _streamingRawContent.value = ""
        _streamingExplicitReasoningContent.value = ""
        _streamingReasoningContent.value = ""
        _streamingReasoningDurationMillis.value = 0L
        streamingReasoningStartedAtMillis = null
        streamingReasoningEndedAtMillis = null

        chatApiClient.streamChat(
            provider = provider,
            model = model,
            messages = prepared.messages,
            systemPrompt = prepared.systemPrompt,
            thinkingLevel = _draftThinkingLevel.value,
            generationParams = prepared.generationParams,
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
                    clearStreamingState(conversationId)
                    saveAssistantReplyIfConversationExists(
                        conversationId,
                        finalContent,
                        finalReasoningContent,
                        finalReasoningDurationMillis,
                    )
                    _isReplying.value = false
                }
                else -> {
                    if (_streamingConversationId.value == conversationId) {
                        appendStreamingChunk(chunk.content, chunk.reasoningContent)
                    }
                }
            }
        }
    }

    private fun buildChatMessages(conversationId: String): List<ChatMessage> {
        val messages = _messagesByConversationId.value[conversationId].orEmpty()
        return messages.map { ChatMessage(role = it.role, content = it.content) }
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
        val history = _messagesByConversationId.value[conversationId].orEmpty()
            .map { HistoryMessage(role = it.role, content = it.content) }
        val globalRegexScripts = resolveRegexScriptsForConversation(
            conversation?.enabledRegexGroupIds,
            conversation?.enabledOrphanRegexIds,
        )
        val (characterAllowed, presetAllowed) = currentRegexScopeFlags()

        // 世界书激活:合并全局选中 + 角色内嵌世界书
        val lorebookResult = runLorebookActivation(history, character, preset, persona)

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

    /**
     * 执行世界书激活扫描。合并全局选中的世界书 + 角色内嵌世界书。
     */
    private suspend fun runLorebookActivation(
        history: List<HistoryMessage>,
        character: com.nuttavern.data.character.Character?,
        preset: com.nuttavern.data.preset.Preset,
        persona: com.nuttavern.data.persona.UserPersona?,
    ): com.nuttavern.lorebook.LorebookEngine.ActivationResult? {
        val globalSelectedIds = lorebookRepository.globalSelectedIds.first()
        val allBooks = lorebookRepository.lorebooks.first()
        val selectedBooks = allBooks.filter { it.id in globalSelectedIds }

        // 角色内嵌世界书转换为 Lorebook 格式
        val characterBook = character?.characterBook?.let { cb ->
            com.nuttavern.data.lorebook.Lorebook(
                id = "__character_book__",
                name = cb.name ?: "角色内嵌世界书",
                scanDepth = cb.scanDepth ?: 2,
                tokenBudget = cb.tokenBudget ?: 25,
                recursiveScanning = cb.recursiveScanning ?: false,
                entries = cb.entries.mapIndexed { index, entry ->
                    com.nuttavern.data.lorebook.LorebookEntry(
                        uid = entry.id ?: index,
                        key = entry.keys,
                        keysecondary = entry.secondaryKeys,
                        comment = entry.comment ?: entry.name ?: "",
                        content = entry.content,
                        constant = entry.isConstant ?: false,
                        selective = entry.selective ?: true,
                        selectiveLogic = entry.selectiveLogic ?: com.nuttavern.data.lorebook.SelectiveLogic.AND_ANY,
                        order = entry.insertionOrder,
                        position = entry.position?.toIntOrNull() ?: com.nuttavern.data.lorebook.WiPosition.BEFORE,
                        disable = !entry.enabled,
                        depth = entry.depth ?: com.nuttavern.data.lorebook.LorebookEntry.DEFAULT_DEPTH,
                        role = entry.role?.toIntOrNull() ?: com.nuttavern.data.lorebook.WiRole.SYSTEM,
                        group = entry.group ?: "",
                        groupOverride = entry.groupOverride ?: false,
                        groupWeight = entry.groupWeight ?: com.nuttavern.data.lorebook.LorebookEntry.DEFAULT_WEIGHT,
                        entryScanDepth = entry.entryScanDepth,
                        entryCaseSensitive = entry.entryCaseSensitive,
                        entryMatchWholeWords = entry.matchWholeWords,
                        probability = entry.probability ?: 100,
                        useProbability = entry.useProbability ?: true,
                        excludeRecursion = entry.excludeRecursion ?: false,
                        preventRecursion = entry.preventRecursion ?: false,
                        matchPersonaDescription = entry.matchPersonaDescription ?: false,
                        matchCharacterDescription = entry.matchCharacterDescription ?: false,
                        matchCharacterPersonality = entry.matchCharacterPersonality ?: false,
                        matchCharacterDepthPrompt = entry.matchCharacterDepthPrompt ?: false,
                        matchScenario = entry.matchScenario ?: false,
                        matchCreatorNotes = entry.matchCreatorNotes ?: false,
                        characterFilter = entry.characterFilter,
                        vectorized = entry.vectorized ?: false,
                        automationId = entry.automationId ?: "",
                    )
                },
            )
        }

        val taggedLorebooks = buildList {
            if (characterBook != null) {
                add(com.nuttavern.lorebook.TaggedLorebook(book = characterBook, isCharacterSource = true))
            }
            // 角色绑定的全局世界书(isCharacterSource = true)
            val characterBoundIds = character?.lorebookIds.orEmpty().toSet()
            for (book in allBooks) {
                if (book.id in characterBoundIds) {
                    add(com.nuttavern.lorebook.TaggedLorebook(book = book, isCharacterSource = true))
                }
            }
            // 全局选中的世界书(排除已作为角色绑定加入的)
            for (book in selectedBooks) {
                if (book.id !in characterBoundIds) {
                    add(com.nuttavern.lorebook.TaggedLorebook(book = book, isCharacterSource = false))
                }
            }
        }
        if (taggedLorebooks.isEmpty()) return null

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
        )
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
        )
    }

    private data class PreparedPrompt(
        val systemPrompt: String,
        val messages: List<ChatMessage>,
        val generationParams: com.nuttavern.network.GenerationParams,
    )

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

    private companion object {
        /** 解析 [Preset.extensions] 里的 `regex_scripts` 节点用,与 PromptComposer 同口径。 */
        val REGEX_PRESET_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val REGEX_PRESET_LIST_SERIALIZER =
            kotlinx.serialization.builtins.ListSerializer(com.nuttavern.data.regex.RegexScript.serializer())
    }
}
