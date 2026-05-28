package com.nuttavern.lorebook

import kotlinx.serialization.Serializable

@Serializable
data class LorebookTimedEffectState(
    val sticky: Map<String, LorebookTimedEffect> = emptyMap(),
    val cooldown: Map<String, LorebookTimedEffect> = emptyMap(),
) {
    companion object {
        val Empty = LorebookTimedEffectState()
    }
}

@Serializable
data class LorebookTimedEffect(
    val entryHash: Int,
    val startMessageCount: Int,
    val endMessageCount: Int,
    val protectedEffect: Boolean = false,
)
