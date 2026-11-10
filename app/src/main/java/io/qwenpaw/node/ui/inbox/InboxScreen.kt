package io.qwenpaw.node.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.InboxEventDto
import io.qwenpaw.node.ui.TimeLabels
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 收件箱：服务端的事件流。
 *
 * 事件来源是 cron 执行、自动记忆写入一类的系统消息，天然适合聚合成一条消息流。
 * 分页、未读计数与删除都由 `InboxViewModel` 负责，这里只管呈现。
 *
 * `severity` 只映射成左侧色条，不做文案翻译：服务端会新增来源类型，
 * 把严重度写成中文反而会锁死取值域。
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMarkRead: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissError: () -> Unit,
    onSourceFilter: (String?) -> Unit,
    onAgentFilter: (String?) -> Unit,
    onLoadAgents: () -> Unit,
) {
    var deleting by remember { mutableStateOf<InboxEventDto?>(null) }
    var picking by remember { mutableStateOf<InboxFilterKind?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { onLoadAgents() }

    // 触底加载下一页：提前两屏开始取，滚动时不易看到空白。
    LaunchedEffect(listState, state.events.size, state.hasMore) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            if (state.hasMore && last >= state.events.size - 3) onLoadMore()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
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
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "收件箱",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${state.unreadCount} 条未读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.unreadCount > 0 && !state.filtered) {
                        Text(
                            text = "全部已读",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onMarkAllRead)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                InboxFilterRow(
                    sourceFilter = state.sourceFilter,
                    agentFilter = state.agentFilter,
                    agentLabel = state.agentOptions
                        .firstOrNull { it.id == state.agentFilter }
                        ?.let { it.name.ifBlank { it.id } }
                        ?: state.agentFilter,
                    onPickSource = { picking = InboxFilterKind.Source },
                    onPickAgent = { picking = InboxFilterKind.Agent },
                    onClear = {
                        onSourceFilter(null)
                        onAgentFilter(null)
                    },
                )
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
                state.loading && state.events.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.events.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = when {
                            !state.loaded -> "正在加载"
                            state.filtered -> "没有符合条件的记录"
                            else -> "收件箱是空的"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.loaded && !state.filtered) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "定时任务与记忆写入的结果会出现在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.events, key = { it.id }) { event ->
                        InboxEventRow(
                            event = event,
                            onClick = { onMarkRead(event.id) },
                            onLongClick = { deleting = event },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }

            state.error?.let { message ->
                ErrorStrip(message = message, onDismiss = onDismissError)
            }
        }
    }

    deleting?.let { event ->
        DestructiveActionDialog(
            title = "删除事件",
            message = "删除后无法恢复，确定要删除这条事件吗？",
            impacts = listOf("「${event.title}」这条记录", "该事件关联的运行轨迹（若无其它引用）"),
            confirmLabel = "删除",
            undoable = false,
            onConfirm = {
                onDelete(event.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    when (picking) {
        InboxFilterKind.Source -> InboxFilterDialog(
            title = "筛选来源",
            options = listOf(InboxFilterOption(null, "全部")) +
                INBOX_SOURCE_FILTERS.map { InboxFilterOption(it, inboxSourceLabel(it)) },
            selected = state.sourceFilter,
            onSelect = {
                picking = null
                onSourceFilter(it)
            },
            onDismiss = { picking = null },
        )

        InboxFilterKind.Agent -> InboxFilterDialog(
            title = "筛选智能体",
            options = listOf(InboxFilterOption(null, "全部")) +
                state.agentOptions.map { InboxFilterOption(it.id, it.name.ifBlank { it.id }) },
            selected = state.agentFilter,
            onSelect = {
                picking = null
                onAgentFilter(it)
            },
            onDismiss = { picking = null },
            emptyHint = if (state.agentOptions.isEmpty()) "未能读取智能体列表" else null,
        )

        null -> Unit
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InboxEventRow(
    event: InboxEventDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // 未读用色条表示，已读把色条压淡：同一行里不额外占一行文字。
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (event.read) MaterialTheme.colorScheme.outlineVariant
                    else severityColor(event.severity),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.title.ifBlank { event.eventType },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (event.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TimeLabels.epochStamp(event.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = event.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(inboxSourceLabel(event.sourceType))
                    if (event.status.isNotBlank()) append("，").append(event.status)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 严重度只影响色条，不做文字映射。未知取值退回次要色。 */
@Composable
private fun severityColor(severity: String) = when (severity) {
    "error", "critical", "warning" -> MaterialTheme.colorScheme.error
    "success" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.secondary
}

/** 收件箱的两个筛选维度，决定打开哪个对话框。 */
private enum class InboxFilterKind { Source, Agent }

/** 对话框选项：`value` 为 null 表示该维度的「全部」。 */
private data class InboxFilterOption(val value: String?, val label: String)

/**
 * 筛选条：来源与智能体各一个下拉入口，右侧在有筛选时出现「清除」。
 *
 * 两个维度都收进对话框：来源含「全部」共六项，平铺成 Chip 在 411dp 上放不下，
 * 而且与智能体按钮同排时看不出它们属于两个维度。
 */
@Composable
private fun InboxFilterRow(
    sourceFilter: String?,
    agentFilter: String?,
    agentLabel: String?,
    onPickSource: () -> Unit,
    onPickAgent: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧一组占满剩余宽度，智能体名过长时被压缩到省略号，不把「清除」挤出屏幕。
        Row(modifier = Modifier.weight(1f)) {
            FilterButton(
                text = "来源 · " + sourceFilterLabel(sourceFilter),
                onClick = onPickSource,
            )
            Spacer(Modifier.width(12.dp))
            FilterButton(
                text = "智能体 · " + (agentLabel ?: "全部"),
                onClick = onPickAgent,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (sourceFilter != null || agentFilter != null) {
            Text(
                text = "清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** 无边框筛选按钮：维度与当前值加一个下拉箭头，点击打开选择对话框。 */
@Composable
private fun FilterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = QwenIcons.chevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * 来源与智能体共用的选择对话框。
 *
 * 首项固定是「全部」，用于清除单个维度；选中项右侧打勾。智能体列表可能很长，
 * 因此限制最大高度并在内部纵向滚动，不把对话框撑出屏幕。
 */
@Composable
private fun InboxFilterDialog(
    title: String,
    options: List<InboxFilterOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    emptyHint: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    InboxFilterOptionRow(
                        label = option.label,
                        selected = selected == option.value,
                        onClick = { onSelect(option.value) },
                    )
                }
                emptyHint?.let { hint ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun InboxFilterOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = QwenIcons.check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}