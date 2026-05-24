package com.nuttavern.data.repository

import com.nuttavern.data.local.ApiKeyStore
import com.nuttavern.data.local.ProviderDataStore
import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.ModelAbility
import com.nuttavern.data.model.Provider
import com.nuttavern.data.registry.ModelRegistry
import com.nuttavern.network.ChatApiClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provider / Model 的应用层仓库。这里**只**做四件事:
 *
 * 1. 启动一次性把 DataStore 里的 Provider 列表 + 加密 ApiKey 合并到内存 StateFlow;
 * 2. 提供 CRUD;每次写入都会同步 DataStore + ApiKeyStore;
 * 3. 维护"当前选中的 Provider + Model"二元组,跨 Provider 切换时自动选回首条可用模型;
 * 4. 给 UI 暴露一个"用 [ModelRegistry] 推断能力"的便捷方法 [applyInferredCapabilities]。
 *
 * 不做的事:UI 文案、错误码本地化、模型远端拉取的 UI 调度。这些放各自的 ViewModel。
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val providerDataStore: ProviderDataStore,
    private val apiKeyStore: ApiKeyStore,
    private val chatApiClient: ChatApiClient,
) {
    private val initializeMutex = Mutex()
    private var initialized = false

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _selectedProviderId = MutableStateFlow("")
    val selectedProviderId: StateFlow<String> = _selectedProviderId.asStateFlow()

    private val _selectedModelInternalId = MutableStateFlow("")
    val selectedModelInternalId: StateFlow<String> = _selectedModelInternalId.asStateFlow()

    val currentProviderIdSnapshot: String
        get() = _selectedProviderId.value

    suspend fun initialize() {
        initializeMutex.withLock {
            if (initialized) return

            val saved = providerDataStore.getProviders()
            val providers = if (saved.isEmpty() && !providerDataStore.hasSavedProviders()) {
                defaultProviders()
            } else {
                saved
            }
            _providers.value = providers.withSecureApiKeys()

            normalizeSelection(
                preferredProviderId = providerDataStore.getSelectedProviderId(),
                preferredModelInternalId = providerDataStore.getSelectedModelInternalId(),
            )

            providerDataStore.saveProviders(_providers.value.withoutApiKeys())
            providerDataStore.saveSelectedProviderId(_selectedProviderId.value)
            providerDataStore.saveSelectedModelInternalId(_selectedModelInternalId.value)
            initialized = true
        }
    }

    fun currentProvider(): Provider? =
        _providers.value.firstOrNull { it.id == _selectedProviderId.value }

    fun currentModel(): Model? {
        val provider = currentProvider() ?: return null
        return provider.models.firstOrNull { it.id == _selectedModelInternalId.value }
            ?: provider.models.firstOrNull()
    }

    suspend fun addProvider(provider: Provider) {
        val sanitized = provider.sanitize()
        apiKeyStore.saveApiKey(sanitized.id, sanitized.apiKey)
        val updated = _providers.value + sanitized
        _providers.value = updated.withSecureApiKeys()
        persist()
    }

    suspend fun updateProvider(provider: Provider) {
        val sanitized = provider.sanitize()
        // 不再用"非空才写"的合并逻辑:用户清空 apiKey 也要落到 ApiKeyStore,
        // 否则 withSecureApiKeys() 会把旧值合回来,看起来像"清空又自动填充"。
        apiKeyStore.saveApiKey(sanitized.id, sanitized.apiKey)
        val updated = _providers.value.map { if (it.id == sanitized.id) sanitized else it }
        _providers.value = updated.withSecureApiKeys()
        normalizeSelection(_selectedProviderId.value, _selectedModelInternalId.value)
        persist()
    }

    suspend fun deleteProvider(providerId: String) {
        apiKeyStore.deleteApiKey(providerId)
        _providers.value = _providers.value.filter { it.id != providerId }
        normalizeSelection(_selectedProviderId.value, _selectedModelInternalId.value)
        persist()
    }

    suspend fun reorderProviders(orderedIds: List<String>) {
        val map = _providers.value.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { map[it] }.mapIndexed { index, provider ->
            provider.withOrder(index)
        }
        if (reordered.size != _providers.value.size) return
        _providers.value = reordered
        persist()
    }

    suspend fun addModel(providerId: String, model: Model) {
        val provider = _providers.value.firstOrNull { it.id == providerId } ?: return
        val sanitized = sanitizeModel(model)
        val updated = provider.withModels(provider.models + sanitized)
        _providers.value = _providers.value.map { if (it.id == providerId) updated else it }
        persist()
    }

    /**
     * 批量追加模型,过滤掉已存在的 modelId,避免远端拉模型时重复入库。
     * 返回真正写入的条目数,UI 层用来给 Snackbar 显示"添加了 N 个模型"。
     */
    suspend fun addModels(providerId: String, models: List<Model>): Int {
        val provider = _providers.value.firstOrNull { it.id == providerId } ?: return 0
        val existingModelIds = provider.models.map { it.modelId }.toSet()
        val sanitizedNewModels = models
            .map { sanitizeModel(it) }
            .filter { it.modelId.isNotBlank() && it.modelId !in existingModelIds }
            .distinctBy { it.modelId }
        if (sanitizedNewModels.isEmpty()) return 0

        val updated = provider.withModels(provider.models + sanitizedNewModels)
        _providers.value = _providers.value.map { if (it.id == providerId) updated else it }
        persist()
        return sanitizedNewModels.size
    }

    suspend fun updateModel(providerId: String, model: Model) {
        val provider = _providers.value.firstOrNull { it.id == providerId } ?: return
        val sanitized = sanitizeModel(model)
        val updated = provider.withModels(
            provider.models.map { if (it.id == sanitized.id) sanitized else it },
        )
        _providers.value = _providers.value.map { if (it.id == providerId) updated else it }
        persist()
    }

    suspend fun deleteModel(providerId: String, modelInternalId: String) {
        val provider = _providers.value.firstOrNull { it.id == providerId } ?: return
        val updated = provider.withModels(provider.models.filter { it.id != modelInternalId })
        _providers.value = _providers.value.map { if (it.id == providerId) updated else it }
        normalizeSelection(_selectedProviderId.value, _selectedModelInternalId.value)
        persist()
    }

    suspend fun selectProvider(providerId: String) {
        val provider = _providers.value.firstOrNull { it.id == providerId && it.enabled } ?: return
        val firstModel = provider.models.firstOrNull()?.id.orEmpty()
        _selectedProviderId.value = provider.id
        _selectedModelInternalId.value = firstModel
        providerDataStore.saveSelectedProviderId(provider.id)
        providerDataStore.saveSelectedModelInternalId(firstModel)
    }

    suspend fun selectProviderAndModel(providerId: String, modelInternalId: String) {
        val provider = _providers.value.firstOrNull { it.id == providerId && it.enabled } ?: return
        val resolved = provider.models.firstOrNull { it.id == modelInternalId }
            ?: provider.models.firstOrNull()
            ?: return
        _selectedProviderId.value = provider.id
        _selectedModelInternalId.value = resolved.id
        providerDataStore.saveSelectedProviderId(provider.id)
        providerDataStore.saveSelectedModelInternalId(resolved.id)
    }

    suspend fun selectModel(modelInternalId: String) {
        val provider = currentProvider() ?: return
        val resolved = provider.models.firstOrNull { it.id == modelInternalId } ?: return
        _selectedModelInternalId.value = resolved.id
        providerDataStore.saveSelectedModelInternalId(resolved.id)
    }

    /**
     * 用 [ModelRegistry] 推断 [Model] 的输入 / 输出模态和能力,返回新的 Model。
     * 不会改 [Model.id] / [Model.modelId] / [Model.displayName] 等用户字段。
     */
    fun applyInferredCapabilities(model: Model): Model {
        val inferred = ModelRegistry.inferAll(model.modelId)
        return model.copy(
            inputModalities = inferred.inputModalities,
            outputModalities = inferred.outputModalities,
            abilities = inferred.abilities,
        )
    }

    /**
     * 远端拉取模型列表。三协议都支持:
     * - OpenAI: GET /v1/models,响应 `{"data":[{"id":"..."}]}`;
     * - Google: GET /v1beta/models?key=...,响应 `{"models":[{"name":"models/...","supportedGenerationMethods":[...]}]}`;
     * - Claude: GET /v1/models(Anthropic 自 2024 年起开放),响应同 OpenAI 形态。
     */
    suspend fun fetchRemoteModels(providerId: String): Result<List<String>> {
        val provider = _providers.value.firstOrNull { it.id == providerId }
            ?: return Result.failure(IllegalArgumentException("提供商不存在"))

        if (provider.apiKey.isBlank()) {
            return Result.failure(IllegalStateException("请先填写 API Key"))
        }
        if (provider.baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("请先填写 Base URL"))
        }

        return chatApiClient.fetchModels(provider).map { models ->
            models.map { it.trim().take(120) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
    }

    private fun normalizeSelection(preferredProviderId: String, preferredModelInternalId: String) {
        val provider = _providers.value.firstOrNull { it.id == preferredProviderId && it.enabled }
            ?: _providers.value.firstOrNull { it.enabled }

        if (provider == null) {
            _selectedProviderId.value = ""
            _selectedModelInternalId.value = ""
            return
        }

        _selectedProviderId.value = provider.id
        _selectedModelInternalId.value = provider.models.firstOrNull { it.id == preferredModelInternalId }?.id
            ?: provider.models.firstOrNull()?.id
            ?: ""
    }

    private suspend fun persist() {
        providerDataStore.saveProviders(_providers.value.withoutApiKeys())
        providerDataStore.saveSelectedProviderId(_selectedProviderId.value)
        providerDataStore.saveSelectedModelInternalId(_selectedModelInternalId.value)
    }

    private fun List<Provider>.withSecureApiKeys(): List<Provider> = map {
        it.withApiKey(apiKeyStore.getApiKey(it.id))
    }

    private fun List<Provider>.withoutApiKeys(): List<Provider> = map { it.withApiKey("") }

    private fun Provider.sanitize(): Provider {
        val safeBaseUrl = sanitizeBaseUrl(baseUrl)
        // 名称只裁剪和去首尾空,保留空字符串原样;UI 层负责用 placeholder 给视觉兜底,
        // 不在仓库强行补"OpenAI / Google / Claude",避免用户清空想重输时被瞬间填回。
        val safeName = name.trim().take(40)
        val sanitizedModels = models.map { sanitizeModel(it) }

        return when (this) {
            is Provider.OpenAI -> copy(
                name = safeName,
                baseUrl = safeBaseUrl,
                chatCompletionsPath = sanitizeApiPath(chatCompletionsPath),
                models = sanitizedModels,
            )
            is Provider.Google -> copy(
                name = safeName,
                baseUrl = safeBaseUrl,
                models = sanitizedModels,
            )
            is Provider.Claude -> copy(
                name = safeName,
                baseUrl = safeBaseUrl,
                models = sanitizedModels,
            )
        }
    }

    private fun sanitizeModel(model: Model): Model {
        val modelId = model.modelId.trim().take(180)
        val displayName = model.displayName.trim().take(120)
        val inputModalities = model.inputModalities.distinct().ifEmpty { listOf(Modality.TEXT) }
        val outputModalities = model.outputModalities.distinct().ifEmpty { listOf(Modality.TEXT) }
        val abilities = model.abilities.distinct().take(ModelAbility.entries.size)
        return model.copy(
            modelId = modelId,
            displayName = displayName,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            abilities = abilities,
        )
    }

    private fun sanitizeBaseUrl(value: String): String {
        // 仓库这一层不做"合法性回退到空"。用户编辑过程里 `http` / `https://` 这种半成品
        // 都是合法中间态,擦掉会让输入框来回闪。真正的 URL 校验留给"测试连接"和发请求时,
        // 这里只做长度截断、去首尾空和去末尾斜杠。
        return value.trim().take(2_048).removeSuffix("/")
    }

    private fun sanitizeApiPath(path: String): String {
        val normalized = path.trim().take(512)
        return when {
            normalized.isBlank() -> ""
            normalized.startsWith("/") -> normalized
            else -> "/$normalized"
        }
    }

    /**
     * 全新装机或反序列化失败时返回的初始 Provider 列表。
     *
     * 取舍:不再预置三条空 Provider(OpenAI / Google / Claude)。预置项在产品上有两个问题:
     * 1. 用户进列表只看见三条空架子,需要先点进去填写,体验绕;
     * 2. "默认 Provider"会污染用户对"自己有几个 Provider"的判断,搜索 / 排序 / 启用状态都被占用。
     *
     * 现在启动后是空列表,UI 直接进入"还没有提供商"空态,用户从右上 + 自己建,协议在新建后
     * 的详情页里通过药丸切换。
     */
    private fun defaultProviders(): List<Provider> = emptyList()
}
