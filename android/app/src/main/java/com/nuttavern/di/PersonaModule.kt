package com.nuttavern.di

import com.nuttavern.data.persona.DataStorePersonaRepository
import com.nuttavern.data.persona.PersonaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 用户身份模块的依赖绑定。
 *
 * 把 [PersonaRepository] 绑到 [DataStorePersonaRepository](独立 DataStore 文件持久化)。
 *
 * 单测如需用内存仓库,在测试 module 里替换绑定即可。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PersonaModule {

    @Binds
    @Singleton
    abstract fun bindPersonaRepository(impl: DataStorePersonaRepository): PersonaRepository
}
