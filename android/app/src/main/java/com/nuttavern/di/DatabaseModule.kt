package com.nuttavern.di

import android.content.Context
import androidx.room.Room
import com.nuttavern.data.local.NutTavernDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NutTavernDatabase {
        return Room.databaseBuilder(
            context,
            NutTavernDatabase::class.java,
            "nuttavern"
        )
            .addMigrations(
                NutTavernDatabase.MIGRATION_1_2,
                NutTavernDatabase.MIGRATION_2_3,
                NutTavernDatabase.MIGRATION_3_4,
                NutTavernDatabase.MIGRATION_4_5,
                NutTavernDatabase.MIGRATION_5_6,
                NutTavernDatabase.MIGRATION_6_7,
                NutTavernDatabase.MIGRATION_7_8,
                NutTavernDatabase.MIGRATION_8_9,
                NutTavernDatabase.MIGRATION_9_10,
                NutTavernDatabase.MIGRATION_10_11,
                NutTavernDatabase.MIGRATION_11_12,
                NutTavernDatabase.MIGRATION_12_13,
                NutTavernDatabase.MIGRATION_13_14,
                NutTavernDatabase.MIGRATION_14_15,
                NutTavernDatabase.MIGRATION_15_16,
                NutTavernDatabase.MIGRATION_16_17,
                NutTavernDatabase.MIGRATION_17_18,
                NutTavernDatabase.MIGRATION_18_19,
            )
            .build()
    }
}
