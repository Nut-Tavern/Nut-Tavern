package com.nuttavern.data.preset

import kotlinx.coroutines.flow.Flow

/**
 * 预设仓库。生产实现走 [DataStorePresetRepository](独立 DataStore 文件,kotlinx.serialization JSON)。
 * 测试如需替换,在测试 module 里覆盖 [com.nuttavern.di.PresetModule.bindPresetRepository] 即可。
 *
 * # 契约
 *
 * - [presets] 是用户当前所有预设(含仓库内置默认预设)。空仓库返回 `listOf(Preset.default())`;
 *   仓库会在首次访问 / 删完所有预设后自动塞回默认预设,保证拼接管线永远有可用预设。
 * - [defaultPresetId] 是用户标记为默认的预设 id。空仓库返回 [Preset.DEFAULT_PRESET_ID];
 *   消费方拿到 null **不可能发生**(仓库恢复机制保证非空)。
 * - [upsert] 按 id 创建或覆盖。空 name 由 UI 校验,仓库不拦。
 * - [delete] 删除指定预设;若删的是当前默认,会自动选下一条作为默认。删完最后一份非默认预设时
 *   保留默认预设;若试图删默认预设本身,仓库无视。
 * - [setDefault] 接受任意已知 id;未知 id 抛 [IllegalArgumentException]。
 * - [reorder] 按指定 id 顺序重排;未出现的 id 保留在末尾。
 */
interface PresetRepository {
    val presets: Flow<List<Preset>>
    val defaultPresetId: Flow<String>

    suspend fun upsert(preset: Preset)
    suspend fun delete(id: String)
    suspend fun setDefault(id: String)
    suspend fun reorder(orderedIds: List<String>)
}
