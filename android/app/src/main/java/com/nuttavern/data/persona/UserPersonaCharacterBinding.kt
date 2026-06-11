package com.nuttavern.data.persona

/**
 * 按角色绑定关系选择新会话初始身份。
 *
 * 列表顺序来自 [PersonaRepository.personas],也就是用户在身份列表里的排序;第一个匹配项胜出。
 * `UserPersona.None` 是伪卡,即使未来错误携带了绑定 id,也不参与自动匹配。
 */
fun findPersonaIdBoundToCharacter(
    personas: List<UserPersona>,
    characterId: String?,
): String? {
    if (characterId == null) return null

    return personas.firstOrNull { persona ->
        !persona.isNonePersona && characterId in persona.characterConnections
    }?.id
}

fun selectInitialPersonaIdForCharacter(
    personas: List<UserPersona>,
    defaultPersonaId: String,
    characterId: String?,
): String {
    return findPersonaIdBoundToCharacter(personas, characterId) ?: defaultPersonaId
}

fun normalizePersonaIdForConversationStorage(personaId: String?): String? {
    return personaId?.takeIf { it != UserPersona.NONE_PERSONA_ID }
}
