package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 块级公式渲染。
 *
 * 用 Row / Column 组合而不是自绘 `Layout`：分式、根式、上下标都是
 * 「上下叠放 + 居中」这一种结构，组合式写法不需要推算基线偏移，
 * 也不会在字号缩放变化时算出与实际测量不一致的尺寸。
 *
 * 嵌套时逐层缩小字号，与数学排版里的 script style / scriptscript style 对应，
 * 同时保证三层分式在手机宽度内仍然读得清。
 */
@Composable
fun LatexView(
    source: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val scale = io.qwenpaw.node.ui.theme.LocalChatFontScale.current
    val node = remember(source) { LatexParser.parse(source) }
    if (node == null) {
        // 解析失败退回源码：能复制、能交给别的工具渲染，比一个错误的公式好。
        LatexFallback(source, color, modifier)
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.CenterStart,
    ) {
        MathNodeView(node, baseSize = 16f * scale, color = color)
    }
}

/** 解析失败或流式输出中公式未闭合时的展示：等宽源码 + 一句说明。 */
@Composable
private fun LatexFallback(source: String, color: Color, modifier: Modifier) {
    val scale = io.qwenpaw.node.ui.theme.LocalChatFontScale.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
    ) {
        Text(
            text = source,
            fontFamily = FontFamily.Monospace,
            fontSize = (13 * scale).sp,
            color = color,
        )
        Text(
            text = "该公式用到了暂不支持的写法，以上为原始内容",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 递归渲染一个节点。
 *
 * [baseSize] 是该层正文字号；分式的分子分母按 0.72 缩小，上下标按 0.58 缩小，
 * 与常见数学字体的 script 比例接近。
 */
@Composable
private fun MathNodeView(node: MathNode, baseSize: Float, color: Color) {
    when (node) {
        is MathNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { MathNodeView(it, baseSize, color) }
        }

        is MathNode.Sym -> Text(text = node.text, fontSize = baseSize.sp, color = color)

        is MathNode.Text -> Text(text = node.text, fontSize = baseSize.sp, color = color)

        is MathNode.Fraction -> FractionView(node.numerator, node.denominator, baseSize, color)

        is MathNode.Radical -> Row(verticalAlignment = Alignment.CenterVertically) {
            // 根指数排在根号左上方，字号再小一档。
            node.index?.let { index ->
                Column {
                    MathNodeView(index, baseSize * 0.58f, color)
                    Spacer(Modifier.height(2.dp))
                }
            }
            Text("√", fontSize = (baseSize * 1.15f).sp, color = color)
            RadicalBody(node.body, baseSize, color)
        }

        is MathNode.Script -> Row(verticalAlignment = Alignment.CenterVertically) {
            MathNodeView(node.base, baseSize, color)
            Column {
                node.superscript?.let { sup ->
                    Column {
                        MathNodeView(sup, baseSize * 0.58f, color)
                        Spacer(Modifier.height(2.dp))
                    }
                }
                node.subscript?.let { sub -> MathNodeView(sub, baseSize * 0.58f, color) }
            }
        }

        is MathNode.BigOp -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            node.superscript?.let { MathNodeView(it, baseSize * 0.58f, color) }
            Text(node.op, fontSize = (baseSize * 1.3f).sp, color = color)
            node.subscript?.let { MathNodeView(it, baseSize * 0.58f, color) }
        }

        is MathNode.Delim -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // 定界符按整体高度放大，否则包住分式时括号只有半边高。
            if (node.left.isNotEmpty()) {
                Text(node.left, fontSize = (baseSize * 1.4f).sp, color = color)
            }
            MathNodeView(node.body, baseSize, color)
            if (node.right.isNotEmpty()) {
                Text(node.right, fontSize = (baseSize * 1.4f).sp, color = color)
            }
        }

        is MathNode.Env -> EnvView(node, baseSize, color)

        // 重音符号排在正上方。组合附加符（如 U+20D7）在西文字体里普遍缺字形，
        // 用一个小字号的字形叠在正文上方，至少在所有设备上都能显示。
        is MathNode.Accent -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(node.mark, fontSize = (baseSize * 0.9f).sp, color = color)
            MathNodeView(node.body, baseSize, color)
        }
    }
}

/** 多行环境两侧的定界符，用 Canvas 画：字符括号的高度跟不上内容行数。 */
private enum class EnvDelimiter { BRACE, PAREN, BRACKET, BAR }

/**
 * 多行环境排版。
 *
 * 外层用 [SubcomposeLayout] 而不是 Row：定界符的高度必须等于整列的实测高度，
 * 先测量中间的内容再画两侧，才能让括号随行数长大；用字形缩放只能靠字号猜。
 */
@Composable
private fun EnvView(node: MathNode.Env, baseSize: Float, color: Color) {
    val delimiter = when (node.kind) {
        EnvKind.CASES -> EnvDelimiter.BRACE
        EnvKind.PMATRIX -> EnvDelimiter.PAREN
        EnvKind.BMATRIX -> EnvDelimiter.BRACKET
        EnvKind.VMATRIX -> EnvDelimiter.BAR
        EnvKind.ALIGNED, EnvKind.MATRIX, EnvKind.ARRAY -> null
    }
    // cases 只在左侧画大括号，其余定界符成对出现。
    val bothSides = node.kind != EnvKind.CASES
    val gap: Dp = when (node.kind) {
        EnvKind.CASES -> 16.dp
        EnvKind.MATRIX, EnvKind.ALIGNED, EnvKind.ARRAY -> 14.dp
        else -> 12.dp
    }
    val cellCentered = node.kind == EnvKind.MATRIX || node.kind == EnvKind.PMATRIX ||
        node.kind == EnvKind.BMATRIX || node.kind == EnvKind.VMATRIX

    SubcomposeLayout { constraints ->
        val sideWidth = if (delimiter == null) 0 else with(density) { 12.dp.roundToPx() }
        val reserved = sideWidth * if (bothSides) 2 else 1
        // 宽度无上界时不能再减定界符占位，见 [envContentMaxWidth] 的说明。
        val contentMaxWidth = envContentMaxWidth(constraints.maxWidth, reserved)
        val content = subcompose("env-content") {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = if (cellCentered) {
                    Alignment.CenterHorizontally
                } else {
                    Alignment.Start
                },
            ) {
                node.rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { cell -> MathNodeView(cell, baseSize, color) }
                    }
                }
            }
        }.first().measure(Constraints(maxWidth = contentMaxWidth))

        val sideHeight = content.height
        val left = delimiter?.let { style ->
            subcompose("env-left") { EnvSide(style, sideHeight, mirrored = false, color) }
                .first()
                .measure(Constraints())
        }
        val right = if (delimiter != null && bothSides) {
            subcompose("env-right") { EnvSide(delimiter, sideHeight, mirrored = true, color) }
                .first()
                .measure(Constraints())
        } else {
            null
        }

        val totalWidth = content.width + (left?.width ?: 0) + (right?.width ?: 0)
        val totalHeight = maxOf(content.height, left?.height ?: 0)
        layout(totalWidth, totalHeight) {
            var x = 0
            if (left != null) {
                left.place(x, (totalHeight - left.height) / 2)
                x += left.width
            }
            content.place(x, (totalHeight - content.height) / 2)
            x += content.width
            right?.place(x, (totalHeight - right.height) / 2)
        }
    }
}

/**
 * 多行环境内容可用的最大宽度。
 *
 * Compose 的 `Constraints` 只接受 `Constraints.Infinity` 本身作为无上界值：
 * 传 `Infinity - 1` 之类的值会抛
 * `IllegalArgumentException: Can't represent a width of … and height of … in Constraints`。
 * 块级公式放在横向滚动容器里，宽度正好是无上界，因此这里遇到无上界必须原样返回，
 * 不能再减去两侧定界符的占位——早先的写法让每一条 `cases` / `matrix` 公式
 * 都把应用直接打崩（实测栈顶在 `LatexView.kt` 的 `EnvView` 测量块）。
 */
internal fun envContentMaxWidth(maxWidth: Int, reserved: Int): Int =
    if (maxWidth == Constraints.Infinity) Constraints.Infinity
    else (maxWidth - reserved).coerceAtLeast(0)

/** 一侧的定界符。[mirrored] 为 true 时画右半边（`)`、`]`），供成对定界符使用。 */
@Composable
private fun EnvSide(style: EnvDelimiter, heightPx: Int, mirrored: Boolean, color: Color) {
    val heightDp = with(LocalDensity.current) { heightPx.coerceAtLeast(1).toDp() }
    Canvas(Modifier.width(12.dp).height(heightDp)) {
        drawEnvDelimiter(style, mirrored, color)
    }
}

private fun DrawScope.drawEnvDelimiter(style: EnvDelimiter, mirrored: Boolean, color: Color) {
    val strokeWidth = 1.5.dp.toPx()
    val cap = StrokeCap.Round
    // 线宽的一半留在画布内，否则弧顶会被裁掉半个像素。
    val left = strokeWidth / 2f
    val right = size.width - strokeWidth / 2f
    val top = strokeWidth / 2f
    val bottom = size.height - strokeWidth / 2f
    val midY = size.height / 2f
    when (style) {
        EnvDelimiter.BRACE -> {
            val arm = left + (right - left) * 0.45f
            val path = Path().apply {
                moveTo(right, top)
                quadraticTo(arm, top, arm, top + (bottom - top) * 0.22f)
                lineTo(arm, midY - (bottom - top) * 0.04f)
                quadraticTo(arm, midY, left, midY)
                quadraticTo(arm, midY, arm, midY + (bottom - top) * 0.04f)
                lineTo(arm, bottom - (bottom - top) * 0.22f)
                quadraticTo(arm, bottom, right, bottom)
            }
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = cap))
        }

        EnvDelimiter.PAREN -> {
            val arc = Stroke(width = strokeWidth, cap = cap)
            if (!mirrored) {
                // 90°→270° 顺时针经过左端，得到左半侧的圆弧。
                drawArc(
                    color = color,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(left, top),
                    size = Size((right - left) * 2f, bottom - top),
                    style = arc,
                )
            } else {
                drawArc(
                    color = color,
                    startAngle = 270f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(2f * left - right, top),
                    size = Size((right - left) * 2f, bottom - top),
                    style = arc,
                )
            }
        }

        EnvDelimiter.BRACKET -> {
            val edge = if (mirrored) right else left
            drawLine(color, Offset(left, top), Offset(right, top), strokeWidth, cap)
            drawLine(color, Offset(edge, top), Offset(edge, bottom), strokeWidth, cap)
            drawLine(color, Offset(left, bottom), Offset(right, bottom), strokeWidth, cap)
        }

        EnvDelimiter.BAR -> drawLine(
            color,
            Offset(size.width / 2f, top),
            Offset(size.width / 2f, bottom),
            strokeWidth,
            cap,
        )
    }
}

/**
 * 分式：先分别量出分子与分母，再按较宽的一侧铺一条横线，两块居中叠放。
 *
 * 不用 `Modifier.width(IntrinsicSize.Max)` 加 `fillMaxWidth`：块级公式处在横向
 * 滚动容器里，宽度无上界，`fillMaxWidth` 拿到的上限是 `Constraints.Infinity`，
 * 画出来等于没有线；而且固有宽度查询在子项是多行环境（`SubcomposeLayout`）时
 * 会直接抛异常。
 */
@Composable
private fun FractionView(
    numerator: MathNode,
    denominator: MathNode,
    baseSize: Float,
    color: Color,
) {
    SubcomposeLayout { constraints ->
        val child = Constraints(maxWidth = constraints.maxWidth)
        val num = subcompose("frac-num") { MathNodeView(numerator, baseSize * 0.72f, color) }
            .first()
            .measure(child)
        val den = subcompose("frac-den") { MathNodeView(denominator, baseSize * 0.72f, color) }
            .first()
            .measure(child)
        val gap = with(density) { 3.dp.roundToPx() }
        val barHeight = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)
        val width = maxOf(num.width, den.width) + with(density) { 8.dp.roundToPx() }
        val bar = subcompose("frac-bar") { RuleBox(color) }
            .first()
            .measure(Constraints.fixed(width, barHeight))
        val height = num.height + gap + barHeight + gap + den.height
        layout(width, height) {
            num.place((width - num.width) / 2, 0)
            bar.place(0, num.height + gap)
            den.place((width - den.width) / 2, num.height + gap + barHeight + gap)
        }
    }
}

/** 根号下方的被开方数：上方一条横线代替根号的长尾巴，宽度与内容一致。 */
@Composable
private fun RadicalBody(body: MathNode, baseSize: Float, color: Color) {
    SubcomposeLayout { constraints ->
        val content = subcompose("rad-body") { MathNodeView(body, baseSize, color) }
            .first()
            .measure(Constraints(maxWidth = constraints.maxWidth))
        val barHeight = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)
        val width = content.width + with(density) { 2.dp.roundToPx() }
        val bar = subcompose("rad-bar") { RuleBox(color) }
            .first()
            .measure(Constraints.fixed(width, barHeight))
        layout(width, barHeight + content.height) {
            bar.place(0, 0)
            content.place(0, barHeight)
        }
    }
}

/** 一条 1dp 的横线。尺寸由调用方给出的约束决定。 */
@Composable
private fun RuleBox(color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.7f)))
}