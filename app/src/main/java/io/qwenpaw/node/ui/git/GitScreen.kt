package io.qwenpaw.node.ui.git

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.GitChangeDto
import io.qwenpaw.node.data.model.GitLogEntryDto
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

data class GitActions(
    val open: () -> Unit,
    val acknowledge: () -> Unit,
    val refresh: () -> Unit,
    val showDiff: (GitChangeDto) -> Unit,
    val showCommitDiff: (String) -> Unit,
    val stage: (List<String>) -> Unit,
    val unstage: (List<String>) -> Unit,
    val commit: () -> Unit,
    val updateMessage: (String) -> Unit,
    val selectTab: (GitTab) -> Unit,
    val dismissDiff: () -> Unit,
    val dismissError: () -> Unit,
    val dismissNotice: () -> Unit,
)

@Composable
fun GitScreen(
    state: GitUiState,
    actions: GitActions,
    onBack: () -> Unit,
) {
    var noticeDismissed by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { actions.open() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(icon = QwenIcons.arrowLeft, description = "返回", onClick = onBack)
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Git",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.status?.branch?.takeIf { it.isNotBlank() } ?: "工作区",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.status != null) {
                    Text(
                        text = state.status.branch.ifBlank { "HEAD" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showBranches = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                IconCircle(
                    icon = QwenIcons.refresh,
                    description = "刷新",
                    onClick = actions.refresh,
                    enabled = !state.busy,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when {
                state.diff != null -> DiffScreen(
                    diff = state.diff,
                    onClose = actions.dismissDiff,
                )

                !state.acknowledged -> GitIntro(
                    dismissed = noticeDismissed,
                    onAcknowledge = actions.acknowledge,
                )

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.status == null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "无法读取 Git 状态",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.error ?: "当前工作区不是 Git 仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = actions.refresh) { Text("重试") }
                }

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    state.error?.let { message ->
                        GitBanner(
                            text = message,
                            isError = true,
                            onDismiss = actions.dismissError,
                        )
                    }
                    state.notice?.let { message ->
                        GitBanner(
                            text = message,
                            isError = false,
                            onDismiss = actions.dismissNotice,
                        )
                    }
                    GitTabs(state.tab, actions.selectTab)
                    when (state.tab) {
                        GitTab.CHANGES -> ChangesTab(
                            state = state,
                            actions = actions,
                        )
                        GitTab.HISTORY -> HistoryTab(
                            state = state,
                            onOpen = actions.showCommitDiff,
                        )
                    }
                }
            }
        }
    }

    if (!state.acknowledged && !noticeDismissed) {
        AlertDialog(
            onDismissRequest = { noticeDismissed = true },
            title = { Text("首次打开 Git") },
            text = {
                Text(
                    "打开后会检查工作区。如果它还不是独立的 Git 仓库，服务端会初始化仓库、" +
                        "写一份 .gitignore，并把当前全部文件做一次初始提交。这个操作会改动工作区。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    noticeDismissed = true
                    actions.acknowledge()
                }) { Text("我已了解，继续") }
            },
            dismissButton = {
                TextButton(onClick = { noticeDismissed = true }) { Text("暂不检查") }
            },
        )
    }

    if (showBranches) {
        AlertDialog(
            onDismissRequest = { showBranches = false },
            title = { Text("分支（只读）") },
            text = {
                Column {
                    val branches = state.branches
                    if (branches.isEmpty()) {
                        Text("没有可显示的分支。")
                    } else {
                        branches.forEach { branch ->
                            Text(
                                text = buildString {
                                    if (branch.current) append("● ")
                                    append(branch.name)
                                    if (branch.remote) append("（远程）")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本版不提供切换分支；需要换分支请使用 Web 控制台。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBranches = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun GitIntro(dismissed: Boolean, onAcknowledge: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Git 检查尚未开始",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (dismissed) {
                "你选择了暂不检查。准备好后可以继续；首次检查可能会初始化仓库并做一次初始提交。"
            } else {
                "首次检查可能会初始化仓库并做一次初始提交。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAcknowledge) { Text("继续检查") }
    }
}

@Composable
private fun GitTabs(tab: GitTab, onSelect: (GitTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabChip("改动", tab == GitTab.CHANGES) { onSelect(GitTab.CHANGES) }
        TabChip("历史", tab == GitTab.HISTORY) { onSelect(GitTab.HISTORY) }
        if (tab == GitTab.CHANGES) {
            val status = "工作区"
            Spacer(Modifier.weight(1f))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun ChangesTab(
    state: GitUiState,
    actions: GitActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val ahead = state.status?.ahead ?: 0
            val behind = state.status?.behind ?: 0
            if (ahead > 0 || behind > 0) {
                item {
                    Text(
                        text = "领先 $ahead / 落后 $behind",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            section(
                title = "已暂存",
                changes = state.staged,
                staged = true,
                state = state,
                actions = actions,
            )
            section(
                title = "未暂存",
                changes = state.unstaged,
                staged = false,
                state = state,
                actions = actions,
            )
            section(
                title = "未跟踪",
                changes = state.untracked,
                staged = false,
                state = state,
                actions = actions,
            )
            if (state.staged.isEmpty() && state.unstaged.isEmpty() && state.untracked.isEmpty()) {
                item {
                    Text(
                        text = "工作区没有改动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
        Divider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.message,
                onValueChange = actions.updateMessage,
                placeholder = { Text("提交信息") },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = actions.commit,
                enabled = state.staged.isNotEmpty() &&
                    state.message.isNotBlank() &&
                    !state.busy,
            ) { Text("提交") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    changes: List<GitChangeDto>,
    staged: Boolean,
    state: GitUiState,
    actions: GitActions,
) {
    if (changes.isEmpty()) return
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$title（${changes.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (staged) actions.unstage(emptyList())
                    else actions.stage(emptyList())
                },
                enabled = !state.busy,
            ) {
                Text(if (staged) "全部取消暂存" else "全部暂存")
            }
        }
    }
    items(changes, key = { "$title|${it.path}|${it.staged}" }) { change ->
        ChangeRow(
            change = change,
            staged = staged,
            enabled = !state.busy,
            onClick = { actions.showDiff(change) },
            onToggle = {
                if (staged) actions.unstage(listOf(change.path))
                else actions.stage(listOf(change.path))
            },
        )
    }
}

@Composable
private fun ChangeRow(
    change: GitChangeDto,
    staged: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = change.status,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = when (change.status) {
                "D" -> MaterialTheme.colorScheme.error
                "?" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = change.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle, enabled = enabled) {
            Text(if (staged) "取消暂存" else "暂存")
        }
    }
}

@Composable
private fun HistoryTab(
    state: GitUiState,
    onOpen: (String) -> Unit,
) {
    if (state.log.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "还没有提交记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 4.dp,
        ),
    ) {
        items(state.log, key = { it.hash }) { entry ->
            HistoryRow(entry = entry, onClick = { onOpen(entry.hash) })
        }
    }
}

@Composable
private fun HistoryRow(entry: GitLogEntryDto, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = entry.message.ifBlank { "（无提交信息）" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${entry.hash}  ${entry.author}  ${entry.date}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Divider()
}

@Composable
private fun DiffScreen(
    diff: GitDiffView,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon = QwenIcons.arrowLeft, description = "返回", onClick = onClose)
            Spacer(Modifier.width(4.dp))
            Text(
                text = diff.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (diff.truncated) {
            Text(
                text = "差异较大，已截断显示；完整内容请在 Web 控制台查看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            ) {
                items(diff.text.split('\n')) { line ->
                    val color = when {
                        line.startsWith("+++") || line.startsWith("---") ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        line.startsWith("+") -> MaterialTheme.colorScheme.primary
                        line.startsWith("-") -> MaterialTheme.colorScheme.error
                        line.startsWith("@@") -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = line.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun GitBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}
