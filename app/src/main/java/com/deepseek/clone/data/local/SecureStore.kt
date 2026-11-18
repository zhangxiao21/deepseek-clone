package com.deepseek.clone.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.core.ClientProfile

/**
 * 安全存储：API Key 与设置用 EncryptedSharedPreferences（底层 Android Keystore）加密。
 * API Key 等价账号付费权限，绝不写日志、绝不上传第三方。
 *
 * 依赖固定为 security-crypto 1.0.0：该版本只有 MasterKeys 与
 * create(fileName, masterKeyAlias, context, ...) 旧签名；MasterKey / create(context, ...) 属 1.1.0+。
 */
class SecureStore(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "deepseek_secure_prefs",
        masterKeyAlias,
        context,
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

    /** 外观模式：system / light / dark（与 ui.theme.ThemeMode 的字符串值一致） */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var isLoggedIn: Boolean
        get() = !token.isNullOrBlank()
        set(value) { if (!value) token = null }

    fun currentProfile(): ClientProfile = ClientProfile()

    fun clearAll() {
        // 退出登录只清鉴权信息，外观偏好保留
        val keepTheme = themeMode
        prefs.edit().clear().apply()
        themeMode = keepTheme
    }

    companion object {
        private const val KEY_TOKEN = "api_key"
        private const val KEY_MODEL = "default_model"
        private const val KEY_THEME = "theme_mode"
    }
}
