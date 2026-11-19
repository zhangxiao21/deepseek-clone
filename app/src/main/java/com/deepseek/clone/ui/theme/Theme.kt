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
    val bubbleUserBorder: Color
)

private val LightPalette = AppPalette(
    primary = Color(0xFF4D6BFE),
    primaryDark = Color(0xFF3B57E0),
    primaryLight = Color(0xFFEBEFFF),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFFECEEF2),
    bubbleUser = Color(0xFFEBEFFF),
    bubbleAI = Color(0xFFF5F6F8),
    textPrimary = Color(0xFF1A1B1E),
    textSecondary = Color(0xFF6A6B70),
    textTertiary = Color(0xFF9A9BA0),
    reasoningBg = Color(0xFFF7F8FA),
    reasoningText = Color(0xFF6E6F74),
    divider = Color(0xFFE4E6EB),
    error = Color(0xFFE5484D),
    success = Color(0xFF2EBD85),
    onPrimary = Color(0xFFFFFFFF),
    onBubbleUser = Color(0xFF1A1B1E),
    bubbleUserBorder = Color(0xFFD6DCFF)
)

private val DarkPalette = AppPalette(
    primary = Color(0xFF4D6BFE),
    primaryDark = Color(0xFF3B57E0),
    primaryLight = Color(0xFF1E2240),
    background = Color(0xFF0E0E10),
    surface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFF232428),
    bubbleUser = Color(0xFF1E2240),
    bubbleAI = Color(0xFF16171A),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFF9A9BA0),
    textTertiary = Color(0xFF6A6B70),
    reasoningBg = Color(0xFF16171A),
    reasoningText = Color(0xFF8A8B90),
    divider = Color(0xFF2E2F33),
    error = Color(0xFFE5484D),
    success = Color(0xFF2EBD85),
    onPrimary = Color(0xFFFFFFFF),
    onBubbleUser = Color(0xFFECECEC),
    bubbleUserBorder = Color(0xFF2A3360)
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
}

// 直接写字面量，避免顶层 val 初始化时绑定到某个固定调色板
private val LightColors = lightColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBEFFF),
    onPrimaryContainer = Color(0xFF3B57E0),
    secondary = Color(0xFF4D6BFE),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFF5F6F8),
    onSurfaceVariant = Color(0xFF6A6B70),
    outline = Color(0xFFE4E6EB),
    error = Color(0xFFE5484D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E2240),
    onPrimaryContainer = Color(0xFFC5CDFF),
    secondary = Color(0xFF4D6BFE),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1A1B1E),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF232428),
    onSurfaceVariant = Color(0xFF9A9BA0),
    outline = Color(0xFF2E2F33),
    error = Color(0xFFE5484D)
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
