package com.nuttavern.data.lorebook

import com.nuttavern.data.character.Character
import com.nuttavern.data.persona.UserPersona
import com.nuttavern.lorebook.TaggedLorebook
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLorebookResolverTest {

    private fun book(id: String, name: String = id) = Lorebook(id = id, name = name)

    @Test
    fun emptySelection_producesEmpty() {
        val result = resolveSessionLorebooks(
            allBooks = listOf(book("a"), book("b")),
            globalSelectedIds = emptyList(),
            character = null,
            persona = null,
        )
        assertEquals(emptyList<TaggedLorebook>(), result)
    }

    @Test
    fun globalSelected_includedInAllBooksOrder() {
        val result = resolveSessionLorebooks(
            allBooks = listOf(book("a"), book("b"), book("c")),
            globalSelectedIds = listOf("c", "a"),
            character = null,
            persona = null,
        )
        // 顺序跟随 allBooks(a 在 c 前),不跟随 globalSelectedIds 的顺序。
        assertEquals(listOf("a", "c"), result.map { it.book.id })
        assertEquals(listOf(false, false), result.map { it.isCharacterSource })
    }

    @Test
    fun characterSources_primaryAndAdditional_markedAsCharacterSource() {
        val character = Character(characterLorebookId = "primary", lorebookIds = listOf("extra"))
        val result = resolveSessionLorebooks(
            allBooks = listOf(book("primary"), book("extra"), book("global")),
            globalSelectedIds = listOf("global"),
            character = character,
            persona = null,
        )
        assertEquals(setOf("primary", "extra", "global"), result.map { it.book.id }.toSet())
        assertEquals(true, result.first { it.book.id == "primary" }.isCharacterSource)
        assertEquals(true, result.first { it.book.id == "extra" }.isCharacterSource)
        assertEquals(false, result.first { it.book.id == "global" }.isCharacterSource)
    }

    @Test
    fun personaBook_dedupedAgainstCharacterAndGlobal() {
        // persona 绑定的书已作为角色来源出现 → 不重复加入。
        val character = Character(characterLorebookId = "shared")
        val persona = UserPersona(lorebookId = "shared")
        val result = resolveSessionLorebooks(
            allBooks = listOf(book("shared")),
            globalSelectedIds = emptyList(),
            character = character,
            persona = persona,
        )
        assertEquals(listOf("shared"), result.map { it.book.id })
        // 只作为角色来源出现一次。
        assertEquals(1, result.count { it.book.id == "shared" })
        assertEquals(true, result.single().isCharacterSource)
    }

    @Test
    fun personaBook_uniqueIsAddedWithPersonaSourceKey() {
        val persona = UserPersona(lorebookId = "p")
        val result = resolveSessionLorebooks(
            allBooks = listOf(book("p"), book("g")),
            globalSelectedIds = listOf("g"),
            character = null,
            persona = persona,
        )
        // persona 书排在最前(优先级最高),source key 带 persona: 前缀。
        assertEquals(listOf("p", "g"), result.map { it.book.id })
        assertEquals("persona:p", result.first().sourceKey)
        assertEquals(false, result.first().isCharacterSource)
    }

    @Test
    fun globalBook_alsoCharacterBound_skippedFromGlobalPass() {
        // 一本书既被角色绑定又被全局选中 → 只作为角色来源出现一次,不重复。
        val character = Character(characterLorebookId = "x")
        val result = resolveSessionLorebooks(
            allBooks = listOf(book("x")),
            globalSelectedIds = listOf("x"),
            character = character,
            persona = null,
        )
        assertEquals(1, result.size)
        assertEquals(true, result.single().isCharacterSource)
    }
}
