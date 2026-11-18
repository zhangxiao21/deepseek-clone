package com.deepseek.clone.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.core.ClientProfile

/**
 * 安全存储：API Key 与设置用 EncryptedSharedPreferences（底层 Android Keystore）加密。
 * API Key 等价账号付费权限，绝不写日志、绝不上传第三方。
 */
class SecureStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "deepseek_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** 官方 API Key（sk-...）。沿用 token 字段名以兼容旧数据结构。 */
    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_TOKEN).apply()
            else prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var defaultModelType: String
        get() = prefs.getString(KEY_MODEL, ApiConfig.MODEL_FLASH) ?: ApiConfig.MODEL_FLASH
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var isLoggedIn: Boolean
        get() = !token.isNullOrBlank()
        set(value) { if (!value) token = null }

    fun currentProfile(): ClientProfile = ClientProfile()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "api_key"
        private const val KEY_MODEL = "default_model"
    }
}
