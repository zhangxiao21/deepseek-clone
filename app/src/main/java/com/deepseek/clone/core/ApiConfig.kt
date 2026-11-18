package com.deepseek.clone.core

/**
 * DeepSeek 官方开放平台 API 配置（OpenAI 兼容）。
 * 文档：https://api-docs.deepseek.com/zh-cn/
 *
 * 与网页端私有接口无关：这里是 api.deepseek.com，用 API Key 鉴权，无 PoW、无版本头。
 */
object ApiConfig {
    const val BASE_URL = "https://api.deepseek.com/"
    const val API_KEY_URL = "https://platform.deepseek.com/api_keys"
    const val DEFAULT_USER_AGENT = "CyberWhale/1.3 (Android)"

    // 官方模型 ID（2026-11）
    const val MODEL_FLASH = "deepseek-flash"           // V4.1 Flash，支持视觉/图片
    const val MODEL_PRO = "deepseek-v4-pro"            // V4 Pro，强推理

    // 内置联网工具
    const val TOOL_WEB_SEARCH = "deepseek_web_search"
    const val TOOL_READ_URL = "deepseek_read_url"

    // thinking 模式
    const val THINK_ENABLED = "enabled"
    const val THINK_DISABLED = "disabled"

    // 推理强度
    const val EFFORT_LOW = "low"
    const val EFFORT_HIGH = "high"
    const val EFFORT_MAX = "max"

    // Files API purpose
    const val PURPOSE_USER_DATA = "user_data"                    // 图片/文本/表格/电子书
    const val PURPOSE_TEXT_RECOGNITION = "text_recognition"      // PDF/Word/PPT 只解析文字
    const val PURPOSE_RICH_LAYOUT = "rich_layout_recognition"    // 图文混排解析

    /**
     * 把任意来源的 model 名规范化成服务端认可的有效值。
     * 服务端只接受 deepseek-flash / deepseek-v4-pro，传入其他值（如导入会话
     * 写死的 "default"）会报 HTTP 400 invalid_request_error，这里统一兜底。
     */
    fun normalizeModel(raw: String?): String = when (raw) {
        MODEL_FLASH, MODEL_PRO -> raw
        else -> MODEL_FLASH
    }
}

/** 兼容设置页的可覆盖参数（官方 API 仅需 userAgent） */
data class ClientProfile(
    val userAgent: String = ApiConfig.DEFAULT_USER_AGENT
)

/** 用户可选模型（UI 展示） */
data class ModelOption(
    val id: String,
    val label: String,
    val desc: String,
    val supportsVision: Boolean
) {
    companion object {
        val ALL = listOf(
            ModelOption(ApiConfig.MODEL_FLASH, "DeepSeek Fast", "V4.1 Flash · 快速，支持图片", true),
            ModelOption(ApiConfig.MODEL_PRO, "DeepSeek Expert", "V4 Pro · 深度推理", false)
        )
        fun find(id: String) = ALL.find { it.id == id } ?: ALL[0]
    }
}
