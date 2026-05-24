package com.nuttavern.data.persona

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 用户身份持久化。独立 DataStore 文件 `nuttavern_personas.preferences_pb`,与 Provider /
 * Theme / Settings 都分开:Provider 在启动早期就要读取,身份数据如果挤进同一个 DataStore
 * 会拖慢首屏,且未来字段扩展(头像 / lorebook 关联等)频繁,放一起会让别的偏好读取失败概率
 * 增高(任意字段反序列化失败会让整文件归零)。
 *
 * 序列化用 kotlinx.serialization,与 Provider 一致。
 *
 * # 反序列化失败兜底
 *
 * 任何反序列化错误等价于"全新装机":
 * - personas 列表 → 空列表
 * - defaultPersonaId → null(消费方会回退到 [UserPersona.NONE_PERSONA_ID])
 * - personaOrder → 空列表
 *
 * 不抛异常,避免 app 因为旧版数据破坏而无法启动。代价:升级时如果字段不兼容会丢失自定义身份。
 * 字段加默认值的写法(全部 [UserPersona] / 枚举字段都带默认)已经把这个风险降到最低,
 * `Json { ignoreUnknownKeys = true }` 也保证未来加字段能向前兼容。
 *
 * 反序列化失败时会调用 [errorReporter] — 当前固定走 `System.err`,后续接 logger 框架时
 * 把这个内部字段提升为构造参数即可在测试 / 生产环境注入不同实现。
 */
private val Context.personaDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_personas")

@Singleton
class PersonaDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * 反序列化失败上报钩子。当前固定走 stderr。需要接 Sentry / Logger / 单测断言时,
     * 把它提升为构造参数注入即可,本类内部的调用点不变。
     */
    private val errorReporter: (String, Throwable) -> Unit = { context, error ->
        android.util.Log.w("PersonaDataStore", context, error)
    }

    private val personaListSerializer = ListSerializer(UserPersona.serializer())
    private val orderListSerializer = ListSerializer(String.serializer())

    private companion object {
        val KEY_PERSONAS = stringPreferencesKey("personas_v1_json")
        val KEY_DEFAULT_PERSONA_ID = stringPreferencesKey("default_persona_id_v1")
        val KEY_PERSONA_ORDER = stringPreferencesKey("persona_order_v1_json")
    }

    /**
     * 当前快照,代表 DataStore 里完整的用户身份状态,**作为 [mutate] 的原子读写单位**。
     */
    data class Snapshot(
        val personas: List<UserPersona>,
        val defaultPersonaId: String?,
        val orderedIds: List<String>,
    )

    val personasFlow: Flow<List<UserPersona>> = context.personaDataStore.data.map { prefs ->
        decodePersonas(prefs[KEY_PERSONAS])
    }

    val defaultPersonaIdFlow: Flow<String?> = context.personaDataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PERSONA_ID]
    }

    val orderFlow: Flow<List<String>> = context.personaDataStore.data.map { prefs ->
        decodeOrder(prefs[KEY_PERSONA_ORDER])
    }

    /**
     * 原子读 + 改 + 写。把 personas / 默认 id / 顺序三个字段当作一个不可拆的写单元,
     * 避免中间崩溃留下"persona 写了但顺序没写 / 默认还指着已删 id"等不一致中间态。
     *
     * 实现使用 DataStore 的单次 `edit { ... }` 块,所有 key 一次提交。
     */
    suspend fun mutate(transform: (Snapshot) -> Snapshot) {
        context.personaDataStore.edit { prefs ->
            val current = Snapshot(
                personas = decodePersonas(prefs[KEY_PERSONAS]),
                defaultPersonaId = prefs[KEY_DEFAULT_PERSONA_ID],
                orderedIds = decodeOrder(prefs[KEY_PERSONA_ORDER]),
            )
            val next = transform(current)
            prefs[KEY_PERSONAS] = json.encodeToString(personaListSerializer, next.personas)
            prefs[KEY_PERSONA_ORDER] = json.encodeToString(orderListSerializer, next.orderedIds)
            if (next.defaultPersonaId == null) {
                prefs.remove(KEY_DEFAULT_PERSONA_ID)
            } else {
                prefs[KEY_DEFAULT_PERSONA_ID] = next.defaultPersonaId
            }
        }
    }

    private fun decodePersonas(raw: String?): List<UserPersona> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(personaListSerializer, raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decodePersonas", error)
                    emptyList()
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }

    private fun decodeOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(orderListSerializer, raw) }
            .recover { error ->
                if (error is SerializationException || error is IllegalArgumentException) {
                    errorReporter("decodeOrder", error)
                    emptyList()
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }
}
