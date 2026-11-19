package io.qwenpaw.node.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.chat.formatTokens
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 用量统计页：区间概览、当前智能体、调用趋势与按模型明细。
 *
 * 四块的统计口径不同，标题必须分别写明「全服务端」与「当前智能体」：
 * `/api/agent-stats` 按 `X-Agent-Id` 过滤，另外三个端点是全局。
 * 不写范围会让用户把「当前智能体 12 次工具调用」当成「全服务端 340 次」。
 */
@Composable
fun UsageScreen(
    state: UsageUiState,
    onBack: () -> Unit,
    onRange: (UsageRange) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    // 自定义顶栏自行让开状态栏，否则会被通知栏压住。
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = QwenIcons.arrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.stats_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                // 首屏还在拉数据时给一个转圈；之后即使某一块失败也照常渲染其余三块。
                state.loading && !state.loaded -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.allFailed -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.summaryError ?: stringResource(R.string.stats_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.stats_tap_to_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RangeRow(current = state.range, onRange = onRange)

                    SectionCard(
                        title = stringResource(R.string.stats_range_summary_title),
                        subtitle = stringResource(R.string.stats_scope_server),
                    ) {
                        val summary = state.summary
                        if (summary == null) {
                            BlockError(state.summaryError, onRetry)
                        } else {
                            MetricRow(
                                stringResource(R.string.stats_metric_total_calls),
                                summary.totalCalls.toString(),
                            )
                            MetricRow(
                                stringResource(R.string.stats_metric_prompt_tokens),
                                formatTokens(summary.totalPromptTokens),
                            )
                            MetricRow(
                                stringResource(R.string.stats_metric_completion_tokens),
                                formatTokens(summary.totalCompletionTokens),
                            )
                            // 没有调用时服务端给的是 null，不是 0。写成「0.0%」会把「还没测过」
                            // 说成「命中率为零」，两者对用户的含义完全不同。
                            MetricRow(
                                stringResource(R.string.stats_metric_cache_hit_rate),
                                summary.cacheHitRate?.let { "%.1f%%".format(it) }
                                    ?: stringResource(R.string.stats_no_data),
                            )
                        }
                    }

                    SectionCard(
                        title = stringResource(R.string.stats_agent_title),
                        subtitle = stringResource(R.string.stats_scope_agent),
                    ) {
                        val agent = state.agent
                        when {
                            agent == null -> BlockError(state.agentError, onRetry)
                            agent.totalActiveSessions == 0 && agent.totalMessages == 0 &&
                                agent.totalLlmCalls == 0 && agent.totalToolCalls == 0 ->
                                EmptyHint(stringResource(R.string.stats_agent_empty))
                            else -> {
                                MetricRow(
                                    stringResource(R.string.stats_metric_active_sessions),
                                    agent.totalActiveSessions.toString(),
                                )
                                MetricRow(
                                    stringResource(R.string.stats_metric_messages),
                                    agent.totalMessages.toString(),
                                )
                                MetricRow(
                                    stringResource(R.string.stats_metric_llm_calls),
                                    agent.totalLlmCalls.toString(),
                                )
                                MetricRow(
                                    stringResource(R.string.stats_metric_tool_calls),
                                    agent.totalToolCalls.toString(),
                                )
                                MetricRow(
                                    stringResource(R.string.stats_metric_cache_hit_rate),
                                    agent.agentCacheHitRate?.let { "%.1f%%".format(it) }
                                        ?: stringResource(R.string.stats_no_data),
                                )
                            }
                        }
                    }

                    SectionCard(
                        title = stringResource(R.string.stats_trend_title),
                        subtitle = stringResource(R.string.stats_scope_server),
                    ) {
                        if (state.trendError != null && state.trend.isEmpty()) {
                            BlockError(state.trendError, onRetry)
                        } else if (state.trend.isEmpty()) {
                            EmptyHint(stringResource(R.string.stats_trend_empty))
                        } else {
                            UsageChart(state.trend)
                            Spacer(Modifier.height(8.dp))
                            Legend(
                                llmLabel = stringResource(R.string.stats_metric_llm_calls),
                                toolLabel = stringResource(R.string.stats_metric_tool_calls),
                            )
                        }
                    }

                    SectionCard(
                        title = stringResource(R.string.stats_models_title),
                        subtitle = stringResource(R.string.stats_scope_server),
                    ) {
                        if (state.modelsError != null && state.models.isEmpty()) {
                            BlockError(state.modelsError, onRetry)
                        } else if (state.models.isEmpty()) {
                            EmptyHint(stringResource(R.string.stats_trend_empty))
                        } else {
                            state.models.forEach { row ->
                                UsageLine(
                                    label = row.model.ifBlank { row.providerId },
                                    sub = pluralStringResource(
                                        R.plurals.stats_model_call_count,
                                        row.callCount,
                                        row.providerId,
                                        row.callCount,
                                    ),
                                    value = row.totalTokens,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 时间范围三档。切换即重新拉取四块数据。 */
@Composable
private fun RangeRow(current: UsageRange, onRange: (UsageRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageRange.entries.forEach { range ->
            val selected = range == current
            Text(
                text = stringResource(range.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onRange(range) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** 两条线的图例。Vico 的图例组件要单独组合，这里用一行色点更直观也更省事。 */
@Composable
private fun Legend(llmLabel: String, toolLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            text = llmLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        LegendDot(MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            text = toolLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 某一块取不到时的说明。四条端点是独立请求，不该让一块失败拖垮整页。 */
@Composable
private fun BlockError(message: String?, onRetry: () -> Unit) {
    Column {
        Text(
            text = message ?: stringResource(R.string.stats_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.stats_tap_to_retry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UsageLine(label: String, sub: String, value: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatTokens(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
