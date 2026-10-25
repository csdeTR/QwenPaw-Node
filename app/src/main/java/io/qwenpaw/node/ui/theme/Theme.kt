package io.qwenpaw.node.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D3FA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF25005A),
    secondary = Color(0xFF625B71),
    surface = Color(0xFFFEF7FF),
    surfaceVariant = Color(0xFFE7E0EC),
    background = Color(0xFFFDF8FF),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    surface = Color(0xFF141218),
    surfaceVariant = Color(0xFF49454F),
    background = Color(0xFF0F0D13),
    error = Color(0xFFFFB4AB),
)

/** 可调字号：小 / 标准 / 大。 */
enum class FontScale(val multiplier: Float) {
    SMALL(0.9f),
    NORMAL(1.0f),
    LARGE(1.15f),
}

@Composable
fun QwenPawTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: FontScale = FontScale.NORMAL,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) DarkColors else LightColors
    val typography = scaledTypography(fontScale)

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = base,
        typography = typography,
        content = content,
    )
}

private fun scaledTypography(scale: FontScale): Typography {
    val m = scale.multiplier
    val base = Typography()
    fun TextStyle.scaled() = copy(
        fontSize = fontSize * m,
        lineHeight = lineHeight * m,
    )
    return Typography(
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

/** 代码块专用样式。 */
val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    fontWeight = FontWeight.Normal,
)

val MessageMaxWidth = 640.dp
