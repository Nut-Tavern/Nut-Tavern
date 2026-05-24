package com.nuttavern.data.registry

import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `gpt-5 matches gpt-5 mini and not unrelated ids`() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
    }

    @Test
    fun `claude series matches expected variants`() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
    }

    @Test
    fun `openai o-series matches o1 and o3-mini`() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.inferInputModalities("o3-mini"),
        )
    }

    @Test
    fun `gemini 2-5-flash matches and supports image input only`() {
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))

        assertEquals(listOf(Modality.TEXT), ModelRegistry.inferOutputModalities("gemini-2.5-flash"))
    }

    @Test
    fun `gemini image variants output image too`() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.inferOutputModalities("gemini-2.5-flash-image"),
        )
    }

    @Test
    fun `gemini latest aliases match exact id only`() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertFalse(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest-stable"))
    }

    @Test
    fun `unknown model defaults to text-only with no abilities`() {
        val inferred = ModelRegistry.inferAll("totally-unknown-model-x")
        assertEquals(listOf(Modality.TEXT), inferred.inputModalities)
        assertEquals(listOf(Modality.TEXT), inferred.outputModalities)
        assertTrue(inferred.abilities.isEmpty())
    }

    @Test
    fun `deepseek reasoner gets tool plus reasoning`() {
        val abilities = ModelRegistry.inferAbilities("deepseek-reasoner")
        assertTrue(ModelAbility.TOOL in abilities)
        assertTrue(ModelAbility.REASONING in abilities)
    }

    @Test
    fun `deepseek v4 flash inherits tool plus reasoning`() {
        val abilities = ModelRegistry.inferAbilities("deepseek-v4-flash")
        assertTrue(ModelAbility.TOOL in abilities)
        assertTrue(ModelAbility.REASONING in abilities)
    }

    @Test
    fun `claude opus 4-7 reports tool plus reasoning`() {
        val abilities = ModelRegistry.inferAbilities("claude-opus-4-7")
        assertTrue(ModelAbility.TOOL in abilities)
        assertTrue(ModelAbility.REASONING in abilities)
    }

    @Test
    fun `gpt 5-5 reports tool plus reasoning with vision`() {
        val inferred = ModelRegistry.inferAll("gpt-5.5")
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), inferred.inputModalities)
        assertTrue(ModelAbility.TOOL in inferred.abilities)
        assertTrue(ModelAbility.REASONING in inferred.abilities)
    }
}
