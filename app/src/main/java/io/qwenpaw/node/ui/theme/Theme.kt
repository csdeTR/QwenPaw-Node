package io.qwenpaw.node.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/** QwenPaw 品牌色，取自 Console 的真实设计变量。 */
val Accent = Color(0xFFFF7F16)
val AccentHover = Color(0xFFFF9D4D)
val AccentActive = Color(0xFFD96806)
val AccentTextLight = Color(0xFFBD5100)
val AccentTextDark = Color(0xFFFF9A47)
val AccentSoft = Color(0x1AFF7F16)
val AccentBorder = Color(0x6BFF7F16)
val Success = Color(0xFF52C41A)
val Warning = Color(0xFFFAAD14)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE7D3),
    onPrimaryContainer = Color(0xFF7A3400),
    secondary = AccentTextLight,
    onSecondary = Color.White,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = AccentTextLight,
    tertiary = Warning,
    background = Color(0xFFF9F8F4),
    onBackground = Color(0xE1141413),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xE1141413),
    surfaceVariant = Color(0xFFFAF9F7),
    onSurfaceVariant = Color(0x94141413),
    surfaceContainer = Color(0xFFFAF9F7),
    surfaceContainerHigh = Color(0xFFF2F0EC),
    surfaceContainerLow = Color(0xFFFCFBF9),
    outline = Color(0xFFD8D4CF),
    outlineVariant = Color(0xFFEAE8E7),
    error = Color(0xFFCF1322),
    errorContainer = Color(0xFFFFF2F0),
    onErrorContainer = Color(0xFFCF1322),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2410),
    onPrimaryContainer = Color(0xFFFFD8B8),
    secondary = AccentTextDark,
    onSecondary = Color(0xFF3A1B00),
    secondaryContainer = Color(0x29FF7F16),
    onSecondaryContainer = AccentTextDark,
    tertiary = Warning,
    background = Color(0xFF141414),
    onBackground = Color(0xD9FFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xD9FFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xA6FFFFFF),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerLow = Color(0xFF191919),
    outline = Color(0x2EFFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFFF7875),
    errorContainer = Color(0xFF2C1618),
    onErrorContainer = Color(0xFFFF7875),
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
    /**
     * MiSans 字体族。字体是运行时按需下载的，没下载时传 null，
     * 交给系统默认字体——所以这里不再引用打包期资源。
     */
    misans: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) DarkColors else LightColors
    // 字体族查表较贵，按字号和字体分别缓存，避免每次重组都重建整套排版。
    val typography = remember(fontScale, misans) {
        scaledTypography(fontScale).withFamily(misans)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        LaunchedEffect(dark) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
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

/** 整体换字体。传 null 时保留系统默认字体。 */
private fun Typography.withFamily(family: FontFamily?): Typography {
    if (family == null) return this
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(),
        displayMedium = displayMedium.f(),
        displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(),
        headlineMedium = headlineMedium.f(),
        headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(),
        titleMedium = titleMedium.f(),
        titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(),
        bodyMedium = bodyMedium.f(),
        bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(),
        labelMedium = labelMedium.f(),
        labelSmall = labelSmall.f(),
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
