package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.ChatSpecDto

/**
 * 会话列表页。
 *
 * 顶部是大标题和总数，下面一个搜索框，再往下是会话列表，
 * 新建会话固定在最底部。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    chats: List<ChatSpecDto>,
    loading: Boolean,
    compact: Boolean = false,
    previews: Map<String, String> = emptyMap(),
    archivedCount: Int = 0,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit = {},
    onArchive: (String) -> Unit = {},
    onCopyAll: (String) -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSettings: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ChatSpecDto?>(null) }
    var deleting by remember { mutableStateOf<ChatSpecDto?>(null) }
    var archiving by remember { mutableStateOf<ChatSpecDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    val filtered = remember(chats, query) {
        val q = query.trim()
        if (q.isEmpty()) chats
        else chats.filter { it.name.contains(q, ignoreCase = true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ListHeader(
                count = chats.size,
                query = query,
                onQueryChange = { query = it },
                onOpenSettings = onOpenSettings,
                onOpenArchived = onOpenArchived,
                onOpenTokenUsage = onOpenTokenUsage,
                archivedCount = archivedCount,
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onCreate)
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "新建会话",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && chats.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                filtered.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (query.isBlank()) "还没有会话" else "没有匹配的会话",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (query.isBlank()) "点下面的按钮新建一个" else "换个关键词试试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            compact = compact,
                            preview = previews[chat.id],
                            onClick = { onOpen(chat.id) },
                            onRename = {
                                renameText = chat.name
                                renaming = chat
                            },
                            onDelete = { deleting = chat },
                            onTogglePin = { onTogglePin(chat.id) },
                            onArchive = { archiving = chat },
                            onCopyAll = { onCopyAll(chat.id) },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { chat ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(chat.id, renameText.trim())
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除会话") },
            text = { Text("删除后无法恢复，确定要删除「${chat.name}」吗") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(chat.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    archiving?.let { chat ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text("归档会话") },
            text = { Text("归档后可在「已归档」里找回，确定归档「${chat.name}」吗") },
            confirmButton = {
                TextButton(onClick = {
                    onArchive(chat.id)
                    archiving = null
                }) { Text("归档") }
            },
            dismissButton = {
                TextButton(onClick = { archiving = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ListHeader(
    count: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    archivedCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "会话",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (archivedCount > 0) {
                IconButtonCell(
                    icon = Icons.Default.Inventory2,
                    description = "已归档 $archivedCount 个",
                    onClick = onOpenArchived,
                )
            }
            IconButtonCell(
                icon = Icons.Default.BarChart,
                description = "用量统计",
                onClick = onOpenTokenUsage,
            )
            IconButtonCell(
                icon = Icons.Default.Settings,
                description = "设置",
                onClick = onOpenSettings,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索会话名称",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空搜索",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatSpecDto,
    compact: Boolean,
    preview: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onCopyAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 时间文案只在时间戳变化时重算，避免每次重组都解析日期。
    val time = remember(chat.updatedAt, chat.lastFinishedAt, chat.createdAt) {
        timeLabel(chat)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menu = true },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 9.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (chat.status == "running") {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .border(
                            1.dp,
                            if (chat.status == "running") {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (chat.status == "running") {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = chat.name.ifBlank { "未命名会话" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 预览文案：紧凑模式也保留，用户扫一眼就能认出会话内容。
                if (!preview.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val running = chat.status == "running"
                if (!compact && running) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusTag("运行中", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        ChatRowMenu(
            expanded = menu,
            pinned = chat.pinned,
            onDismiss = { menu = false },
            onRename = {
                menu = false
                onRename()
            },
            onDelete = {
                menu = false
                onDelete()
            },
            onTogglePin = {
                menu = false
                onTogglePin()
            },
            onArchive = {
                menu = false
                onArchive()
            },
            onCopyAll = {
                menu = false
                onCopyAll()
            },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 14.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun IconButtonCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun ChatRowMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onCopyAll: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("重命名") },
            onClick = onRename,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (pinned) "取消置顶" else "置顶") },
            onClick = onTogglePin,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("复制全部内容") },
            onClick = onCopyAll,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("归档") },
            onClick = onArchive,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("删除") },
            onClick = onDelete,
        )
    }
}

/** 服务端给的是 ISO 时间，列表里只显示到时和分，跨天时补上日期。 */
internal fun timeLabel(chat: ChatSpecDto): String {
    val raw = chat.updatedAt ?: chat.lastFinishedAt ?: chat.createdAt ?: return ""
    val datePart = raw.take(10)
    val time = raw.substringAfter('T', "").take(5)
    if (datePart.length != 10 || time.isBlank()) return ""
    // 用 LocalDate 直接解析，避免每次都新建 SimpleDateFormat 和 Calendar。
    val date = runCatching { java.time.LocalDate.parse(datePart) }.getOrNull() ?: return time
    val today = java.time.LocalDate.now()
    val diffDays = java.time.temporal.ChronoUnit.DAYS.between(date, today).toInt()
    return when {
        diffDays == 0 -> time
        diffDays == 1 -> "昨天 $time"
        diffDays in 2..6 -> {
            val weekday = date.dayOfWeek.value // 1=周一 .. 7=周日
            "周" + "一二三四五六日"[weekday - 1] + " " + time
        }
        else -> datePart.substring(5) + " " + time
    }
}
