package com.nuttavern.data.registry

import com.nuttavern.data.model.Modality
import com.nuttavern.data.model.ModelAbility

/**
 * 模型命中规则的内部 DSL。基于 rikkahub 的 ModelDsl 改写,字段含义保持一致:
 *
 * - 模型 id 被分词为字母 / 数字 / 单字符段(例如 `gpt-4o-mini` → ["gpt","-","4","o","-","mini"]),
 *   匹配按 token 顺序而非子串;
 * - 一个 [ModelDefinition] 可以串多组 matcher(例如 `tokens` + `notTokens`),全部命中才算命中;
 * - [exact] 命中时给一个高分(EXACT_ID_BONUS),用来覆盖前缀类规则;
 * - 多条规则同时命中时,取分数最高的若干条;并集它们的能力声明。
 *
 * **约定**:本文件后续上新模型时由我们自己更新,不与 rikkahub upstream 同步;
 * 不要在这里塞业务相关的开关,只描述"模型 id 命中什么形态 / 能力"。
 */
fun interface ModelData<T> {
    fun getData(modelId: String): T
}

interface ModelSelector {
    fun match(modelId: String): Boolean
}

class ModelDefinition internal constructor(
    private val matcher: TokenMatcher,
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val abilities: Set<ModelAbility>,
) : ModelSelector {
    override fun match(modelId: String): Boolean {
        val tokens = tokenize(modelId)
        return matcher.score(modelId, tokens) != null
    }

    fun matchScore(modelId: String): Int? {
        val tokens = tokenize(modelId)
        return matcher.score(modelId, tokens)
    }

    internal fun matchScore(modelId: String, tokens: List<String>): Int? =
        matcher.score(modelId, tokens)
}

class ModelGroup internal constructor(
    private val members: List<ModelSelector>,
) : ModelSelector {
    override fun match(modelId: String): Boolean = members.any { it.match(modelId) }
}

fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition =
    ModelDefinitionBuilder().apply(block).build()

fun defineGroup(block: ModelGroupBuilder.() -> Unit): ModelGroup =
    ModelGroupBuilder().apply(block).build()

fun tokenRegex(pattern: String): TokenSpec = TokenRegex(pattern.toRegex(RegexOption.IGNORE_CASE))

class ModelDefinitionBuilder internal constructor() {
    private val matchers = mutableListOf<TokenMatcher>()
    private val inputModalities = mutableSetOf(Modality.TEXT)
    private val outputModalities = mutableSetOf(Modality.TEXT)
    private val abilities = mutableSetOf<ModelAbility>()

    fun tokens(vararg specs: String) {
        matchers += TokenSequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun tokens(vararg specs: TokenSpec) {
        matchers += TokenSequenceMatcher(specs.toList())
    }

    fun notTokens(vararg specs: String) {
        matchers += NotTokenSequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun notTokens(vararg specs: TokenSpec) {
        matchers += NotTokenSequenceMatcher(specs.toList())
    }

    fun exact(id: String) {
        matchers += ExactIdMatcher(id)
    }

    fun input(vararg modalities: Modality) {
        inputModalities.clear()
        inputModalities.addAll(modalities)
    }

    fun output(vararg modalities: Modality) {
        outputModalities.clear()
        outputModalities.addAll(modalities)
    }

    fun ability(vararg abilities: ModelAbility) {
        this.abilities.addAll(abilities)
    }

    internal fun build(): ModelDefinition {
        val matcher = when {
            matchers.isEmpty() -> MatchNone
            matchers.size == 1 -> matchers.first()
            else -> AndMatcher(matchers.toList())
        }
        return ModelDefinition(
            matcher = matcher,
            inputModalities = inputModalities.toSet(),
            outputModalities = outputModalities.toSet(),
            abilities = abilities.toSet(),
        )
    }
}

class ModelGroupBuilder internal constructor() {
    private val members = mutableListOf<ModelSelector>()

    fun add(vararg models: ModelSelector) {
        members.addAll(models)
    }

    internal fun build(): ModelGroup = ModelGroup(members.toList())
}

sealed interface TokenSpec {
    fun matches(token: String): Boolean
}

private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
    override fun matches(token: String): Boolean = options.contains(token)
}

private data class TokenRegex(val regex: Regex) : TokenSpec {
    override fun matches(token: String): Boolean = regex.matches(token)
}

internal interface TokenMatcher {
    fun score(modelId: String, tokens: List<String>): Int?
}

private object MatchNone : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? = null
}

private class AndMatcher(
    private val matchers: List<TokenMatcher>,
) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        var total = 0
        for (matcher in matchers) {
            val score = matcher.score(modelId, tokens) ?: return null
            total += score
        }
        return total
    }
}

private class ExactIdMatcher(private val id: String) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        return if (modelId.equals(id, ignoreCase = true)) {
            EXACT_ID_BONUS + tokens.size
        } else {
            null
        }
    }
}

private class TokenSequenceMatcher(
    private val specs: List<TokenSpec>,
) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        if (specs.isEmpty()) return null
        var specIndex = 0
        for (token in tokens) {
            if (specs[specIndex].matches(token)) {
                specIndex += 1
                if (specIndex == specs.size) return specs.size
            }
        }
        return null
    }
}

private class NotTokenSequenceMatcher(
    private val specs: List<TokenSpec>,
) : TokenMatcher {
    private val matcher = TokenSequenceMatcher(specs)

    override fun score(modelId: String, tokens: List<String>): Int? {
        return if (matcher.score(modelId, tokens) == null) 0 else null
    }
}

private fun parseTokenSpec(spec: String): TokenSpec {
    val options = spec.split('|')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    return TokenAlternatives(options)
}

private const val EXACT_ID_BONUS = 1000

internal fun tokenize(modelId: String): List<String> {
    val tokens = mutableListOf<String>()
    val input = modelId.lowercase()
    var index = 0
    while (index < input.length) {
        val ch = input[index]
        when {
            ch.isLetter() -> {
                val start = index
                index += 1
                while (index < input.length && input[index].isLetter()) {
                    index += 1
                }
                tokens.add(input.substring(start, index))
            }

            ch.isDigit() -> {
                val start = index
                index += 1
                while (index < input.length && input[index].isDigit()) {
                    index += 1
                }
                tokens.add(input.substring(start, index))
            }

            else -> {
                tokens.add(ch.toString())
                index += 1
            }
        }
    }
    return tokens
}
