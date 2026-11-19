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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
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
                IconCircle(
                    icon = QwenIcons.arrowLeft,
                    description = stringResource(R.string.action_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Git",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.status?.branch?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.git_workspace),
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
                    description = stringResource(R.string.action_refresh),
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
                        text = stringResource(R.string.git_error_status),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.error ?: stringResource(R.string.git_not_repo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = actions.refresh) {
                        Text(stringResource(R.string.action_retry))
                    }
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
            title = { Text(stringResource(R.string.git_intro_title)) },
            text = {
                Text(stringResource(R.string.git_intro_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    noticeDismissed = true
                    actions.acknowledge()
                }) { Text(stringResource(R.string.git_intro_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { noticeDismissed = true }) {
                    Text(stringResource(R.string.git_intro_dismiss))
                }
            },
        )
    }

    if (showBranches) {
        AlertDialog(
            onDismissRequest = { showBranches = false },
            title = { Text(stringResource(R.string.git_branches_title)) },
            text = {
                Column {
                    val branches = state.branches
                    if (branches.isEmpty()) {
                        Text(stringResource(R.string.git_branches_empty))
                    } else {
                        val remoteSuffix = stringResource(R.string.git_branch_remote)
                        branches.forEach { branch ->
                            Text(
                                text = buildString {
                                    if (branch.current) append("● ")
                                    append(branch.name)
                                    if (branch.remote) append(remoteSuffix)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.git_branches_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBranches = false }) {
                    Text(stringResource(R.string.action_close))
                }
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
            text = stringResource(R.string.git_pending_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (dismissed) {
                stringResource(R.string.git_pending_dismissed)
            } else {
                stringResource(R.string.git_pending_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAcknowledge) {
            Text(stringResource(R.string.git_pending_continue))
        }
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
        TabChip(stringResource(R.string.git_tab_changes), tab == GitTab.CHANGES) {
            onSelect(GitTab.CHANGES)
        }
        TabChip(stringResource(R.string.git_tab_history), tab == GitTab.HISTORY) {
            onSelect(GitTab.HISTORY)
        }
        if (tab == GitTab.CHANGES) {
            val status = stringResource(R.string.git_workspace)
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
    val stagedTitle = stringResource(R.string.git_section_staged)
    val unstagedTitle = stringResource(R.string.git_section_unstaged)
    val untrackedTitle = stringResource(R.string.git_section_untracked)
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
                        text = stringResource(R.string.git_ahead_behind, ahead, behind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            section(
                title = stagedTitle,
                changes = state.staged,
                staged = true,
                state = state,
                actions = actions,
            )
            section(
                title = unstagedTitle,
                changes = state.unstaged,
                staged = false,
                state = state,
                actions = actions,
            )
            section(
                title = untrackedTitle,
                changes = state.untracked,
                staged = false,
                state = state,
                actions = actions,
            )
            if (state.staged.isEmpty() && state.unstaged.isEmpty() && state.untracked.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.git_changes_empty),
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
                placeholder = { Text(stringResource(R.string.git_commit_message_hint)) },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = actions.commit,
                enabled = state.staged.isNotEmpty() &&
                    state.message.isNotBlank() &&
                    !state.busy,
            ) { Text(stringResource(R.string.git_commit)) }
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
                text = pluralStringResource(
                    R.plurals.git_section_count,
                    changes.size,
                    title,
                    changes.size,
                ),
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
                Text(
                    if (staged) {
                        stringResource(R.string.git_unstage_all)
                    } else {
                        stringResource(R.string.git_stage_all)
                    },
                )
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
            Text(
                if (staged) {
                    stringResource(R.string.git_unstage)
                } else {
                    stringResource(R.string.git_stage)
                },
            )
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
                text = stringResource(R.string.git_history_empty),
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
    val noMessage = stringResource(R.string.git_no_commit_message)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = entry.message.ifBlank { noMessage },
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
            IconCircle(
                icon = QwenIcons.arrowLeft,
                description = stringResource(R.string.action_back),
                onClick = onClose,
            )
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
                text = stringResource(R.string.git_diff_truncated),
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
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
    }
}
