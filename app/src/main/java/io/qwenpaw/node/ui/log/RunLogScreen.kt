package io.qwenpaw.node.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.log.LogEntry
import io.qwenpaw.node.log.LogKind
import io.qwenpaw.node.ui.theme.LocalCaptionColor
import kotlinx.coroutines.flow.StateFlow

/**
 * 运行日志页。
 *
 * 这里展示的是**本机客户端**的日志，不是 QwenPaw 服务端的日志。
 * 服务端日志需要在 Web 控制台查看，客户端不提供。
 */
@Composable
fun RunLogScreen(
    entries: StateFlow<List<LogEntry>>,
    enabled: Boolean,
    kinds: Set<LogKind>,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleKind: (LogKind) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val list by entries.collectAsState()
    // 新条目自动贴底。用户往上翻时不打扰，靠「已贴底」判断。
    val listState = rememberLazyListState()
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(list.size) {
        if (stickToBottom && list.isNotEmpty()) {
            listState.scrollToItem(list.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "本机客户端日志，${list.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
            }
            SmallAction("清空", onClick = onClear)
            Spacer(Modifier.width(6.dp))
            SmallAction("导入", onClick = onImport)
            Spacer(Modifier.width(6.dp))
            SmallAction("导出", onClick = onExport)
            Spacer(Modifier.width(4.dp))
        }

        // 开关与类型过滤。放在页内而不是只放设置里，是为了让「看不到日志」
        // 时的第一反应（开关没开）能在同一屏解决。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "记录日志",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggleEnabled,
                colors = io.qwenpaw.node.ui.theme.qwenSwitchColors(),
            )
            Spacer(Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LogKind.entries.forEach { kind ->
                KindChip(
                    label = kind.label,
                    selected = kind in kinds,
                    onClick = { onToggleKind(kind) },
                )
            }
        }

        if (list.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (enabled) {
                        "暂无记录。发起一次对话后即可在此查看。"
                    } else {
                        "尚未记录日志。请打开上方的「记录日志」开关。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 用下标做 key：日志内容可能完全重复（例如同一请求连续失败），
                // 靠内容拼 key 会撞车，而 LazyColumn 对重复 key 会直接抛异常。
                itemsIndexed(list, key = { index, _ -> index }) { _, entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val isError = entry.error != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.time,
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.kind.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 顶部的小号文字按钮。用统一的描边胶囊，避免和正文混淆。 */
@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}