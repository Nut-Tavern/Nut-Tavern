package com.nuttavern.data.preset

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * 预设的酒馆 chat completion 预设 JSON ↔ 内部 [Preset] 编解码器。
 *
 * 只在 JsonElement 层做形态转换,**不改 [Preset] 内部模型与 DataStore 存储格式**。隔离酒馆
 * JSON 的两处怪癖,避免污染内部 schema:
 *
 * 1. **枚举裸数字 vs 带引号字符串**:酒馆把 `names_behavior` / `injection_position` 写成裸数字
 *    `0`(Default.json:35、PromptManager.js:915 `Number(...)`),而内部 [NamesBehavior] /
 *    [InjectionPosition] 用 `@SerialName("0")` 序列化成带引号 `"0"`。直接互喂会 decode 失败。
 * 2. **prompt_order.character_id 数字 vs 字符串标识**:酒馆 chat completion 把 promptManager 配成
 *    `strategy:'global', dummyId:100001`(openai.js:687-690),运行时只读 `character_id == 100001`
 *    那条(PromptManager.js:1131-1132 / 1208);`100000` 是基类默认 dummyId,chat completion 模式
 *    **不消费**(legacy 槽,Default.json 仍带着)。所以本仓库 `__global__`(PromptComposer 唯一消费
 *    的顺序)对应酒馆 **100001**,不是 100000。
 *    - 导入:取活跃顺序(100001 优先,100000 回退)填本仓库 GLOBAL + GROUP 两槽(对齐 Preset.default);
 *    - 导出:本仓库 GLOBAL 顺序同时写 100000 + 100001(保证酒馆读 100001 拿到正确顺序);GROUP 不导出;
 *    - 按具体角色 id 定制的排序在本仓库无对应角色(角色用 UUID),导入忽略;运行时本就只读全局,无副作用。
 *
 * 不保留酒馆预设里的连接配置 / 全局生成行为字段(chat_completion_source / *_model /
 * reverse_proxy / function_calling / reasoning_effort / verbosity 等):这些在本仓库归 Provider /
 * Tools / Character / 会话级管理,不随预设流转。导入时经 `ignoreUnknownKeys` 静默丢弃,导出不写回。
 */
object PresetSillyTavernCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private const val ST_LEGACY_CHARACTER_ID = 100000
    private const val ST_ACTIVE_CHARACTER_ID = 100001

    private const val KEY_NAMES_BEHAVIOR = "names_behavior"
    private const val KEY_PROMPTS = "prompts"
    private const val KEY_PROMPT_ORDER = "prompt_order"
    private const val KEY_INJECTION_POSITION = "injection_position"
    private const val KEY_CHARACTER_ID = "character_id"
    private const val KEY_ORDER = "order"

    // 内部专用字段,不写进酒馆预设 JSON(酒馆预设名取自文件名,不含 id / 时间戳)。
    private val INTERNAL_ONLY_KEYS = setOf("id", "name", "description", "createdAt", "updatedAt")

    /**
     * 把酒馆预设 JSON 解析为内部 [Preset]。
     *
     * @param jsonText 酒馆导出的预设 JSON 文本
     * @param presetName 预设名(酒馆约定取自文件名,JSON 本体不含 name)
     * @throws kotlinx.serialization.SerializationException JSON 结构非法时抛出,由调用方兜底
     */
    fun decodeFromSillyTavern(jsonText: String, presetName: String): Preset {
        val root = json.parseToJsonElement(jsonText) as? JsonObject
            ?: throw IllegalArgumentException("预设 JSON 顶层不是对象")
        val normalized = buildJsonObject {
            root.forEach { (key, value) ->
                when (key) {
                    KEY_NAMES_BEHAVIOR -> put(key, intToQuotedString(value))
                    KEY_PROMPTS -> put(key, normalizePromptsForImport(value))
                    KEY_PROMPT_ORDER -> put(key, normalizePromptOrderForImport(value))
                    else -> put(key, value)
                }
            }
        }
        val now = System.currentTimeMillis()
        return json.decodeFromJsonElement(Preset.serializer(), normalized).copy(
            id = UUID.randomUUID().toString(),
            name = presetName,
            description = "",
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 把内部 [Preset] 编码为酒馆预设 JSON 文本(瘦身预设:只含拼接相关字段)。
     */
    fun encodeToSillyTavern(preset: Preset): String {
        val encoded = json.encodeToJsonElement(Preset.serializer(), preset) as JsonObject
        val sillyTavern = buildJsonObject {
            encoded.forEach { (key, value) ->
                when {
                    key in INTERNAL_ONLY_KEYS -> Unit // 跳过内部专用字段
                    key == KEY_NAMES_BEHAVIOR -> put(key, quotedStringToInt(value))
                    key == KEY_PROMPTS -> put(key, normalizePromptsForExport(value))
                    key == KEY_PROMPT_ORDER -> put(key, normalizePromptOrderForExport(value))
                    else -> put(key, value)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), sillyTavern)
    }

    // ── prompts[].injection_position 转换 ──

    private fun normalizePromptsForImport(prompts: JsonElement): JsonElement {
        val array = prompts as? JsonArray ?: return prompts
        return JsonArray(array.map { element ->
            val entry = element as? JsonObject ?: return@map element
            convertEntryField(entry, KEY_INJECTION_POSITION, ::intToQuotedString)
        })
    }

    private fun normalizePromptsForExport(prompts: JsonElement): JsonElement {
        val array = prompts as? JsonArray ?: return prompts
        return JsonArray(array.map { element ->
            val entry = element as? JsonObject ?: return@map element
            convertEntryField(entry, KEY_INJECTION_POSITION, ::quotedStringToInt)
        })
    }

    /** 对象里存在 [field] 时用 [transform] 转换其值,否则原样返回。 */
    private fun convertEntryField(
        entry: JsonObject,
        field: String,
        transform: (JsonElement) -> JsonElement,
    ): JsonObject {
        if (field !in entry) return entry
        return buildJsonObject {
            entry.forEach { (key, value) ->
                if (key == field) put(key, transform(value)) else put(key, value)
            }
        }
    }

    // ── prompt_order.character_id 转换 ──

    /** 导入:数字 100000/100001 → 字符串标识;其余 character_id(角色定制)整条丢弃。 */
    /**
     * 导入:提取酒馆"实际消费的全局顺序",填充本仓库 [PromptOrderForCharacter.GLOBAL_CHARACTER_ID]
     * 与 [PromptOrderForCharacter.GROUP_CHARACTER_ID](两条相同,对齐 [Preset.default] 结构)。
     *
     * 酒馆 chat completion 把 `promptManager` 配成 `strategy:'global', dummyId:100001`
     * (openai.js:687-690),运行时 `activeCharacter.id = 100001`,只读 `character_id == 100001`
     * 那条(PromptManager.js:1131-1132 / 1208)。`100000` 是基类默认 dummyId,chat completion
     * 模式下不被消费(legacy 槽)。所以本仓库 `__global__`(PromptComposer 唯一消费的顺序)
     * 必须对应酒馆 100001,而非 100000。
     *
     * 取值优先级:100001 > 100000(legacy 回退)。按具体角色 id 定制的排序在本仓库无对应角色
     * (本仓库角色用 UUID),整条忽略。
     */
    private fun normalizePromptOrderForImport(promptOrder: JsonElement): JsonElement {
        val array = promptOrder as? JsonArray ?: return promptOrder
        val orderByStId = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val stId = (item[KEY_CHARACTER_ID] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val order = item[KEY_ORDER] ?: return@mapNotNull null
            stId to order
        }.toMap()

        val activeOrder = orderByStId[ST_ACTIVE_CHARACTER_ID]
            ?: orderByStId[ST_LEGACY_CHARACTER_ID]
            ?: return JsonArray(emptyList())

        // 全局与群聊样板都填同一份活跃顺序,与本仓库自建预设结构一致。
        return buildJsonArray {
            add(promptOrderItem(PromptOrderForCharacter.GLOBAL_CHARACTER_ID, activeOrder))
            add(promptOrderItem(PromptOrderForCharacter.GROUP_CHARACTER_ID, activeOrder))
        }
    }

    /**
     * 导出:本仓库 [PromptOrderForCharacter.GLOBAL_CHARACTER_ID] 顺序同时写酒馆 100000 与 100001,
     * 保证酒馆无论读哪条(chat completion 读 100001)都拿到正确顺序,且保持 Default.json 双槽形态。
     * [PromptOrderForCharacter.GROUP_CHARACTER_ID] 是本仓库群聊样板,酒馆无对应概念,不导出。
     */
    private fun normalizePromptOrderForExport(promptOrder: JsonElement): JsonElement {
        val array = promptOrder as? JsonArray ?: return promptOrder
        val globalOrder = array.mapNotNull { it as? JsonObject }.firstOrNull { item ->
            (item[KEY_CHARACTER_ID] as? JsonPrimitive)?.contentOrNull() ==
                PromptOrderForCharacter.GLOBAL_CHARACTER_ID
        }?.get(KEY_ORDER) ?: return JsonArray(emptyList())

        return buildJsonArray {
            add(promptOrderItemNumeric(ST_LEGACY_CHARACTER_ID, globalOrder))
            add(promptOrderItemNumeric(ST_ACTIVE_CHARACTER_ID, globalOrder))
        }
    }

    private fun promptOrderItem(characterId: String, order: JsonElement): JsonObject = buildJsonObject {
        put(KEY_CHARACTER_ID, JsonPrimitive(characterId))
        put(KEY_ORDER, order)
    }

    private fun promptOrderItemNumeric(characterId: Int, order: JsonElement): JsonObject = buildJsonObject {
        put(KEY_CHARACTER_ID, JsonPrimitive(characterId))
        put(KEY_ORDER, order)
    }

    // ── 基础转换工具 ──

    /** 裸数字 → 带引号字符串("0");非数字原样返回(已是字符串或缺省时不破坏)。 */
    private fun intToQuotedString(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive ?: return value
        if (primitive.isString) return primitive
        val intValue = primitive.intOrNull ?: return value
        return JsonPrimitive(intValue.toString())
    }

    /** 带引号字符串("0") → 裸数字;非数字字符串原样返回。 */
    private fun quotedStringToInt(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive ?: return value
        val intValue = primitive.contentOrNull()?.toIntOrNull() ?: return value
        return JsonPrimitive(intValue)
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
}
