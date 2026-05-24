package com.nuttavern.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuttavern.data.model.Model
import com.nuttavern.data.model.Provider
import com.nuttavern.data.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 提供商列表 / 详情 / 编辑模型对话框共享的 ViewModel。
 *
 * 选择共享的理由:三处都消费同一份 `providers` 状态,且写操作链路(添加 / 更新 / 删除)
 * 必须落到同一个仓库,分多个 VM 反而造成同步问题。读取按 [providerById] / [modelById]
 * 从同一份 [providers] 派生即可。
 */
@HiltViewModel
class ProviderSettingsViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    val providers: StateFlow<List<Provider>> = providerRepository.providers

    init {
        // initialize 是幂等的,这里再调一次保证设置页第一次进来就能拿到默认 Provider 列表。
        viewModelScope.launch { providerRepository.initialize() }
    }

    fun providerById(id: String): Provider? = providers.value.firstOrNull { it.id == id }

    fun modelById(providerId: String, modelInternalId: String): Model? =
        providerById(providerId)?.models?.firstOrNull { it.id == modelInternalId }

    fun addProvider(provider: Provider) {
        viewModelScope.launch { providerRepository.addProvider(provider) }
    }

    fun updateProvider(provider: Provider) {
        viewModelScope.launch { providerRepository.updateProvider(provider) }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch { providerRepository.deleteProvider(providerId) }
    }

    fun reorderProviders(orderedIds: List<String>) {
        viewModelScope.launch { providerRepository.reorderProviders(orderedIds) }
    }

    fun toggleProviderEnabled(providerId: String) {
        val provider = providerById(providerId) ?: return
        updateProvider(provider.withEnabled(!provider.enabled))
    }

    fun addModel(providerId: String, model: Model) {
        viewModelScope.launch { providerRepository.addModel(providerId, model) }
    }

    fun updateModel(providerId: String, model: Model) {
        viewModelScope.launch { providerRepository.updateModel(providerId, model) }
    }

    fun deleteModel(providerId: String, modelInternalId: String) {
        viewModelScope.launch { providerRepository.deleteModel(providerId, modelInternalId) }
    }

    /**
     * 按 [modelId](真实 API id,不是内部 UUID)删除一条模型。给"管理模型"抽屉用:
     * 用户点 × 时只知道 modelId,不需要先查 internal id。
     */
    fun deleteModelByModelId(providerId: String, modelId: String) {
        val provider = providerById(providerId) ?: return
        val target = provider.models.firstOrNull { it.modelId == modelId } ?: return
        deleteModel(providerId, target.id)
    }

    /**
     * 按 modelId 即时添加一条模型(走 ModelRegistry 推断能力)。给"管理模型"抽屉用,
     * 与 [addModelsFromIds] 区别:这里是单条同步入口,不需要 suspend。
     */
    fun addModelByModelId(providerId: String, modelId: String) {
        val newModel = newModel(modelId)
        viewModelScope.launch { providerRepository.addModel(providerId, newModel) }
    }

    /**
     * 远端拉取该 Provider 可用模型 id。封装在 VM 里:
     * - 不直接暴露仓库,UI 只关心结果;
     * - Provider.Claude 在仓库层就会返回 failure(API 不支持 list),UI 据此提示。
     */
    suspend fun fetchRemoteModelIds(providerId: String): Result<List<String>> =
        providerRepository.fetchRemoteModels(providerId)

    /**
     * 用一组 modelId 批量加到 Provider。modelId 现场过 ModelRegistry 推断能力,
     * 返回真正写入的条目数(已存在的会被仓库去重)。
     */
    suspend fun addModelsFromIds(providerId: String, modelIds: List<String>): Int {
        val newModels = modelIds.map { newModel(it) }
        return providerRepository.addModels(providerId, newModels)
    }

    /**
     * "测试连接":借 fetchModels 路径做最小可用性验证。三种协议统一走 list models:
     * - OpenAI:GET /v1/models
     * - Google:GET /v1beta/models
     * - Claude:GET /v1/models(Anthropic 已支持,响应同 OpenAI 的 {data:[{id}]})
     */
    suspend fun testConnection(providerId: String): Result<String> {
        return providerRepository.fetchRemoteModels(providerId)
            .map { models -> "连接成功,远端可用模型 ${models.size} 个" }
    }

    /** 用 ModelRegistry 重算能力字段,返回新的 Model;不写入仓库,保存交给调用方。 */
    fun inferCapabilities(model: Model): Model = providerRepository.applyInferredCapabilities(model)

    fun newOpenAiProvider(): Provider = Provider.OpenAI(
        id = UUID.randomUUID().toString(),
        order = providers.value.size,
    )

    fun newGoogleProvider(): Provider = Provider.Google(
        id = UUID.randomUUID().toString(),
        order = providers.value.size,
    )

    fun newClaudeProvider(): Provider = Provider.Claude(
        id = UUID.randomUUID().toString(),
        order = providers.value.size,
    )

    /**
     * 列表页"+"使用的统一新建入口。默认按 OpenAI 协议建,后续在详情页用药丸切换。
     * 这条与"协议在新建时确定,要换协议就删了重建"的旧约束相反:
     * 现在通过 [Provider.withProtocol] 在详情页内可切换协议,新建时不再让用户先选协议。
     */
    fun newProvider(): Provider = newOpenAiProvider()

    fun newModel(modelId: String): Model {
        val trimmed = modelId.trim()
        val base = Model(
            id = UUID.randomUUID().toString(),
            modelId = trimmed,
            displayName = trimmed,
        )
        return providerRepository.applyInferredCapabilities(base)
    }
}
