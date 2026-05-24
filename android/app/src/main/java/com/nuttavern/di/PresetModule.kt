package com.nuttavern.di

import com.nuttavern.data.preset.DataStorePresetRepository
import com.nuttavern.data.preset.PresetRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 预设模块的依赖绑定。
 *
 * 把 [PresetRepository] 绑到 [DataStorePresetRepository](独立 DataStore 文件持久化,
 * `nuttavern_presets.preferences_pb`)。
 *
 * 单测如需用内存仓库,在测试 module 里替换绑定即可。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PresetModule {

    @Binds
    @Singleton
    abstract fun bindPresetRepository(impl: DataStorePresetRepository): PresetRepository
}
