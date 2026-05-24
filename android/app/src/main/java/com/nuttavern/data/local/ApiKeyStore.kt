package com.nuttavern.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存放 Provider API Key 的加密 SharedPreferences。新版本按 providerId 单独存,
 * 不再按"协议"维度细分(新数据模型 [com.nuttavern.data.model.Provider] 一对一对应协议)。
 *
 * 数据落盘时 DataStore 上 Provider 的 apiKey 字段会被抹空,真实值只在这里。
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getApiKey(providerId: String): String {
        return sharedPreferences.getString(buildKey(providerId), "").orEmpty()
    }

    fun saveApiKey(providerId: String, apiKey: String) {
        sharedPreferences.edit()
            .putString(buildKey(providerId), apiKey.trim().take(MAX_API_KEY_LENGTH))
            .apply()
    }

    fun deleteApiKey(providerId: String) {
        sharedPreferences.edit()
            .remove(buildKey(providerId))
            .apply()
    }

    private fun buildKey(providerId: String): String = "$providerId:api_key"

    private companion object {
        const val FILE_NAME = "nuttavern_secure_provider_keys"
        const val MAX_API_KEY_LENGTH = 512
    }
}
