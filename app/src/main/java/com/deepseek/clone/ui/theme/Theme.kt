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
    val divider: Color,
    val error: Color,
    val success: Color,
    val onPrimary: Color,
    val onBubbleUser: Color
)

private val LightPalette = AppPalette(
    primary = Color(0xFF4D6BFE),
    primaryDark = Color(0xFF3A54D6),
    primaryLight = Color(0xFFEBEEFF),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F8FA),
    surfaceVariant = Color(0xFFEFF1F5),
    bubbleUser = Color(0xFF4D6BFE),
    bubbleAI = Color(0xFFF4F5F7),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF6B7280),
    divider = Color(0xFFE9EAEC),
    error = Color(0xFFE5484D),
    success = Color(0xFF30A46C),
    onPrimary = Color(0xFFFFFFFF),
    onBubbleUser = Color(0xFFFFFFFF)
)

private val DarkPalette = AppPalette(
    primary = Color(0xFF8FA3FF),
    primaryDark = Color(0xFFB7C5FF),
    primaryLight = Color(0xFF2A3A7A),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    bubbleUser = Color(0xFF3A54D6),
    bubbleAI = Color(0xFF1E1E1E),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFFB0B0B0),
    divider = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B6B),
    success = Color(0xFF4CC38A),
    onPrimary = Color(0xFF101442),
    onBubbleUser = Color(0xFFFFFFFF)
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
    val Divider: Color get() = palette.value.divider
    val Error: Color get() = palette.value.error
    val Success: Color get() = palette.value.success
    val OnPrimary: Color get() = palette.value.onPrimary
    val OnBubbleUser: Color get() = palette.value.onBubbleUser
}

// 直接写字面量，避免顶层 val 初始化时绑定到某个固定调色板
private val LightColors = lightColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBEEFF),
    onPrimaryContainer = Color(0xFF3A54D6),
    secondary = Color(0xFF4D6BFE),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF7F8FA),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE9EAEC),
    error = Color(0xFFE5484D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA3FF),
    onPrimary = Color(0xFF101442),
    primaryContainer = Color(0xFF2A3A7A),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF8FA3FF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B6B)
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
