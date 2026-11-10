package io.qwenpaw.node.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 思考强度脑图：轮廓固定，液体按强度从底部往上填充。
 *
 * 图形取自 lucide `brain`，与官方控制台 `ThinkingIndicator` 是同一份路径数据。
 * 这里不用 `ImageVector`：`ImageVector` 只能在整张图上着色，无法按比例裁剪填充，
 * 所以直接用 [PathParser] 解析到 Compose [Path] 再自己绘制。
 */
private val BRAIN_PATHS: List<Path> by lazy {
    listOf(
        "M12 18V5",
        "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4",
        "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5",
        "M17.997 5.125a4 4 0 0 1 2.526 5.77",
        "M18 18a4 4 0 0 0 2-7.464",
        "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517",
        "M6 18a4 4 0 0 1-2-7.464",
        "M6.003 5.125a4 4 0 0 0-2.526 5.77",
    ).map { PathParser().parsePathString(it).toPath() }
}

/** 与官方控制台的 `linearGradient` 同色。 */
private val LIQUID_COLORS = listOf(
    Color(0xFFFFD1A3),
    Color(0xFFFFA348),
    Color(0xFFF57808),
)

/** 24×24 视口，与 lucide 原始坐标一致。 */
private const val VIEWPORT = 24f

/**
 * 按 [ratio]（0..1）填充的脑图，尺寸固定 18dp。
 *
 * 液体层先按 `18 - 17 * ratio` 裁剪再重绘同一条描边路径，液面不做波浪循环动画：
 * 触屏上看不出差别，却会持续占用帧。
 */
@Composable
internal fun ThinkingBrainIcon(ratio: Float, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "thinkingFill",
    )
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(18.dp)) {
        // 路径坐标都在 24 视口里，这里只把坐标缩放到实际像素。
        val factor = size.width / VIEWPORT
        // 2f 是 lucide 的默认描边，在 18dp 上约合 1.5dp。
        val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 有填充时轮廓让到半透明，液体颜色才能压过灰线；关闭状态下保持实线轮廓。
        val outlineColor = outline.copy(alpha = if (fill > 0f) 0.5f else 1f)
        // 渐变端点用视口坐标：绘制发生在缩放后的坐标系里，24 个单位正好是整张图。
        val liquid = Brush.verticalGradient(
            colors = LIQUID_COLORS,
            startY = 0f,
            endY = VIEWPORT,
        )
        withTransform({ scale(factor, factor, pivot = Offset.Zero) }) {
            BRAIN_PATHS.forEach { drawPath(it, color = outlineColor, style = stroke) }
            if (fill > 0f) {
                clipRect(top = 18f - 17f * fill, right = VIEWPORT, bottom = VIEWPORT) {
                    BRAIN_PATHS.forEach { drawPath(it, brush = liquid, style = stroke) }
                }
            }
        }
    }
}
