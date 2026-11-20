package com.deepseek.clone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** 外观模式：跟随系统 / 浅色 / 深色（持久化在 SecureStore） */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val ALL = listOf(SYSTEM, LIGHT, DARK)

    fun label(mode: String): String = when (mode) {
        LIGHT -> "浅色"
        DARK -> "深色"
        else -> "跟随系统"
    }

    fun description(mode: String): String = when (mode) {
        LIGHT -> "始终使用浅色界面"
        DARK -> "始终使用深色界面"
        else -> "跟随手机系统的深色模式设置"
    }

    fun isDark(mode: String, systemDark: Boolean): Boolean = when (mode) {
        LIGHT -> false
        DARK -> true
        else -> systemDark
    }
}

data class AppPalette(
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val bubbleUser: Color,
    val bubbleAI: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val reasoningBg: Color,
    val reasoningText: Color,
    val divider: Color,
    val error: Color,
    val success: Color,
    val onPrimary: Color,
    val onBubbleUser: Color,
    val bubbleUserBorder: Color,
    val inputBackground: Color
)

// 色值取自官方 chat-demo 源码 src/app/theme.css（blue-500/450、neutral-bluish 系列）
private val LightPalette = AppPalette(
    primary = Color(0xFF3964FE),          // blue-500
    primaryDark = Color(0xFF5686FE),      // blue-450 (hover)
    primaryLight = Color(0xFFEDF3FE),     // blue-50（胶囊激活底 / 用户气泡）
    background = Color(0xFFFFFFFF),       // neutral-bluish-00
    surface = Color(0xFFF9FAFB),          // neutral-bluish-50
    surfaceVariant = Color(0xFFF1F3F5),   // neutral-bluish-60
    bubbleUser = Color(0xFFEDF3FE),       // blue-50
    bubbleAI = Color(0xFFF9FAFB),
    textPrimary = Color(0xFF0F1115),      // neutral-bluish-1000
    textSecondary = Color(0xFF61666B),    // neutral-bluish-700
    textTertiary = Color(0xFF81858C),     // neutral-bluish-600
    reasoningBg = Color(0xFFF9FAFB),
    reasoningText = Color(0xFF61666B),
    divider = Color(0xFF1A1A1A).copy(alpha = 0.10f), // rgba(0,0,0,0.1)
    error = Color(0xFFEC1313),            // red-600
    success = Color(0xFF22C55E),          // green-500
    onPrimary = Color(0xFFFFFFFF),
    onBubbleUser = Color(0xFF0F1115),
    bubbleUserBorder = Color(0xFFB7C8FE),  // blue-300
    inputBackground = Color(0xFFFFFFFF)    // specific-input-major = neutral-bluish-00
)

private val DarkPalette = AppPalette(
    primary = Color(0xFF5686FE),          // blue-450
    primaryDark = Color(0xFF3964FE),      // blue-500
    primaryLight = Color(0xFF283848),     // deep blue tint (blue-900 近似)
    background = Color(0xFF151517),       // neutral-bluish-950
    surface = Color(0xFF2C2C2E),          // neutral-bluish-850
    surfaceVariant = Color(0xFF232428),
    bubbleUser = Color(0xFF2C2C2E),       // specific-bubble = neutral-bluish-850
    bubbleAI = Color(0xFF1B1B1C),
    textPrimary = Color(0xFFF9FAFB),      // neutral-bluish-50
    textSecondary = Color(0xFFCFCFD2),    // neutral-bluish-300
    textTertiary = Color(0xFFADB2B8),     // neutral-bluish-400
    reasoningBg = Color(0xFF1B1B1C),
    reasoningText = Color(0xFFADB2B8),
    divider = Color(0xFFFFFFFF).copy(alpha = 0.12f), // rgba(255,255,255,0.12)
    error = Color(0xFFF25A5A),            // red-400
    success = Color(0xFF4ED17E),          // green-400
    onPrimary = Color(0xFFFFFFFF),
    onBubbleUser = Color(0xFFF9FAFB),
    bubbleUserBorder = Color(0xFF4864B2),  // blue-600
    inputBackground = Color(0xFF2C2C2E)    // specific-input-major = neutral-bluish-850
)

/**
 * 动态调色板。
 *
 * 刻意用 mutableStateOf 而不是 @Composable getter / CompositionLocal：
 *  - getter 是普通函数，可在非 Composable 上下文读取（例如 MarkdownText.parseInline、
 *    函数默认参数），不会破坏现有 `AppColors.Xxx` 的调用写法；
 *  - Compose 会跟踪 state 读取，主题切换时所有读过颜色的位置自动重组。
 */
object AppColors {
    private val palette = mutableStateOf<AppPalette>(LightPalette)

    /** 幂等：仅在明暗真正变化时通知重组 */
    fun applyDark(dark: Boolean) {
        val next = if (dark) DarkPalette else LightPalette
        if (palette.value !== next) palette.value = next
    }

    val Primary: Color get() = palette.value.primary
    val PrimaryDark: Color get() = palette.value.primaryDark
    val PrimaryLight: Color get() = palette.value.primaryLight
    val Background: Color get() = palette.value.background
    val Surface: Color get() = palette.value.surface
    val SurfaceVariant: Color get() = palette.value.surfaceVariant
    val BubbleUser: Color get() = palette.value.bubbleUser
    val BubbleAI: Color get() = palette.value.bubbleAI
    val TextPrimary: Color get() = palette.value.textPrimary
    val TextSecondary: Color get() = palette.value.textSecondary
    val TextTertiary: Color get() = palette.value.textTertiary
    val ReasoningBg: Color get() = palette.value.reasoningBg
    val ReasoningText: Color get() = palette.value.reasoningText
    val Divider: Color get() = palette.value.divider
    val Error: Color get() = palette.value.error
    val Success: Color get() = palette.value.success
    val OnPrimary: Color get() = palette.value.onPrimary
    val OnBubbleUser: Color get() = palette.value.onBubbleUser
    val BubbleUserBorder: Color get() = palette.value.bubbleUserBorder
    val InputBackground: Color get() = palette.value.inputBackground
}

// 直接写字面量，避免顶层 val 初始化时绑定到某个固定调色板
private val LightColors = lightColorScheme(
    primary = Color(0xFF3964FE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDF3FE),
    onPrimaryContainer = Color(0xFF3964FE),
    secondary = Color(0xFF3964FE),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F1115),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFFF9FAFB),
    onSurfaceVariant = Color(0xFF61666B),
    outline = Color(0xFFE4E6EB),
    error = Color(0xFFEC1313)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5686FE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF283848),
    onPrimaryContainer = Color(0xFFB7C8FE),
    secondary = Color(0xFF5686FE),
    background = Color(0xFF151517),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF2C2C2E),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF232428),
    onSurfaceVariant = Color(0xFFCFCFD2),
    outline = Color(0xFF2E2F33),
    error = Color(0xFFF25A5A)
)

@Composable
fun DeepSeekTheme(
    themeMode: String = ThemeMode.SYSTEM,
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val dark = ThemeMode.isDark(themeMode, isSystemInDarkTheme())
    SideEffect { AppColors.applyDark(dark) }
    // 只放大 sp 字号（fontScale），保持 dp 尺寸不变，避免布局因缩放溢出
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale)
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = Typography(),
            content = content
        )
    }
}
