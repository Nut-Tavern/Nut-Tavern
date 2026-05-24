package com.nuttavern.data.character

import com.nuttavern.data.regex.RegexScript
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CharacterSerializationTest {

    @Test
    fun characterRoundTripKeepsV3FieldNamesAndNestedData() {
        val character = Character(
            id = "character-1",
            name = "Alice",
            firstMessage = "Hello",
            messageExample = "<START>\nAlice: hi",
            systemPrompt = "Stay in character.",
            postHistoryInstructions = "End note.",
            alternateGreetings = listOf("Hi", "Hey"),
            tags = listOf("test", "v3"),
            extensions = buildJsonObject { put("vendor", "nut-tavern") },
            characterBook = CharacterBook(
                name = "Alice book",
                entries = listOf(
                    CharacterBookEntry(
                        keys = listOf("tea"),
                        content = "Alice likes tea.",
                        secondaryKeys = listOf("cup"),
                    )
                ),
            ),
            regexScripts = listOf(
                RegexScript(
                    id = "regex-1",
                    scriptName = "Trim brackets",
                    findRegex = "\\[(.*?)\\]",
                    replaceString = "$1",
                    placement = listOf(1, 2),
                )
            ),
            createdAt = 10L,
            updatedAt = 20L,
        )

        val encodedCharacter = json.encodeToString(character)
        val decodedCharacter = json.decodeFromString<Character>(encodedCharacter)

        assertEquals(character, decodedCharacter)
        assertNotNull(decodedCharacter.characterBook)
        assertEquals("Hello", decodedCharacter.firstMessage)
        assertEquals(listOf("Hi", "Hey"), decodedCharacter.alternateGreetings)
        assertEquals("Alice likes tea.", decodedCharacter.characterBook?.entries?.first()?.content)
        assertEquals("Trim brackets", decodedCharacter.regexScripts.first().scriptName)
    }

    @Test
    fun characterDefaultsAllowMinimalCardData() {
        val decodedCharacter = json.decodeFromString<Character>("""
            {
                "name": "Minimal",
                "first_mes": "Hi"
            }
        """.trimIndent())

        assertEquals("Minimal", decodedCharacter.name)
        assertEquals("Hi", decodedCharacter.firstMessage)
        assertEquals(emptyList<String>(), decodedCharacter.alternateGreetings)
        assertEquals(Character.EMPTY_JSON_OBJECT, decodedCharacter.extensions)
        assertEquals(null, decodedCharacter.characterBook)
        assertEquals(emptyList<RegexScript>(), decodedCharacter.regexScripts)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
