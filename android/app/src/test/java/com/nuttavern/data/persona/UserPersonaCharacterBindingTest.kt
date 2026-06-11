package com.nuttavern.data.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserPersonaCharacterBindingTest {

    @Test
    fun boundCharacter_returnsFirstMatchingRealPersona() {
        val personas = listOf(
            persona(id = "persona-a", characterConnections = listOf("character-1")),
            persona(id = "persona-b", characterConnections = listOf("character-1")),
        )

        val selectedPersonaId = findPersonaIdBoundToCharacter(personas, "character-1")

        assertEquals("persona-a", selectedPersonaId)
    }

    @Test
    fun nonePersona_isIgnoredEvenWhenItContainsCharacterConnection() {
        val personas = listOf(
            UserPersona.None.copy(characterConnections = listOf("character-1")),
            persona(id = "persona-a", characterConnections = listOf("character-1")),
        )

        val selectedPersonaId = findPersonaIdBoundToCharacter(personas, "character-1")

        assertEquals("persona-a", selectedPersonaId)
    }

    @Test
    fun unboundCharacter_returnsNull() {
        val personas = listOf(
            persona(id = "persona-a", characterConnections = listOf("character-1")),
        )

        val selectedPersonaId = findPersonaIdBoundToCharacter(personas, "character-2")

        assertNull(selectedPersonaId)
    }

    @Test
    fun nullCharacter_returnsNull() {
        val personas = listOf(
            persona(id = "persona-a", characterConnections = listOf("character-1")),
        )

        val selectedPersonaId = findPersonaIdBoundToCharacter(personas, null)

        assertNull(selectedPersonaId)
    }

    @Test
    fun initialPersona_usesBoundPersonaBeforeDefault() {
        val personas = listOf(
            persona(id = "persona-a", characterConnections = listOf("character-1")),
        )

        val selectedPersonaId = selectInitialPersonaIdForCharacter(
            personas = personas,
            defaultPersonaId = "default-persona",
            characterId = "character-1",
        )

        assertEquals("persona-a", selectedPersonaId)
    }

    @Test
    fun initialPersona_usesDefaultWhenCharacterIsUnbound() {
        val personas = listOf(
            persona(id = "persona-a", characterConnections = listOf("character-1")),
        )

        val selectedPersonaId = selectInitialPersonaIdForCharacter(
            personas = personas,
            defaultPersonaId = UserPersona.NONE_PERSONA_ID,
            characterId = "character-2",
        )

        assertEquals(UserPersona.NONE_PERSONA_ID, selectedPersonaId)
    }

    @Test
    fun storagePersonaId_convertsNonePersonaToNull() {
        val storagePersonaId = normalizePersonaIdForConversationStorage(UserPersona.NONE_PERSONA_ID)

        assertNull(storagePersonaId)
    }

    @Test
    fun storagePersonaId_keepsRealPersonaId() {
        val storagePersonaId = normalizePersonaIdForConversationStorage("persona-a")

        assertEquals("persona-a", storagePersonaId)
    }

    @Test
    fun storagePersonaId_keepsNullAsNull() {
        val storagePersonaId = normalizePersonaIdForConversationStorage(null)

        assertNull(storagePersonaId)
    }

    private fun persona(id: String, characterConnections: List<String>): UserPersona {
        return UserPersona(
            id = id,
            name = id,
            characterConnections = characterConnections,
        )
    }
}
