package com.nuttavern.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nuttavern.data.local.dao.CharacterDao
import com.nuttavern.data.local.dao.ConversationDao
import com.nuttavern.data.local.dao.MessageDao
import com.nuttavern.data.local.entity.CharacterEntity
import com.nuttavern.data.local.entity.ConversationEntity
import com.nuttavern.data.local.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, CharacterEntity::class],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NutTavernDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun characterDao(): CharacterDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "messages", columnName = "reasoningContent")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "messages", columnName = "reasoningDurationMillis")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN reasoningDurationMillis INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `characters` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `personality` TEXT NOT NULL,
                        `scenario` TEXT NOT NULL,
                        `firstMessage` TEXT NOT NULL,
                        `messageExample` TEXT NOT NULL,
                        `systemPrompt` TEXT NOT NULL,
                        `postHistoryInstructions` TEXT NOT NULL,
                        `alternateGreetings` TEXT NOT NULL,
                        `creator` TEXT NOT NULL,
                        `characterVersion` TEXT NOT NULL,
                        `creatorNotes` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `extensionsJson` TEXT NOT NULL,
                        `characterBookJson` TEXT,
                        `regexScriptsJson` TEXT NOT NULL,
                        `avatarPath` TEXT,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 角色卡加 displayOrder 字段:用户拖动排序结果。
         *
         * 默认值用 createdAt 兜底,这样升级前已有的角色按创建时间稳定排序;
         * 后续 reorder 重写为按 100 步长的整数,便于在中间插入。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "characters", columnName = "displayOrder")) {
                    db.execSQL("ALTER TABLE characters ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0")
                    // 已有角色按 createdAt 升序排,初始顺序不抖。
                    db.execSQL("UPDATE characters SET displayOrder = createdAt")
                }
            }
        }

        /**
         * 会话加 characterId 字段:绑定角色卡。
         *
         * 老会话默认 NULL = 未绑定,继续走"默认助手 + assistant.systemPrompt"路径,
         * 与升级前行为一致。新会话由 ChatViewModel 在创建时写入当前选中的角色 id。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "conversations", columnName = "characterId")) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN characterId TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * 会话加 personaId 字段:锁定用户身份。
         *
         * 老会话默认 NULL = "无身份",拼接管线跳过用户身份块,与升级前"会话级身份只在内存"
         * 关 app 后丢失的旧行为对齐。新会话由 ChatViewModel 在创建时写入当前默认身份 id。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "conversations", columnName = "personaId")) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN personaId TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * 会话加 presetId 字段:锁定预设。
         *
         * 老会话默认 NULL = "未锁定预设",拼接管线退化为使用全局默认预设。新会话由 ChatViewModel
         * 在创建时写入当前默认预设 id。与 personaId / characterId 同处理:默认预设变化不反向
         * 修改老会话,删预设也不自动清空字段。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "conversations", columnName = "presetId")) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN presetId TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * 角色卡加 verbosity 字段:回复长度档位。
         *
         * 空字符串 = auto = 不发送字段,与升级前"无 verbosity"行为完全一致。
         * 字段语义详见 [com.nuttavern.data.character.Character.verbosity]。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn(tableName = "characters", columnName = "verbosity")) {
                    db.execSQL("ALTER TABLE characters ADD COLUMN verbosity TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /**
         * 会话加正则引用字段:enabledRegexGroupIds / enabledOrphanRegexIds。
         *
         * 骨架期没有真实用户数据,直接抹掉 conversations 和 messages 重建,避免写复杂迁移 SQL。
         * 新 schema 加两个 TEXT 列(JSON 数组),默认 NULL = 会话不引用任何正则。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `messages`")
                db.execSQL("DROP TABLE IF EXISTS `conversations`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `lastMessageTime` INTEGER NOT NULL,
                        `assistantId` TEXT NOT NULL,
                        `groupLabel` TEXT,
                        `pinned` INTEGER NOT NULL DEFAULT 0,
                        `archived` INTEGER NOT NULL DEFAULT 0,
                        `characterId` TEXT DEFAULT NULL,
                        `personaId` TEXT DEFAULT NULL,
                        `presetId` TEXT DEFAULT NULL,
                        `enabledRegexGroupIds` TEXT DEFAULT NULL,
                        `enabledOrphanRegexIds` TEXT DEFAULT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `reasoningContent` TEXT NOT NULL DEFAULT '',
                        `reasoningDurationMillis` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("characters", "lorebookIdsJson")) {
                    db.execSQL("ALTER TABLE `characters` ADD COLUMN `lorebookIdsJson` TEXT NOT NULL DEFAULT '[]'")
                }
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("conversations", "lorebookTimedEffectsJson")) {
                    db.execSQL("ALTER TABLE `conversations` ADD COLUMN `lorebookTimedEffectsJson` TEXT NOT NULL DEFAULT '{}'")
                }
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(
            tableName: String,
            columnName: String,
        ): Boolean {
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false

                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
