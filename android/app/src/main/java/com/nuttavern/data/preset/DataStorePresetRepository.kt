package com.nuttavern.data.preset

import com.nuttavern.data.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 持久化版本的 [PresetRepository]。数据落盘走 [PresetDataStore]。
 *
 * 列表顺序通过单独的 `presetOrder` 数组维护,与 PersonaRepository / CharacterRepository 同模式:
 * 顺序变更与内容变更职责分离,便于以后单独迁移。
 *
 * # 默认预设兜底
 *
 * 仓库**永远保证至少存在一份预设**(默认预设 [Preset.DEFAULT_PRESET_ID]):
 *
 * - 空仓库读取时,在 Flow 层补一份默认预设(不写入存储);
 * - [upsert] / [delete] / [reorder] 后,如果存储里没有默认预设,自动塞回去;
 * - [setDefault] 拒绝指向不存在的 id,但允许指向默认预设;
 * - 删默认预设的请求会被无视(默认预设不可被用户彻底清除,允许用户改其内容)。
 *
 * 这条兜底让拼接管线 / ChatViewModel 不需要处理"没有任何预设"的边界情况。
 *
 * # 不做的事
 *
 * - 不做并发控制(单用户单 app 场景下 [PresetDataStore.mutate] 内部已经走 DataStore 串行 actor);
 * - 不做"默认变化时改写老会话":每个会话的预设在创建时锁定到 `conversations.presetId`,
 *   后续抽屉切预设直接覆盖会话字段。改默认预设只影响**新建**会话的初值。
 */
@Singleton
class DataStorePresetRepository @Inject constructor(
    private val dataStore: PresetDataStore,
    private val conversationRepository: ConversationRepository,
) : PresetRepository {

    override val presets: Flow<List<Preset>> = combine(
        dataStore.presetsFlow,
        dataStore.orderFlow,
    ) { stored, order ->
        applyOrder(ensureDefaultPresent(stored), order)
    }.distinctUntilChanged()

    override val defaultPresetId: Flow<String> = combine(
        dataStore.presetsFlow,
        dataStore.defaultPresetIdFlow,
    ) { stored, savedDefault ->
        val resolved = savedDefault?.takeIf { id -> stored.any { it.id == id } }
        resolved ?: Preset.DEFAULT_PRESET_ID
    }.distinctUntilChanged()

    override suspend fun upsert(preset: Preset) {
        dataStore.mutate { snapshot ->
            val seeded = ensureDefaultPresentMutating(snapshot)
            val existingIndex = seeded.presets.indexOfFirst { it.id == preset.id }
            val nextPresets = if (existingIndex >= 0) {
                seeded.presets.toMutableList().apply { set(existingIndex, preset) }
            } else {
                seeded.presets + preset
            }
            val nextOrder = if (existingIndex < 0 && preset.id !in seeded.orderedIds) {
                seeded.orderedIds + preset.id
            } else {
                seeded.orderedIds
            }
            seeded.copy(presets = nextPresets, orderedIds = nextOrder)
        }
    }

    override suspend fun delete(id: String) {
        if (id == Preset.DEFAULT_PRESET_ID) return
        dataStore.mutate { snapshot ->
            val nextDefault = if (snapshot.defaultPresetId == id) Preset.DEFAULT_PRESET_ID else snapshot.defaultPresetId
            val cleaned = snapshot.copy(
                presets = snapshot.presets.filterNot { it.id == id },
                orderedIds = snapshot.orderedIds.filterNot { it == id },
                defaultPresetId = nextDefault,
            )
            ensureDefaultPresentMutating(cleaned)
        }
        // 联动清理:把绑定该预设的会话 presetId 置 null,加载时退化为全局默认预设。
        conversationRepository.clearPresetIdFromAllConversations(id)
    }

    override suspend fun setDefault(id: String) {
        dataStore.mutate { snapshot ->
            val seeded = ensureDefaultPresentMutating(snapshot)
            require(seeded.presets.any { it.id == id }) { "未知 preset id: $id" }
            seeded.copy(defaultPresetId = id)
        }
    }

    override suspend fun reorder(orderedIds: List<String>) {
        dataStore.mutate { snapshot ->
            val seeded = ensureDefaultPresentMutating(snapshot)
            // 入参里没出现的预设保留在末尾,避免静默丢失。
            val knownIds = seeded.presets.map { it.id }.toSet()
            val cleanedHead = orderedIds.filter { it in knownIds }
            val tail = (knownIds - cleanedHead.toSet())
            seeded.copy(orderedIds = cleanedHead + tail)
        }
    }

    /** Flow 层补默认预设(不写入存储,只让消费方至少看到一份)。 */
    private fun ensureDefaultPresent(presets: List<Preset>): List<Preset> {
        if (presets.any { it.id == Preset.DEFAULT_PRESET_ID }) return presets
        return listOf(Preset.default()) + presets
    }

    /** mutate 路径里同时把默认预设写回 snapshot,保证下次读到的存储数据已经包含默认预设。 */
    private fun ensureDefaultPresentMutating(snapshot: PresetDataStore.Snapshot): PresetDataStore.Snapshot {
        if (snapshot.presets.any { it.id == Preset.DEFAULT_PRESET_ID }) return snapshot
        val seeded = listOf(Preset.default()) + snapshot.presets
        val seededOrder = if (Preset.DEFAULT_PRESET_ID in snapshot.orderedIds) {
            snapshot.orderedIds
        } else {
            listOf(Preset.DEFAULT_PRESET_ID) + snapshot.orderedIds
        }
        return snapshot.copy(presets = seeded, orderedIds = seededOrder)
    }

    private fun applyOrder(presets: List<Preset>, order: List<String>): List<Preset> {
        val byId = presets.associateBy { it.id }
        val ordered = order.mapNotNull(byId::get)
        val tail = presets.filter { it.id !in order }
        return ordered + tail
    }
}
