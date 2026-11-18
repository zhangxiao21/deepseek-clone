package com.deepseek.clone.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.deepseek.clone.core.ApiConfig
import com.deepseek.clone.core.ClientProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一条已保存的 API Key（备注名 + 密钥本体） */
data class ApiKeyEntry(
    val id: String,
    val label: String,
    val key: String
) {
    /** 只在 UI 上展示的脱敏形式，绝不打印完整密钥 */
    val masked: String
        get() = if (key.length > 10) "${key.take(6)}••••${key.takeLast(4)}" else "••••••••"
}

/**
 * 安全存储：API Key 与设置用 EncryptedSharedPreferences（底层 Android Keystore）加密。
 * API Key 等价账号付费权限，绝不写日志、绝不上传第三方。
 *
 * 依赖固定为 security-crypto 1.0.0：该版本只有 MasterKeys 与
 * create(fileName, masterKeyAlias, context, ...) 旧签名；MasterKey / create(context, ...) 属 1.1.0+。
 *
 * 多 Key：api_keys 存 JSON 数组，active_key_id 指向当前使用的一条；
 * 旧版本只存单个 api_key，首次读取时自动迁移为「默认密钥」，升级不丢登录态。
 */
class SecureStore(context: Context) {

    private val gson = Gson()
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "deepseek_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ---------- 多 API Key ----------

    /** 旧单 Key → 列表的惰性迁移（只执行一次） */
    private fun ensureMigrated() {
        if (prefs.contains(KEY_API_KEYS)) return
        val legacy = prefs.getString(KEY_TOKEN, null).orEmpty()
        val list = if (legacy.isBlank()) emptyList()
        else listOf(ApiKeyEntry(LEGACY_KEY_ID, "默认密钥", legacy))
        prefs.edit()
            .putString(KEY_API_KEYS, gson.toJson(list))
            .putString(KEY_ACTIVE_KEY, if (list.isEmpty()) "" else list[0].id)
            .apply()
    }

    private fun readEntries(): MutableList<ApiKeyEntry> {
        ensureMigrated()
        return try {
            gson.fromJson<MutableList<ApiKeyEntry>>(
                prefs.getString(KEY_API_KEYS, "[]"),
                object : TypeToken<MutableList<ApiKeyEntry>>() {}.type
            ) ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun writeEntries(list: List<ApiKeyEntry>) {
        prefs.edit().putString(KEY_API_KEYS, gson.toJson(list)).apply()
    }

    fun apiKeys(): List<ApiKeyEntry> = readEntries().toList()

    private var activeKeyId: String
        get() {
            ensureMigrated()
            return prefs.getString(KEY_ACTIVE_KEY, "").orEmpty()
        }
        set(value) = prefs.edit().putString(KEY_ACTIVE_KEY, value).apply()

    fun activeApiKey(): ApiKeyEntry? {
        val list = apiKeys()
        val id = activeKeyId
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setActiveApiKey(id: String) {
        if (apiKeys().any { it.id == id }) {
            activeKeyId = id
            authBlocked = false
            // 同步旧字段，保证任何直接读 api_key 的老代码拿到的是当前激活的 Key
            activeApiKey()?.let { prefs.edit().putString(KEY_TOKEN, it.key).apply() }
        }
    }

    fun addApiKey(label: String, key: String): String {
        val list = readEntries()
        val id = "key-" + java.util.UUID.randomUUID().toString()
        list.add(ApiKeyEntry(id, label.trim().ifBlank { "密钥 ${list.size + 1}" }, key.trim()))
        writeEntries(list)
        setActiveApiKey(id)
        return id
    }

    fun updateApiKey(id: String, label: String, key: String) {
        val list = readEntries()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(label = label.trim(), key = key.trim())
            writeEntries(list)
            if (activeKeyId == id) prefs.edit().putString(KEY_TOKEN, key.trim()).apply()
        }
    }

    fun removeApiKey(id: String) {
        val list = readEntries()
        list.removeAll { it.id == id }
        writeEntries(list)
        if (activeKeyId == id) {
            val next = list.firstOrNull()
            activeKeyId = next?.id.orEmpty()
            prefs.edit().putString(KEY_TOKEN, next?.key.orEmpty()).apply()
        }
        if (list.isEmpty()) authBlocked = false
    }

    /** 当前生效的 API Key（sk-...）。沿用 token 字段名以兼容旧数据结构。 */
    var token: String?
        get() = if (authBlocked) null else activeApiKey()?.key?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            authBlocked = false
            val clean = value?.trim().orEmpty()
            if (clean.isBlank()) {
                prefs.edit().remove(KEY_TOKEN).apply()
                return
            }
            val list = readEntries()
            val id = activeKeyId
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(key = clean)
            } else {
                val entry = ApiKeyEntry(
                    "key-" + java.util.UUID.randomUUID().toString(), "默认密钥", clean
                )
                list.add(0, entry)
                activeKeyId = entry.id
            }
            writeEntries(list)
            prefs.edit().putString(KEY_TOKEN, clean).apply()
        }

    /** 鉴权失败后临时登出（不删除已保存的其它 Key，切换即可恢复） */
    var authBlocked: Boolean
        get() = prefs.getBoolean(KEY_AUTH_BLOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTH_BLOCKED, value).apply()

    // ---------- 常规设置 ----------

    var defaultModelType: String
        get() = prefs.getString(KEY_MODEL, ApiConfig.MODEL_FLASH) ?: ApiConfig.MODEL_FLASH
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** 外观模式：system / light / dark（与 ui.theme.ThemeMode 的字符串值一致） */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** 全局字体缩放（1.0 标准 / 1.1 大 / 1.2 超大），通过 LocalDensity.fontScale 生效 */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.9f, 1.4f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value.coerceIn(0.9f, 1.4f)).apply()

    // ---------- 长期记忆 / 上下文压缩 / CoT ----------

    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    var memoryFactsJson: String
        get() = prefs.getString(KEY_MEMORY_FACTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_MEMORY_FACTS, value).apply()

    var compressionEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPRESSION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPRESSION_ENABLED, value).apply()

    var cotVisible: Boolean
        get() = prefs.getBoolean(KEY_COT_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_COT_VISIBLE, value).apply()

    /** 自动朗读 AI 回复（TextToSpeech 系统合成，默认关闭） */
    var autoReadAloud: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_ALOUD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_ALOUD, value).apply()

    var isLoggedIn: Boolean
        get() = !token.isNullOrBlank()
        set(value) { if (!value) authBlocked = true }

    fun currentProfile(): ClientProfile = ClientProfile()

    fun clearAll() {
        // 退出登录清空全部 Key；外观偏好与长期记忆属于用户数据，全部保留
        val keepTheme = themeMode
        val keepMemoryEnabled = memoryEnabled
        val keepMemoryFacts = memoryFactsJson
        val keepCompression = compressionEnabled
        val keepCot = cotVisible
        val keepAutoReadAloud = autoReadAloud
        val keepModel = defaultModelType
        val keepFontScale = fontScale
        prefs.edit().clear().apply()
        themeMode = keepTheme
        memoryEnabled = keepMemoryEnabled
        memoryFactsJson = keepMemoryFacts
        compressionEnabled = keepCompression
        cotVisible = keepCot
        autoReadAloud = keepAutoReadAloud
        defaultModelType = keepModel
        fontScale = keepFontScale
    }

    companion object {
        private const val KEY_TOKEN = "api_key"
        private const val KEY_API_KEYS = "api_keys"
        private const val KEY_ACTIVE_KEY = "active_key_id"
        private const val KEY_AUTH_BLOCKED = "auth_blocked"
        private const val LEGACY_KEY_ID = "key-default"
        private const val KEY_MODEL = "default_model"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_MEMORY_FACTS = "memory_facts"
        private const val KEY_COMPRESSION_ENABLED = "compression_enabled"
        private const val KEY_COT_VISIBLE = "cot_visible"
        private const val KEY_AUTO_READ_ALOUD = "auto_read_aloud"
    }
}
