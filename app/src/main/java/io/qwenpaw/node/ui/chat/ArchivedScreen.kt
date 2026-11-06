package io.qwenpaw.node.ui.chat

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.ui.theme.QwenIcons

/** 已归档对话列表：可恢复或删除，支持多选批量恢复。 */
@Composable
fun ArchivedScreen(
    chats: List<ChatSpecDto>,
    onBack: () -> Unit,
    onUnarchive: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** 多选恢复。服务端会跳过无法恢复的项，提示由调用方负责。 */
    onBatchUnarchive: (List<String>) -> Unit = {},
    /** 多选删除。服务端没有回收站，调用方需自行做二次确认。 */
    onBatchDelete: (List<String>) -> Unit = {},
) {
    var deleting by remember { mutableStateOf<ChatSpecDto?>(null) }
    // 多选模式。与对话列表的多选形态一致：右上角「选择」进入，
    // 底部出现操作条，退出时清空选中。
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }

    // 列表变化（例如恢复成功）后，已不存在的 id 要从选中集合里剔掉，
    // 否则底部会显示「已选 2 个」而列表里只剩 1 行。
    val live = chats.map { it.id }.toSet()
    LaunchedEffect(live) {
        if (selected.any { it !in live }) selected = selected.intersect(live)
    }

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
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "已归档",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selecting) "已选 ${selected.size} 个" else "${chats.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (chats.isNotEmpty()) {
                        Text(
                            text = if (selecting) "取消" else "选择",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selecting = !selecting
                                    if (!selecting) selected = emptySet()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
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
            if (chats.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "还没有归档的对话",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "在对话列表中长按对话即可归档",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(chats, key = { it.id }) { chat ->
                            ArchivedRow(
                                chat = chat,
                                selecting = selecting,
                                checked = chat.id in selected,
                                onToggle = {
                                    selected = if (chat.id in selected) {
                                        selected - chat.id
                                    } else {
                                        selected + chat.id
                                    }
                                },
                                onUnarchive = { onUnarchive(chat.id) },
                                onDelete = { deleting = chat },
                            )
                        }
                    }
                    if (selecting) {
                        BatchActionBar(
                            count = selected.size,
                            allSelected = selected.size == chats.size,
                            onToggleAll = {
                                selected = if (selected.size == chats.size) {
                                    emptySet()
                                } else {
                                    chats.map { it.id }.toSet()
                                }
                            },
                            onRestore = {
                                onBatchUnarchive(selected.toList())
                                selected = emptySet()
                                selecting = false
                            },
                            onDelete = { batchDeleteConfirm = true },
                        )
                    }
                }
            }
        }
    }

    deleting?.let { chat ->
        io.qwenpaw.node.ui.common.DestructiveActionDialog(
            title = "删除对话",
            message = "删除后无法恢复，确定要删除「${chat.name}」吗？",
            impacts = listOf(
                "该对话的全部消息记录",
                "与这些消息一同保存的图片与文件引用",
                "本机为该对话缓存的离线历史",
            ),
            confirmLabel = "删除",
            undoable = false,
            onConfirm = {
                onDelete(chat.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    if (batchDeleteConfirm) {
        io.qwenpaw.node.ui.common.DestructiveActionDialog(
            title = "删除 ${selected.size} 个对话",
            message = "删除后无法恢复。",
            impacts = listOf(
                "这 ${selected.size} 个对话的全部消息记录",
                "与这些消息一同保存的图片与文件引用",
                "本机为它们缓存的离线历史",
            ),
            confirmLabel = "删除",
            undoable = false,
            onConfirm = {
                onBatchDelete(selected.toList())
                selected = emptySet()
                selecting = false
                batchDeleteConfirm = false
            },
            onDismiss = { batchDeleteConfirm = false },
        )
    }
}

/** 多选模式下的底部操作条。形态与对话列表的批量条一致。 */
@Composable
private fun BatchActionBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "选择对话" else "已选 $count 个",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleAll) { Text(if (allSelected) "取消全选" else "全选") }
        TextButton(enabled = count > 0, onClick = onRestore) { Text("恢复") }
        TextButton(
            enabled = count > 0,
            onClick = onDelete,
        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ArchivedRow(
    chat: ChatSpecDto,
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // 多选模式下整行可点：只让复选框可点会让点击目标小得难用。
            .then(if (selecting) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.name.ifBlank { "未命名对话" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = timeLabel(chat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 多选模式下隐藏单行按钮：批量操作条已经覆盖同样的动作，
            // 两套入口并存时用户很容易点错对象。
            if (!selecting) {
                TextButton(onClick = onUnarchive) { Text("恢复") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
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