package com.deepseek.clone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppColors {
    val Primary = Color(0xFF4D6BFE)
    val PrimaryDark = Color(0xFF3A54D6)
    val PrimaryLight = Color(0xFFEBEEFF)
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFF7F8FA)
    val SurfaceVariant = Color(0xFFEFF1F5)
    val BubbleUser = Color(0xFF4D6BFE)
    val BubbleAI = Color(0xFFF4F5F7)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF6B7280)
    val Divider = Color(0xFFE9EAEC)
    val Error = Color(0xFFE5484D)
    val Success = Color(0xFF30A46C)
}

private val LightColors = lightColorScheme(
    primary = AppColors.Primary,
    onPrimary = Color.White,
    primaryContainer = AppColors.PrimaryLight,
    onPrimaryContainer = AppColors.PrimaryDark,
    secondary = AppColors.Primary,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Background,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.Surface,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.Divider,
    error = AppColors.Error
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA3FF),
    onPrimary = Color(0xFF101442),
    primaryContainer = Color(0xFF2A3A7A),
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}
