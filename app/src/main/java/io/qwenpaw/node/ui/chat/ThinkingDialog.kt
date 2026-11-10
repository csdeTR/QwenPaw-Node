package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.qwenpaw.node.data.model.ThinkingControlDto
import io.qwenpaw.node.data.model.ThinkingValueDto
import io.qwenpaw.node.ui.theme.QwenIcons
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 思考强度对话框。
 *
 * 两种模型统一用一条滑块，与 qwenpaw 官方 Web 端一致：
 *
 * - 离散档位（`kind = "effort"`）：滑块按 `efforts` 逐个停靠，松手才提交。
 * - 数值预算（`kind = "budget"`）：滑块连续，读数是 token 数；支持关闭时最左一段是
 *   「关闭思考」，判据与官方一致（关闭带与 `budget_min` 的中点）。
 *
 * 拖动过程只更新本地显示，`onValueChangeFinished` 才发请求，避免每一帧都打一次
 * `PUT /api/chats/{id}/thinking`。
 */
@Composable
internal fun ThinkingDialog(
    control: ThinkingControlDto,
    value: ThinkingValueDto?,
    effective: ThinkingValueDto?,
    onSet: (level: String, budgetTokens: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scale = remember(control) { ThinkingScale.of(control) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "思考强度",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QwenIcons.close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (scale == null) {
                Text(
                    text = "当前模型不支持调节思考强度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
                return@Column
            }

            if (scale.isEffort) {
                EffortSlider(scale, value, effective, onSet)
            } else {
                BudgetSlider(scale, value, effective, onSet)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onSet("inherit", null) }) {
                    Text("恢复默认设置")
                }
            }
        }
    }
}

@Composable
private fun EffortSlider(
    scale: ThinkingScale,
    value: ThinkingValueDto?,
    effective: ThinkingValueDto?,
    onSet: (String, Long?) -> Unit,
) {
    // 「跟随默认」时滑块停在服务端算出的实际档位，与 budget 侧同一处理；
    // 否则一打开就停在最右一格，看起来像已经设成了最高档。
    val shown = value?.takeIf { it.level.isNotBlank() && it.level != "inherit" } ?: effective
    var index by remember(scale, shown?.level) {
        mutableIntStateOf(scale.indexOf(shown?.level))
    }
    val level = scale.stops.getOrElse(index) { "" }
    Text(
        text = thinkingLevelLabel(level),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
    )
    Slider(
        value = index.toFloat(),
        onValueChange = { index = it.roundToInt() },
        valueRange = 0f..scale.stops.lastIndex.toFloat(),
        steps = (scale.stops.size - 2).coerceAtLeast(0),
        onValueChangeFinished = {
            // 必须在这里读 index，不能读组合期算好的 level：快速拖到目标档再松手时，
            // 松手回调可能早于这一帧的重组，闭包里的 level 还是上一档，
            // 请求就会按调整前的强度发出去。
            onSet(scale.stops.getOrElse(index) { "" }, null)
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Text(
        text = "仅显示当前模型支持的档位。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

@Composable
private fun BudgetSlider(
    scale: ThinkingScale,
    value: ThinkingValueDto?,
    effective: ThinkingValueDto?,
    onSet: (String, Long?) -> Unit,
) {
    var tokens by remember(scale, value, effective) {
        mutableFloatStateOf(scale.startTokens(value, effective).toFloat())
    }
    val current = tokens.roundToLong()
    val off = scale.supportsOff && tokens < scale.offThreshold
    Text(
        text = if (off) "关闭思考" else "${current} tokens · ${scale.budgetLabel(current)}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
    )
    Slider(
        value = tokens,
        onValueChange = { tokens = it },
        valueRange = scale.sliderMin.toFloat()..scale.budgetMax.toFloat(),
        onValueChangeFinished = {
            val v = tokens.roundToLong()
            if (scale.supportsOff && tokens < scale.offThreshold) {
                onSet("off", null)
            } else {
                onSet("budget", v.coerceIn(scale.budgetMin, scale.budgetMax))
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Text(
        text = "连续调节思考 Token 上限，文字随比例变化，不代表固定档位。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}
