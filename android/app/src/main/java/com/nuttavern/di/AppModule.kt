package com.nuttavern.di

import com.nuttavern.data.local.NutTavernDatabase
import com.nuttavern.data.local.dao.CharacterDao
import com.nuttavern.data.local.dao.ConversationDao
import com.nuttavern.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideConversationDao(database: NutTavernDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: NutTavernDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideCharacterDao(database: NutTavernDatabase): CharacterDao {
        return database.characterDao()
    }
}
