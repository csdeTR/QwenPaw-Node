package io.qwenpaw.node.ui.stats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import io.qwenpaw.node.data.model.LlmToolDailyDto
import io.qwenpaw.node.ui.chat.formatTokens

/**
 * 调用趋势折线：LLM 调用与工具调用两条线。
 *
 * 两条线同量级，只给颜色区分、不做显隐交互：加开关会让用户以为「少一条线」是数据缺失。
 * 空数据不组合图表——Vico 的空模型会画出一块空白坐标系，看起来像加载失败。
 */
@Composable
fun UsageChart(trend: List<LlmToolDailyDto>, modifier: Modifier = Modifier) {
    if (trend.isEmpty()) return
    val producer = remember { CartesianChartModelProducer() }
    val dates = remember(trend) { trend.map { it.date } }
    val llmColor = MaterialTheme.colorScheme.primary
    // 第二条线用中性灰而不是 tertiary：主题的 tertiary 是 Warning 的琥珀色（#FAAD14），
    // 与主色橙（#FF7F16）只差一点色相，2dp 细线在深色背景上几乎分不开。
    val toolColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val labelSize = MaterialTheme.typography.labelSmall.fontSize

    LaunchedEffect(trend) {
        producer.runTransaction {
            // 两条序列必须写在**同一个** lineSeries 块里：分两次调用会得到两个
            // Partial，图层只画出第一条，第二条静默丢失（实测图上只剩 LLM 一条线）。
            lineSeries {
                series(trend.map { it.agentLlmCalls })
                series(trend.map { it.toolCalls })
            }
        }
    }

    // 横轴把索引换回日期；纵轴用与其它用量区块一致的 k / M 写法。
    //
    // Vico 对轴格式化器有一条硬约束：返回空串会抛 IllegalStateException
    // （它要求用 ItemPlacer 控制刻度，而不是让格式化器返回空）。
    // 它偶尔会问到区间外的 x（例如浮点误差落到 lastIndex 之后），
    // 因此这里把索引夹到有效范围，而不是「查不到就返回空」。
    val bottomFormatter = remember(dates) {
        CartesianValueFormatter { _, value, _ ->
            val index = value.toInt().coerceIn(0, dates.lastIndex.coerceAtLeast(0))
            dates.getOrNull(index)?.let { DateLabels.short(it) }.orEmpty().ifEmpty { "-" }
        }
    }
    // 纵轴：大数走 k / M，小整数原样；区间上限低于 1 时 Vico 会给 0.2 / 0.4 这类刻度，
    // 直接 toLong() 会得到五个「0」，看上去像坐标轴坏了。
    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ ->
            when {
                value >= 1000 || value <= -1000 -> formatTokens(value.toLong())
                value == value.toInt().toDouble() -> value.toInt().toString()
                else -> "%.1f".format(value)
            }
        }
    }

    val theme = rememberM3VicoTheme(
        lineCartesianLayerColors = listOf(llmColor, toolColor),
        lineColor = MaterialTheme.colorScheme.outlineVariant,
        textColor = axisText,
    )

    ProvideVicoTheme(theme) {
        val chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(fill(llmColor))),
                    LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(fill(toolColor))),
                ),
            ),
            startAxis = VerticalAxis.rememberStart(
                valueFormatter = startFormatter,
                label = rememberTextComponent(color = axisText, textSize = labelSize),
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = bottomFormatter,
                label = rememberTextComponent(color = axisText, textSize = labelSize),
            ),
            marker = rememberDefaultCartesianMarker(
                label = rememberTextComponent(
                    color = MaterialTheme.colorScheme.onSurface,
                    textSize = labelSize,
                ),
                valueFormatter = DefaultCartesianMarker.ValueFormatter { _, targets ->
                    // 两条线在同一个图层里，因此只有一个 target，它的 points 逐条对应一个序列。
                    val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                    if (target == null) {
                        ""
                    } else {
                        buildString {
                            append(dates.getOrNull(target.x.toInt())?.let { DateLabels.short(it) }.orEmpty())
                            target.points.forEachIndexed { seriesIndex, point ->
                                append("\n")
                                append(if (seriesIndex == 0) "LLM 调用 " else "工具调用 ")
                                append(point.entry.y.toInt())
                            }
                        }
                    }
                },
            ),
        )
        CartesianChartHost(
            chart = chart,
            modelProducer = producer,
            modifier = modifier.fillMaxWidth().height(180.dp),
            // 关掉横向滚动：区间固定为 7 / 30 / 90 天，滚动只会让用户看到半张图。
            scrollState = rememberVicoScrollState(scrollEnabled = false),
        )
    }
}
