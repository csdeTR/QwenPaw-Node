package io.qwenpaw.node.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 工作区文件：浏览、预览与下载服务端文件。
 *
 * 三个根（工作区 / 项目目录 / 记忆）共用一套列表；记忆根是平铺的 Markdown 列表，
 * 没有子目录，也不提供下载，这些差异由 `WorkspaceViewModel` 统一处理，
 * 这里只按 `state` 呈现。
 *
 * SAF 的选择器必须由界面启动，因此两个 launcher 放在本文件内；
 * `MainActivity` 只负责导航与状态转发。
 */
@Composable
fun WorkspaceScreen(
    state: WorkspaceUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenRoot: (WorkspaceRoot) -> Unit,
    onEnter: (WorkspaceEntry) -> Unit,
    onUp: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onClosePreview: () -> Unit,
    onLoadMoreContent: () -> Unit,
    onRequestDownload: (WorkspaceEntry) -> Unit,
    onRequestDirectoryDownload: () -> Unit,
    onPlanCancel: () -> Unit,
    onStartDownload: (DownloadSink) -> Unit,
    onStartDirectoryDownload: (DirectorySink) -> Unit,
    onCancelDownload: () -> Unit,
    onDismissDownload: () -> Unit,
) {
    val context = LocalContext.current
    // 预览页需要「下载当前文件」，而预览模型只带标题与体积，因此记住打开它的条目。
    var openedEntry by remember { mutableStateOf<WorkspaceEntry?>(null) }

    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        // 用户在选择器里取消时不改计划，界面仍停在确认框上，可再次选择。
        if (uri != null) onStartDownload(SingleFileSink(context.contentResolver, uri))
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onStartDirectoryDownload(TreeSink(context, uri))
    }

    // 先注册目录级返回，再注册预览级：Compose 中后注册者优先，
    // 预览打开时返回键先关预览，而不是退回上一级目录。
    BackHandler(enabled = state.path.isNotEmpty() && state.preview == null) { onUp() }
    BackHandler(enabled = state.preview != null) {
        openedEntry = null
        onClosePreview()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WorkspaceTopBar(
                state = state,
                onBack = onBack,
                onUp = onUp,
                onRefresh = onRefresh,
                onOpenRoot = onOpenRoot,
                onRequestDirectoryDownload = onRequestDirectoryDownload,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val preview = state.preview
            when {
                preview != null -> PreviewContent(
                    preview = preview,
                    entry = openedEntry,
                    onBack = {
                        openedEntry = null
                        onClosePreview()
                    },
                    onLoadMore = onLoadMoreContent,
                    onDownload = { entry -> entry?.let(onRequestDownload) },
                )

                state.loading && state.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.entries.isEmpty() -> EmptyContent(state)

                else -> WorkspaceList(
                    state = state,
                    onEnter = onEnter,
                    onOpenFile = { entry ->
                        openedEntry = entry
                        onOpenFile(entry)
                    },
                    onDownload = onRequestDownload,
                    onLoadMore = onLoadMore,
                )
            }

            state.error?.let { message ->
                ErrorStrip(message = message, onRetry = onRefresh)
            }
        }
    }

    // 目录下载先枚举体积，枚举期间不可重复触发。
    if (state.preparing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在统计目录内容") },
            text = { Text("只读取文件列表与体积，不会开始传输。") },
            confirmButton = {},
        )
    }

    state.plan?.let { plan ->
        AlertDialog(
            onDismissRequest = onPlanCancel,
            title = { Text("确认下载") },
            text = {
                Column {
                    Text(plan.subtitle, style = MaterialTheme.typography.bodyMedium)
                    plan.warning?.let { warning ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (plan) {
                            is DownloadPlan.Single -> singleLauncher.launch(plan.entry.name)
                            is DownloadPlan.Directory -> treeLauncher.launch(null)
                        }
                    },
                ) { Text("选择保存位置") }
            },
            dismissButton = {
                TextButton(onClick = onPlanCancel) { Text("取消") }
            },
        )
    }

    state.download?.let { download ->
        DownloadDialog(
            download = download,
            onCancel = onCancelDownload,
            onDismiss = onDismissDownload,
        )
    }
}

@Composable
private fun WorkspaceTopBar(
    state: WorkspaceUiState,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRoot: (WorkspaceRoot) -> Unit,
    onRequestDirectoryDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon = QwenIcons.arrowLeft, description = "返回", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "工作区文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.path.isNotEmpty() && state.preview == null) {
                Text(
                    text = "上一级",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onUp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            IconCircle(icon = QwenIcons.refresh, description = "刷新", onClick = onRefresh)
            if (state.canDownloadDirectory) {
                IconCircle(
                    icon = QwenIcons.download,
                    description = "下载此目录",
                    onClick = onRequestDirectoryDownload,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceRoot.entries.forEach { root ->
                WorkspaceChip(
                    label = root.title,
                    selected = state.root == root,
                    onClick = { onOpenRoot(root) },
                )
            }
        }
        if (state.root == WorkspaceRoot.PROJECT) {
            Text(
                text = "项目目录未绑定时，这里显示服务端默认工作区。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        if (state.path.isNotEmpty()) {
            Text(
                text = state.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun WorkspaceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun WorkspaceList(
    state: WorkspaceUiState,
    onEnter: (WorkspaceEntry) -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onDownload: (WorkspaceEntry) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val entries = remember(state.entries) {
        state.entries.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    // 触底前两屏开始取下一页；用可见项而不是 entries.size 直接触发，
    // 否则首屏渲染完就会一页接一页地把整个目录拉完。
    LaunchedEffect(listState, entries.size, state.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (state.hasMore && !state.loadingMore && last >= entries.size - 3) onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            EntryRow(
                entry = entry,
                onClick = { if (entry.isDir) onEnter(entry) else onOpenFile(entry) },
                onDownload = if (entry.isDir) null else ({ onDownload(entry) }),
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

@Composable
private fun EntryRow(
    entry: WorkspaceEntry,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon(),
            contentDescription = null,
            tint = if (entry.isDir) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                entry.size?.takeIf { !entry.isDir }?.let { DownloadPolicy.formatBytes(it) },
                entry.modifiedAt?.let { prettyTime(it) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (onDownload != null) {
            IconCircle(
                icon = QwenIcons.download,
                description = "下载 ${entry.name}",
                onClick = onDownload,
            )
        }
    }
}

@Composable
private fun PreviewContent(
    preview: WorkspacePreview,
    entry: WorkspaceEntry?,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onDownload: (WorkspaceEntry?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon = QwenIcons.arrowLeft, description = "返回目录", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                text = preview.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry != null) {
                IconCircle(
                    icon = QwenIcons.download,
                    description = "下载",
                    onClick = { onDownload(entry) },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        when (preview) {
            is WorkspacePreview.Text -> TextPreview(preview = preview, onLoadMore = onLoadMore)
            is WorkspacePreview.Image -> ImagePreview(preview = preview, onDownload = { onDownload(entry) })
            is WorkspacePreview.Unsupported -> UnsupportedPreview(
                preview = preview,
                onDownload = { onDownload(entry) },
            )
        }
    }
}

@Composable
private fun TextPreview(preview: WorkspacePreview.Text, onLoadMore: () -> Unit) {
    if (preview.loading && preview.content.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = preview.content.ifEmpty { "（空文件）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        preview.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (!preview.eof && preview.error == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (preview.loadingMore) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在加载",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onLoadMore) { Text("继续加载") }
                }
            }
        } else if (preview.eof) {
            Text(
                text = "已到文件末尾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ImagePreview(preview: WorkspacePreview.Image, onDownload: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = preview.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = preview.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )

            preview.loading -> CircularProgressIndicator()

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = preview.error ?: "无法显示这张图片",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDownload) { Text("下载") }
            }
        }
    }
}

@Composable
private fun UnsupportedPreview(preview: WorkspacePreview.Unsupported, onDownload: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = QwenIcons.fileCode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "这种文件不能直接预览",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            preview.size?.let { size ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = DownloadPolicy.formatBytes(size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDownload) { Text("下载") }
        }
    }
}

@Composable
private fun EmptyContent(state: WorkspaceUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (state.loaded) "这个目录是空的" else "正在加载",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.loaded && state.root == WorkspaceRoot.MEMORY) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "记忆条目会在对话过程中自动写入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorStrip(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "重试",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DownloadDialog(
    download: DownloadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = download.phase == DownloadPhase.RUNNING
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(download.title) },
        text = {
            Column {
                if (download.fileCount > 1) {
                    Text(
                        text = "第 ${download.fileIndex}/${download.fileCount} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = progressText(download),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                val total = download.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (download.read.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                download.message?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = onCancel) { Text("取消") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun IconCircle(
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
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun WorkspaceEntry.icon(): androidx.compose.ui.graphics.vector.ImageVector = when {
    isDir -> QwenIcons.folder
    previewKind == "image" -> QwenIcons.image
    previewKind == "text" -> QwenIcons.fileText
    else -> QwenIcons.fileCode
}

/** `2026-11-10T04:51:00Z` → `2026-11-10 04:51`；不像时间就原样返回。 */
private fun prettyTime(raw: String): String {
    val normalized = raw.replace('T', ' ')
    return if (normalized.length >= 16) normalized.take(16) else normalized
}

private fun progressText(download: DownloadState): String {
    val read = DownloadPolicy.formatBytes(download.read)
    val total = download.total?.let { DownloadPolicy.formatBytes(it) }
    return when (download.phase) {
        DownloadPhase.RUNNING -> if (total != null) "$read / $total" else "已下载 $read"
        DownloadPhase.DONE -> "下载完成"
        DownloadPhase.CANCELLED -> "下载已取消"
        DownloadPhase.FAILED -> "下载失败"
    }
}
