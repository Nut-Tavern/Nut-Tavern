package com.nuttavern.data.lorebook

import com.nuttavern.data.character.Character
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.lorebook.TaggedLorebook
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 解析「当前会话已启用世界书集合」。
 *
 * 这是运行时世界书激活与世界书编辑工具**共用**的来源口径:三处来源合并去重,顺序对齐
 * `ChatViewModel.runLorebookActivation`(persona > 角色 > 当前会话):
 *
 * | 来源 | 字段 | 说明 |
 * |---|---|---|
 * | persona | [UserPersona.lorebookId] | 当前会话身份绑定(单本,优先级最高) |
 * | 角色 | [Character.characterLorebookId] + [Character.lorebookIds] | 角色世界书(单本)+ 辅助世界书(多本) |
 * | 当前会话 | `ConversationSummary.enabledLorebookIdsJson` | 用户在右侧栏世界书 Picker 勾选 |
 *
 * 去重规则:persona 书若已在角色来源或当前会话选中里出现则不重复加入;当前会话书若已是角色来源或 persona
 * 来源则跳过。保证同一本书只出现一次,与激活引擎口径完全一致。
 *
 * 世界书编辑工具的作用范围硬边界由此集合界定:工具只能在集合内的世界书上操作条目,集合外一律拒绝。
 */
@Singleton
class SessionLorebookResolver @Inject constructor(
    private val lorebookRepository: LorebookRepository,
) {
    /** 读取仓库当前快照,解析出本会话已启用世界书(带来源标记)。 */
    suspend fun resolve(
        selectedLorebookIds: List<String>,
        character: Character?,
        persona: UserPersona?,
    ): List<TaggedLorebook> {
        val allBooks = lorebookRepository.lorebooks.first()
        return resolveSessionLorebooks(
            allBooks = allBooks,
            selectedLorebookIds = selectedLorebookIds,
            character = character,
            persona = persona,
        )
    }
}

/**
 * 纯函数:在给定世界书全集与三来源选择下,算出本会话已启用世界书(带来源标记,去重)。
 *
 * 抽成顶层纯函数便于单测,不依赖 repository / 协程。
 */
internal fun resolveSessionLorebooks(
    allBooks: List<Lorebook>,
    selectedLorebookIds: List<String>,
    character: Character?,
    persona: UserPersona?,
): List<TaggedLorebook> {
    val selectedLorebookIdSet = selectedLorebookIds.toSet()
    val characterBoundIds = buildSet {
        character?.characterLorebookId?.let { add(it) }
        addAll(character?.lorebookIds.orEmpty())
    }
    val personaLorebookId = persona?.lorebookId

    return buildList {
        // persona 来源(优先级最高):仅当未作为当前会话选中或角色来源出现时加入,避免重复。
        if (personaLorebookId != null
            && personaLorebookId !in selectedLorebookIdSet
            && personaLorebookId !in characterBoundIds
        ) {
            allBooks.find { it.id == personaLorebookId }?.let { personaBook ->
                add(
                    TaggedLorebook(
                        book = personaBook,
                        isCharacterSource = false,
                        sourceKey = "persona:$personaLorebookId",
                    ),
                )
            }
        }
        // 角色来源(角色世界书 + 辅助世界书)。
        for (book in allBooks) {
            if (book.id in characterBoundIds) {
                add(TaggedLorebook(book = book, isCharacterSource = true, sourceKey = book.id))
            }
        }
        // 当前会话选中(排除已作为角色来源或 persona 来源加入的)。
        for (book in allBooks) {
            if (book.id in selectedLorebookIdSet
                && book.id !in characterBoundIds
                && book.id != personaLorebookId
            ) {
                add(TaggedLorebook(book = book, isCharacterSource = false, sourceKey = book.id))
            }
        }
    }
}
