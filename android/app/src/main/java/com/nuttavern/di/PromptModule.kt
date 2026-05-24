package com.nuttavern.di

import com.nuttavern.prompt.PlaceholderResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 拼接管线相关 Hilt 绑定。
 *
 * [PlaceholderResolver] 默认构造已经覆盖生产语义(系统时区 + 系统 locale + Random.Default),
 * 这里只是把它登记成可注入的 Singleton。测试要替换 clock / locale / random 时,
 * 在测试 module 里覆盖 [providePlaceholderResolver] 即可。
 */
@Module
@InstallIn(SingletonComponent::class)
object PromptModule {

    @Provides
    @Singleton
    fun providePlaceholderResolver(): PlaceholderResolver = PlaceholderResolver()
}
