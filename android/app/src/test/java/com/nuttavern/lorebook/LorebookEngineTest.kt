package com.nuttavern.lorebook

import com.nuttavern.data.lorebook.Lorebook
import com.nuttavern.data.lorebook.LorebookEntry
import com.nuttavern.prompt.TokenCounter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookEngineTest {

    private val engine = LorebookEngine(TokenCounter())
    private val json = Json { encodeDefaults = true }

    @Test
    fun delaySuppressesEntryBeforeMessageCountReachesDelay() {
        val entry = entry(content = "delayed", key = listOf("key"), delay = 3)
        val early = activate(entry, messages = listOf("key"), messageCount = 2)
        val ready = activate(entry, messages = listOf("key"), messageCount = 3)

        assertEquals(emptyList<LorebookEntry>(), early.activatedEntries)
        assertEquals(listOf(entry), ready.activatedEntries)
    }

    @Test
    fun stickyKeepsEntryActiveWithoutKeyword() {
        val entry = entry(content = "sticky", key = listOf("key"), sticky = 2)
        val first = activate(entry, messages = listOf("key"), messageCount = 3)
        val second = activate(
            entry = entry,
            messages = listOf("no match"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(listOf(entry), first.activatedEntries)
        assertEquals(listOf(entry), second.activatedEntries)
    }

    @Test
    fun stickyDoesNotRerollProbability() {
        val entry = entry(content = "sticky", key = listOf("key"), sticky = 2, probability = 0)
        val initialState = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(entry, messageCount = 3, endMessageCount = 5)),
        )
        val result = activate(entry, messages = listOf("no match"), messageCount = 4, state = initialState)

        assertEquals(listOf(entry), result.activatedEntries)
    }

    @Test
    fun cooldownSuppressesKeywordMatch() {
        val entry = entry(content = "cooldown", key = listOf("key"), cooldown = 2)
        val first = activate(entry, messages = listOf("key"), messageCount = 3)
        val second = activate(
            entry = entry,
            messages = listOf("key again"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(listOf(entry), first.activatedEntries)
        assertEquals(emptyList<LorebookEntry>(), second.activatedEntries)
    }

    @Test
    fun cooldownStartsWhenStickyEnds() {
        val entry = entry(content = "timed", key = listOf("key"), sticky = 1, cooldown = 2)
        val first = activate(entry, messages = listOf("key"), messageCount = 3)
        val afterSticky = activate(
            entry = entry,
            messages = listOf("key"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(emptyList<LorebookEntry>(), afterSticky.activatedEntries)
        assertFalse(afterSticky.nextTimedEffects.sticky.containsKey("book.1"))
        assertTrue(afterSticky.nextTimedEffects.cooldown.containsKey("book.1"))
    }

    @Test
    fun stickyWinsWithinGroup() {
        val stickyEntry = entry(uid = 1, content = "sticky", key = listOf("sticky"), group = "same", sticky = 2)
        val otherEntry = entry(uid = 2, content = "other", key = listOf("other"), group = "same", groupWeight = 999)
        val first = activate(listOf(stickyEntry), messages = listOf("sticky"), messageCount = 3)
        val second = activate(
            entries = listOf(stickyEntry, otherEntry),
            messages = listOf("other"),
            messageCount = 4,
            state = first.nextTimedEffects,
        )

        assertEquals(listOf(stickyEntry), second.activatedEntries)
    }

    @Test
    fun delayUntilRecursionActivatesOnlyAtConfiguredLevel() {
        val first = entry(uid = 1, content = "alpha", key = listOf("alpha"))
        val second = entry(uid = 2, content = "beta", key = listOf("alpha"), delayUntilRecursion = 1)
        val third = entry(uid = 3, content = "gamma", key = listOf("beta"), delayUntilRecursion = 2)

        val result = activate(
            entries = listOf(first, second, third),
            messages = listOf("alpha"),
            recursiveScanning = true,
            messageCount = 3,
        )

        assertEquals(listOf(first, second, third), result.activatedEntries)
    }

    @Test
    fun recursionDoesNotStartWithoutSuccessfulActivation() {
        val delayed = entry(uid = 1, content = "delayed", key = listOf("alpha"), delayUntilRecursion = 1)

        val result = activate(
            entries = listOf(delayed),
            messages = listOf("alpha"),
            recursiveScanning = true,
            messageCount = 3,
        )

        assertEquals(emptyList<LorebookEntry>(), result.activatedEntries)
    }

    @Test
    fun sameScanPassDoesNotTriggerAnotherEntryByNewContent() {
        val first = entry(uid = 1, content = "beta", key = listOf("alpha"))
        val second = entry(uid = 2, content = "gamma", key = listOf("beta"))

        val result = activate(
            entries = listOf(first, second),
            messages = listOf("alpha"),
            recursiveScanning = false,
            messageCount = 3,
        )

        assertEquals(listOf(first), result.activatedEntries)
    }

    @Test
    fun messageCountRollbackRemovesUnprotectedEffect() {
        val entry = entry(content = "sticky", key = listOf("key"), sticky = 2)
        val state = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(entry, messageCount = 3, endMessageCount = 5)),
        )
        val result = activate(entry, messages = listOf("no match"), messageCount = 3, state = state)

        assertEquals(emptyList<LorebookEntry>(), result.activatedEntries)
        assertEquals(emptyMap<String, LorebookTimedEffect>(), result.nextTimedEffects.sticky)
    }

    @Test
    fun missingEntryStateIsKeptUntilIntervalPasses() {
        val missing = entry(content = "missing", key = listOf("key"), sticky = 3)
        val other = entry(uid = 2, content = "other", key = listOf("other"))
        val state = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(missing, messageCount = 3, endMessageCount = 6)),
        )

        val result = activate(other, messages = listOf("other"), messageCount = 4, state = state)

        assertTrue(result.nextTimedEffects.sticky.containsKey("book.1"))
    }

    @Test
    fun hashMismatchRemovesOldEffect() {
        val original = entry(content = "old", key = listOf("key"), sticky = 2)
        val changed = entry(content = "new", key = listOf("key"), sticky = 2)
        val state = LorebookTimedEffectState(
            sticky = mapOf("book.1" to timedEffect(original, messageCount = 3, endMessageCount = 5)),
        )
        val result = activate(changed, messages = listOf("no match"), messageCount = 4, state = state)

        assertEquals(emptyList<LorebookEntry>(), result.activatedEntries)
        assertEquals(emptyMap<String, LorebookTimedEffect>(), result.nextTimedEffects.sticky)
    }

    private fun activate(
        entry: LorebookEntry,
        messages: List<String>,
        messageCount: Int,
        state: LorebookTimedEffectState = LorebookTimedEffectState.Empty,
    ): LorebookEngine.ActivationResult {
        return activate(listOf(entry), messages, messageCount = messageCount, state = state)
    }

    private fun activate(
        entries: List<LorebookEntry>,
        messages: List<String>,
        recursiveScanning: Boolean = false,
        messageCount: Int,
        state: LorebookTimedEffectState = LorebookTimedEffectState.Empty,
    ): LorebookEngine.ActivationResult {
        return engine.activate(
            messages = messages,
            lorebooks = listOf(
                TaggedLorebook(
                    book = Lorebook(
                        id = "book",
                        name = "book",
                        recursiveScanning = recursiveScanning,
                        entries = entries,
                    ),
                    isCharacterSource = false,
                    sourceKey = "book",
                ),
            ),
            messageCount = messageCount,
            timedEffects = state,
        )
    }

    private fun entry(
        uid: Int = 1,
        content: String,
        key: List<String>,
        group: String = "",
        groupWeight: Int = LorebookEntry.DEFAULT_WEIGHT,
        sticky: Int? = null,
        cooldown: Int? = null,
        delay: Int? = null,
        probability: Int = 100,
        delayUntilRecursion: Int = 0,
    ): LorebookEntry {
        return LorebookEntry(
            uid = uid,
            key = key,
            content = content,
            group = group,
            groupWeight = groupWeight,
            sticky = sticky,
            cooldown = cooldown,
            delay = delay,
            probability = probability,
            delayUntilRecursion = delayUntilRecursion,
        )
    }

    private fun timedEffect(
        entry: LorebookEntry,
        messageCount: Int,
        endMessageCount: Int,
    ): LorebookTimedEffect {
        return LorebookTimedEffect(
            entryHash = "book:${json.encodeToString(entry)}".hashCode(),
            startMessageCount = messageCount,
            endMessageCount = endMessageCount,
        )
    }
}
