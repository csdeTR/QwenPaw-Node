package io.qwenpaw.node.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
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

/**
 * 字号缩放。
 *
 * UI 与聊天正文分开调节：整体 UI 缩放影响标题、标签、按钮等；
 * 聊天缩放只叠加在消息正文上，方便用户把对话文字调得特别大而界面不变。
 * 两段都用连续值而不是三档枚举，范围也给得宽，能明显调出「很小」和「很大」。
 */
object FontScaling {
    const val UI_MIN = 0.75f
    const val UI_MAX = 1.6f
    const val CHAT_MIN = 0.6f
    const val CHAT_MAX = 2.2f
    const val DEFAULT = 1f

    fun ui(value: Float): Float = value.coerceIn(UI_MIN, UI_MAX)
    fun chat(value: Float): Float = value.coerceIn(CHAT_MIN, CHAT_MAX)
}

/** 聊天正文的额外缩放系数，由 [QwenPawTheme] 提供。 */
val LocalChatFontScale = staticCompositionLocalOf { FontScaling.DEFAULT }

/**
 * 注释级小字的颜色。
 *
 * `colorScheme.outline` 的用途是描边，浅色下是 `#D8D4CF`、深色下只有 18% 白，
 * 拿来写正文小字对比度严重不足（深色下尤其明显）。这里单独给一个语义色：
 * 浅色为 54% 黑、深色为 60% 白，都能达到 4.5:1 的可读下限，同时仍明显弱于正文。
 */
val LocalCaptionColor = staticCompositionLocalOf { Color(0x8A141413) }

/**
 * 选中文字的底色与把手色。
 *
 * 不能取 `colorScheme.primary`：那是品牌橙，而用户气泡的底色本身就是橙色，
 * 半透明橙叠在橙底上几乎看不出来，等于在用户消息里选不中文字。
 * 这里固定用蓝系，与橙色在色相上明显分开；两套主题的亮度不同，分别取值。
 */
private fun selectionColors(dark: Boolean): TextSelectionColors {
    val handle = if (dark) Color(0xFF7AA7FF) else Color(0xFF1F6FEB)
    return TextSelectionColors(
        handleColor = handle,
        backgroundColor = handle.copy(alpha = 0.32f),
    )
}

@Composable
fun QwenPawTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    /** 整体界面缩放，作用于整套排版。 */
    uiScale: Float = FontScaling.DEFAULT,
    /** 聊天正文的额外缩放，只影响消息气泡里的文字。 */
    chatScale: Float = FontScaling.DEFAULT,
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
    val sel = remember(dark) { selectionColors(dark) }
    // 字体族查表较贵，按字号和字体分别缓存，避免每次重组都重建整套排版。
    val ui = FontScaling.ui(uiScale)
    val typography = remember(ui, misans) {
        scaledTypography(ui).withFamily(misans)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        LaunchedEffect(dark) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    CompositionLocalProvider(
        LocalChatFontScale provides FontScaling.chat(chatScale),
        // 注释色跟着明暗走：深色主题下用 60% 白，否则用 54% 黑。
        LocalCaptionColor provides if (dark) Color(0x99FFFFFF) else Color(0x8A141413),
        LocalTextSelectionColors provides sel,
    ) {
        MaterialTheme(
            colorScheme = base,
            typography = typography,
            content = content,
        )
    }
}

/**
 * 聊天正文的缩放作用域。
 *
 * 在整体排版（已按 UI 字号缩放）之上再叠一层聊天缩放，所以对话文字可以单独
 * 调得特别大或特别小，而不影响标题栏、按钮这些界面元素。包裹的范围是消息列表，
 * 顶栏与输入框保持 UI 字号。
 */
@Composable
fun ChatFontScale(content: @Composable () -> Unit) {
    val scale = LocalChatFontScale.current
    if (scale == FontScaling.DEFAULT) {
        content()
        return
    }
    val base = MaterialTheme.typography
    // Typography 构造不便宜，按缩放系数和基础排版缓存一份。
    val scaled = remember(scale, base) {
        fun TextStyle.s() = copy(
            fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
            lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
        )
        Typography(
            displayLarge = base.displayLarge.s(),
            displayMedium = base.displayMedium.s(),
            displaySmall = base.displaySmall.s(),
            headlineLarge = base.headlineLarge.s(),
            headlineMedium = base.headlineMedium.s(),
            headlineSmall = base.headlineSmall.s(),
            titleLarge = base.titleLarge.s(),
            titleMedium = base.titleMedium.s(),
            titleSmall = base.titleSmall.s(),
            bodyLarge = base.bodyLarge.s(),
            bodyMedium = base.bodyMedium.s(),
            bodySmall = base.bodySmall.s(),
            labelLarge = base.labelLarge.s(),
            labelMedium = base.labelMedium.s(),
            labelSmall = base.labelSmall.s(),
        )
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = scaled,
        content = content,
    )
}

/**
 * 开关配色。
 *
 * 关闭态不能只是灰掉——那和「不可用」长得一样。这里让关闭态有一个明显更深的
 * 轨道和一圈清晰描边，读起来是「关着的开关」；同时把禁用态的灰色压得更淡更虚，
 * 让两者一眼可分。
 */
@Composable
fun qwenSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    // 开启态：轨道用中等灰度的前景色半透明合成，明暗主题下都能看清。
    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f),
    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    // 禁用态：明显更淡、更虚，不会和「关着的开关」混淆。
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
    disabledCheckedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
)

private fun scaledTypography(scale: Float): Typography {
    val base = Typography()
    fun TextStyle.scaled() = copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
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
