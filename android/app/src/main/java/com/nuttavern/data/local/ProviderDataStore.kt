package com.nuttavern.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuttavern.data.model.Provider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Provider 持久化。独立 DataStore 文件 `nuttavern_providers.preferences_pb`,与
 * `nuttavern_settings` / `nuttavern_theme` 都分开:Provider 在启动早期就要读取
 * (决定 Composer / 模型选择器初值),与其他偏好放一起会拖慢首屏。
 *
 * 序列化用 kotlinx.serialization,sealed class 多态由 [Provider] 上的
 * `@SerialName` 自动接管。任何反序列化错误兜底到空列表(等价于"全新装机"),不抛异常。
 */
private val Context.providerDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuttavern_providers")

@Singleton
class ProviderDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private companion object {
        val KEY_PROVIDERS = stringPreferencesKey("providers_v2_json")
        val KEY_SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id_v2")
        val KEY_SELECTED_MODEL_INTERNAL_ID = stringPreferencesKey("selected_model_internal_id_v2")
    }

    val providersFlow: Flow<List<Provider>> = context.providerDataStore.data.map { prefs ->
        decode(prefs[KEY_PROVIDERS])
    }

    suspend fun hasSavedProviders(): Boolean {
        return context.providerDataStore.data
            .map { it[KEY_PROVIDERS] != null }
            .first()
    }

    suspend fun getProviders(): List<Provider> {
        val raw = context.providerDataStore.data
            .map { it[KEY_PROVIDERS] }
            .first()
        return decode(raw)
    }

    suspend fun saveProviders(providers: List<Provider>) {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Provider.serializer()),
            providers,
        )
        context.providerDataStore.edit { prefs -> prefs[KEY_PROVIDERS] = encoded }
    }

    suspend fun getSelectedProviderId(): String {
        return context.providerDataStore.data
            .map { it[KEY_SELECTED_PROVIDER_ID].orEmpty() }
            .first()
    }

    suspend fun saveSelectedProviderId(id: String) {
        context.providerDataStore.edit { prefs -> prefs[KEY_SELECTED_PROVIDER_ID] = id }
    }

    suspend fun getSelectedModelInternalId(): String {
        return context.providerDataStore.data
            .map { it[KEY_SELECTED_MODEL_INTERNAL_ID].orEmpty() }
            .first()
    }

    suspend fun saveSelectedModelInternalId(id: String) {
        context.providerDataStore.edit { prefs -> prefs[KEY_SELECTED_MODEL_INTERNAL_ID] = id }
    }

    private fun decode(raw: String?): List<Provider> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Provider.serializer()),
                raw,
            )
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}
