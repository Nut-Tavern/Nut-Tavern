package com.nuttavern.data.regex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RegexExecutionTiming] 派生映射单测。
 *
 * 关键不变量:
 * 1. [RegexExecutionTiming.from] 永远返回非空(8 种全集覆盖三布尔笛卡尔积),不抛异常;
 * 2. **8 种组合全部是 [from]/[applyTo] 的双射不动点** — round-trip 一次后字段完全一致;
 * 3. [applyTo] 只动 markdownOnly / promptOnly / runOnEdit 三字段,其它字段保持原样;
 * 4. 不存在"非典型组合被夹取"的行为(对齐酒馆字段 round-trip)。
 */
class RegexExecutionTimingTest {

    private fun script(
        markdownOnly: Boolean = false,
        promptOnly: Boolean = false,
        runOnEdit: Boolean = false,
    ): RegexScript = RegexScript(
        scriptName = "t",
        findRegex = "/foo/",
        replaceString = "bar",
        markdownOnly = markdownOnly,
        promptOnly = promptOnly,
        runOnEdit = runOnEdit,
    )

    @Test
    fun fromAllEightCombinationsMapsUniquely() {
        // 三布尔的 8 种笛卡尔积都能映射到唯一 enum。
        val combos = mutableSetOf<RegexExecutionTiming>()
        for (md in listOf(false, true)) {
            for (po in listOf(false, true)) {
                for (re in listOf(false, true)) {
                    val s = script(markdownOnly = md, promptOnly = po, runOnEdit = re)
                    val timing = RegexExecutionTiming.from(s)
                    assertEquals("markdownOnly", md, timing.markdownOnly)
                    assertEquals("promptOnly", po, timing.promptOnly)
                    assertEquals("runOnEdit", re, timing.runOnEdit)
                    combos += timing
                }
            }
        }
        assertEquals("8 种组合应当映射到 8 个不同 enum", 8, combos.size)
    }

    @Test
    fun fromTypicalCombosMapsToExpectedTimings() {
        assertEquals(
            RegexExecutionTiming.AFTER_GENERATION,
            RegexExecutionTiming.from(script()),
        )
        assertEquals(
            RegexExecutionTiming.AFTER_GENERATION_AND_EDIT,
            RegexExecutionTiming.from(script(runOnEdit = true)),
        )
        assertEquals(
            RegexExecutionTiming.DISPLAY_ONLY,
            RegexExecutionTiming.from(script(markdownOnly = true)),
        )
        assertEquals(
            RegexExecutionTiming.PROMPT_ONLY,
            RegexExecutionTiming.from(script(promptOnly = true)),
        )
        assertEquals(
            RegexExecutionTiming.DISPLAY_AND_PROMPT,
            RegexExecutionTiming.from(script(markdownOnly = true, promptOnly = true)),
        )
    }

    @Test
    fun fromAtypicalCombosMapPreciselyNoClamp() {
        // 旧实现这三种被夹取到典型组合,会丢字段。新实现 1:1 round-trip。
        assertEquals(
            RegexExecutionTiming.DISPLAY_AND_EDIT,
            RegexExecutionTiming.from(script(markdownOnly = true, runOnEdit = true)),
        )
        assertEquals(
            RegexExecutionTiming.PROMPT_AND_EDIT,
            RegexExecutionTiming.from(script(promptOnly = true, runOnEdit = true)),
        )
        assertEquals(
            RegexExecutionTiming.DISPLAY_PROMPT_AND_EDIT,
            RegexExecutionTiming.from(script(
                markdownOnly = true,
                promptOnly = true,
                runOnEdit = true,
            )),
        )
    }

    @Test
    fun applyToOverwritesOnlyTimingFields() {
        val original = RegexScript(
            scriptName = "保留",
            findRegex = "/x/",
            replaceString = "y",
            trimStrings = listOf("noise"),
            placement = listOf(RegexPlacement.AI_OUTPUT.value),
            disabled = true,
            markdownOnly = false,
            promptOnly = false,
            runOnEdit = false,
            substituteRegex = SubstituteRegex.RAW.value,
            minDepth = 0,
            maxDepth = 5,
        )
        val applied = RegexExecutionTiming.DISPLAY_AND_PROMPT.applyTo(original)
        assertEquals("保留", applied.scriptName)
        assertEquals("/x/", applied.findRegex)
        assertEquals("y", applied.replaceString)
        assertEquals(listOf("noise"), applied.trimStrings)
        assertEquals(listOf(RegexPlacement.AI_OUTPUT.value), applied.placement)
        assertTrue(applied.disabled)
        assertEquals(SubstituteRegex.RAW.value, applied.substituteRegex)
        assertEquals(0, applied.minDepth)
        assertEquals(5, applied.maxDepth)
        // 三字段被改写
        assertTrue(applied.markdownOnly)
        assertTrue(applied.promptOnly)
        assertFalse(applied.runOnEdit)
    }

    @Test
    fun roundTripIsStableForAllEightTimings() {
        // 每个 enum 的 applyTo + from 都应回到自身,且字段完全一致。
        RegexExecutionTiming.entries.forEach { timing ->
            val first = timing.applyTo(script())
            val recovered = RegexExecutionTiming.from(first)
            assertEquals("$timing 应当是双射不动点", timing, recovered)
            assertEquals(timing.markdownOnly, first.markdownOnly)
            assertEquals(timing.promptOnly, first.promptOnly)
            assertEquals(timing.runOnEdit, first.runOnEdit)
        }
    }

    @Test
    fun roundTripPreservesAllAtypicalCombos() {
        // 全部 8 种组合从原始三字段出发,经 from + applyTo 应当字段完全保留。
        for (md in listOf(false, true)) {
            for (po in listOf(false, true)) {
                for (re in listOf(false, true)) {
                    val original = script(markdownOnly = md, promptOnly = po, runOnEdit = re)
                    val recovered = RegexExecutionTiming.from(original).applyTo(original)
                    assertEquals("md preserved md=$md/po=$po/re=$re", md, recovered.markdownOnly)
                    assertEquals("po preserved md=$md/po=$po/re=$re", po, recovered.promptOnly)
                    assertEquals("re preserved md=$md/po=$po/re=$re", re, recovered.runOnEdit)
                }
            }
        }
    }
}
