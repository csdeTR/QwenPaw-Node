package io.qwenpaw.node.ui.checkpoint

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.CheckpointNodeDto
import io.qwenpaw.node.data.model.CheckpointSessionDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.ui.TimeLabels
import io.qwenpaw.node.ui.chat.ChatRowContent
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

/** 检查点页面的动作集合。 */
data class CheckpointActions(
    val refresh: () -> Unit,
    val toggleAuto: (Boolean) -> Unit,
    /** 打开某条会话的只读对话页，参数是会话的 `session_key`。 */
    val openSnapshot: (String) -> Unit,
    val closeSnapshot: () -> Unit,
    val setSnapshotName: (String) -> Unit,
    val dismissSnapshot: () -> Unit,
    val submitSnapshot: () -> Unit,
    val openRestore: (CheckpointNodeDto) -> Unit,
    val dismissRestore: () -> Unit,
    val setIncludeMemory: (Boolean) -> Unit,
    val setIncludeFiles: (Boolean) -> Unit,
    val toggleFile: (String) -> Unit,
    val selectAllFiles: () -> Unit,
    val clearFiles: () -> Unit,
    val loadPreview: () -> Unit,
    val applyRestore: () -> Unit,
    val updateGcDraft: (GcDraft) -> Unit,
    val saveGcSettings: () -> Unit,
    val previewGc: () -> Unit,
    val applyGc: () -> Unit,
    val requestReset: () -> Unit,
    val dismissReset: () -> Unit,
    val confirmReset: () -> Unit,
    val dismissNotice: () -> Unit,
    val dismissError: () -> Unit,
)

private const val SNAPSHOT_SESSION_LIMIT = 8

/**
 * 会话转成对话行的展示模型。
 *
 * 只借用 `ChatRowContent` 的排布：标题、图标与时间。没有标题时用 `session_key`
 * 前 8 位兜底，避免出现一整行空白。[latestMs] 是该会话最新检查点的时间，
 * 选择器用它代替对话的「最后更新时间」。
 */
internal fun CheckpointSessionDto.asChatSpec(latestMs: Long? = null): ChatSpecDto = ChatSpecDto(
    id = sessionId.ifBlank { sessionKey },
    name = title.ifBlank { sessionKey.take(8) },
    sessionId = sessionId,
    channel = channel.ifBlank { "console" },
    archived = archived,
    updatedAt = latestMs?.takeIf { it > 0L }
        ?.let { java.time.Instant.ofEpochMilli(it).toString() },
)

/**
 * 检查点：自动开关、时间线、手动快照、回滚与清理。
 *
 * 工作区不是 git 仓库时 `status` 返回 400，页面只显示服务端的说明与刷新入口，
 * 不渲染任何写入按钮——检查点的所有写操作都依赖 git。
 */
@Composable
fun CheckpointScreen(
    state: CheckpointUiState,
    actions: CheckpointActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_SHORT).show()
        actions.dismissNotice()
    }
    BackHandler(onBack = onBack)

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
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconCircle(
                        icon = QwenIcons.arrowLeft,
                        description = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.checkpoint_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircle(
                        icon = QwenIcons.refresh,
                        description = stringResource(R.string.action_refresh),
                        onClick = actions.refresh,
                    )
                }
            }
        },
    ) { padding ->
        if (state.loading && !state.loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            state.error?.let { message ->
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.checkpoint_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = actions.dismissError) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }

            if (!state.writesDisabled) {
                item {
                    SectionHeader(stringResource(R.string.checkpoint_auto_header))
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.checkpoint_auto_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.checkpoint_auto_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.autoEnabled,
                                    onCheckedChange = actions.toggleAuto,
                                    enabled = !state.autoSaving,
                                )
                            }
                            state.status?.workspaceDir?.takeIf { it.isNotBlank() }?.let { dir ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.checkpoint_workspace_dir, dir),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.graph?.summary?.let { summary ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.checkpoint_summary,
                                        summary.total,
                                        summary.total,
                                        summary.auto,
                                        summary.snapshots,
                                        summary.safety,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (!state.writesDisabled) {
                item {
                    SectionHeader(stringResource(R.string.checkpoint_manual_header))
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.checkpoint_manual_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val sessions = state.graph?.sessions.orEmpty()
                            if (sessions.isEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.checkpoint_manual_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 按该会话最新的检查点排序：刚做过检查点的会话排在前面，
                            // 没有检查点的按标题排在后面。
                            val ordered = sessions.sortedWith(
                                compareByDescending<CheckpointSessionDto> {
                                    state.groups.firstOrNull { group ->
                                        group.sessionKey == it.sessionKey
                                    }?.nodes?.firstOrNull()?.timestampMs ?: 0L
                                }.thenBy { it.title },
                            )
                            ordered.take(SNAPSHOT_SESSION_LIMIT).forEach { session ->
                                val latestMs = state.groups.firstOrNull { group ->
                                    group.sessionKey == session.sessionKey
                                }?.nodes?.firstOrNull()?.timestampMs
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { actions.openSnapshot(session.sessionKey) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        ChatRowContent(
                                            chat = session.asChatSpec(latestMs),
                                            compact = false,
                                            preview = null,
                                        )
                                    }
                                    Icon(
                                        imageVector = QwenIcons.chevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(18.dp),
                                    )
                                }
                            }
                            if (sessions.size > SNAPSHOT_SESSION_LIMIT) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.checkpoint_manual_limit,
                                        SNAPSHOT_SESSION_LIMIT,
                                        SNAPSHOT_SESSION_LIMIT,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (state.graphLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
            }

            if (state.groups.isEmpty() && state.graph != null) {
                item {
                    Text(
                        text = stringResource(R.string.checkpoint_timeline_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            state.groups.forEach { group ->
                item(key = "header-${group.sessionKey}") {
                    SectionHeader(
                        title = group.title,
                        trailing = if (group.archived) stringResource(R.string.checkpoint_archived) else null,
                    )
                }
                items(group.nodes, key = { "node-${it.commit}" }) { node ->
                    CheckpointNodeRow(
                        node = node,
                        onRestore = { actions.openRestore(node) },
                        enabled = !state.writesDisabled,
                    )
                }
            }

            if (!state.writesDisabled) {
                item {
                    SectionHeader(stringResource(R.string.checkpoint_gc_header))
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.checkpoint_gc_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            GcField(
                                label = stringResource(R.string.checkpoint_gc_keep_count),
                                value = state.gcDraft.keepCount,
                                onValueChange = { actions.updateGcDraft(state.gcDraft.copy(keepCount = it)) },
                            )
                            GcField(
                                label = stringResource(R.string.checkpoint_gc_keep_days),
                                value = state.gcDraft.keepDays,
                                onValueChange = { actions.updateGcDraft(state.gcDraft.copy(keepDays = it)) },
                            )
                            GcField(
                                label = stringResource(R.string.checkpoint_gc_pre_restore_days),
                                value = state.gcDraft.preRestoreDays,
                                onValueChange = { actions.updateGcDraft(state.gcDraft.copy(preRestoreDays = it)) },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.checkpoint_gc_compact),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = state.gcDraft.compact,
                                    onCheckedChange = { actions.updateGcDraft(state.gcDraft.copy(compact = it)) },
                                )
                            }
                            state.gcError?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Row {
                                TextButton(onClick = actions.saveGcSettings, enabled = !state.gcSaving) {
                                    Text(
                                        if (state.gcSaving) {
                                            stringResource(R.string.checkpoint_gc_saving)
                                        } else {
                                            stringResource(R.string.checkpoint_gc_save)
                                        },
                                    )
                                }
                                TextButton(onClick = actions.previewGc, enabled = !state.gcPreviewing) {
                                    Text(
                                        if (state.gcPreviewing) {
                                            stringResource(R.string.checkpoint_gc_previewing)
                                        } else {
                                            stringResource(R.string.checkpoint_gc_preview)
                                        },
                                    )
                                }
                                TextButton(
                                    onClick = actions.applyGc,
                                    enabled = state.gcPreview != null && !state.gcApplying,
                                ) {
                                    Text(
                                        if (state.gcApplying) {
                                            stringResource(R.string.checkpoint_gc_applying)
                                        } else {
                                            stringResource(R.string.checkpoint_gc_apply)
                                        },
                                    )
                                }
                            }
                            state.gcPreview?.let { preview ->
                                HorizontalDivider()
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = if (preview.deletedRefs.isEmpty()) {
                                        stringResource(R.string.checkpoint_gc_preview_empty)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.checkpoint_gc_preview_result,
                                            preview.deletedRefs.size,
                                            preview.deletedRefs.size,
                                            preview.keptRefs.size,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    SectionHeader(stringResource(R.string.checkpoint_danger_header))
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.checkpoint_reset_heading),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.checkpoint_reset_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = actions.requestReset) {
                                Text(
                                    stringResource(R.string.checkpoint_reset_action),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.restore?.let { draft ->
        RestoreCheckpointDialog(state = state, draft = draft, actions = actions)
    }
    if (state.resetConfirm) {
        val agentFallback = stringResource(R.string.checkpoint_impact_current_agent)
        val workspaceLabel = state.status?.workspaceDir.orEmpty().ifBlank { agentFallback }
        DestructiveActionDialog(
            title = stringResource(R.string.checkpoint_reset_action),
            message = stringResource(R.string.checkpoint_reset_confirm_message),
            impacts = listOf(
                stringResource(R.string.checkpoint_workspace_dir, workspaceLabel),
                stringResource(
                    R.string.checkpoint_impact_count,
                    state.graph?.summary?.total ?: 0,
                ),
                stringResource(R.string.checkpoint_impact_no_undo),
            ),
            confirmLabel = if (state.resetting) {
                stringResource(R.string.checkpoint_reset_deleting)
            } else {
                stringResource(R.string.checkpoint_reset_confirm)
            },
            onConfirm = { if (!state.resetting) actions.confirmReset() },
            onDismiss = actions.dismissReset,
        )
    }
}

@Composable
private fun CheckpointNodeRow(node: CheckpointNodeDto, onRestore: () -> Unit, enabled: Boolean) {
    Card {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = checkpointKindLabel(node.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = TimeLabels.epochMillisStamp(node.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (node.isHead) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.checkpoint_node_latest),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        node.name.takeIf { it.isNotBlank() },
                        node.subject.takeIf { it.isNotBlank() },
                    ).firstOrNull() ?: node.sha.take(7),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = TimeLabels.epochMillisFull(node.timestampMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore, enabled = enabled) {
                Text(stringResource(R.string.checkpoint_node_restore))
            }
        }
    }
}

/**
 * 创建检查点的命名对话框。
 *
 * 放在只读对话页上使用：用户先看清会话内容，再决定名称。
 * 状态由 `CheckpointViewModel` 持有，对话框本身不保留输入。
 */
@Composable
fun CheckpointSnapshotDialog(
    session: CheckpointSessionDto,
    name: String,
    pending: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkpoint_snapshot_create)) },
        text = {
            Column {
                Text(
                    text = session.title.ifBlank { session.sessionKey.take(8) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.checkpoint_snapshot_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !pending) {
                Text(
                    if (pending) {
                        stringResource(R.string.checkpoint_snapshot_creating)
                    } else {
                        stringResource(R.string.checkpoint_snapshot_confirm)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RestoreCheckpointDialog(
    state: CheckpointUiState,
    draft: CheckpointRestoreDraft,
    actions: CheckpointActions,
) {
    AlertDialog(
        onDismissRequest = actions.dismissRestore,
        title = { Text(stringResource(R.string.checkpoint_restore_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = draft.node.name.takeIf { it.isNotBlank() } ?: draft.node.sha.take(7),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = TimeLabels.epochMillisFull(draft.node.timestampMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.checkpoint_restore_memory),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = draft.includeMemory, onCheckedChange = actions.setIncludeMemory)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.checkpoint_restore_files),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = draft.includeFiles, onCheckedChange = actions.setIncludeFiles)
                }
                if (state.previewLoading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgress()
                }
                state.previewError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (draft.includeFiles) {
                    val files = state.restorePreview?.filePaths.orEmpty()
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(onClick = actions.selectAllFiles) {
                            Text(stringResource(R.string.checkpoint_restore_select_all))
                        }
                        TextButton(onClick = actions.clearFiles) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                    if (files.isEmpty()) {
                        Text(
                            text = if (state.previewLoading) {
                                stringResource(R.string.checkpoint_restore_loading_files)
                            } else {
                                stringResource(R.string.checkpoint_restore_no_files)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    files.forEach { path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { actions.toggleFile(path) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = path in draft.selectedFiles,
                                onCheckedChange = { actions.toggleFile(path) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.checkpoint_restore_impacts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.impacts(state.restorePreview).forEach { line ->
                    Text(
                        text = stringResource(R.string.checkpoint_restore_impact_line, line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                state.restoreError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.applyRestore,
                enabled = draft.canApply && !state.restoreApplying,
            ) {
                Text(
                    text = if (state.restoreApplying) {
                        stringResource(R.string.checkpoint_restore_applying)
                    } else {
                        stringResource(R.string.checkpoint_restore_confirm)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = actions.dismissRestore) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(onClick = actions.loadPreview) {
                Text(stringResource(R.string.checkpoint_restore_refresh_preview))
            }
        },
    )
}

@Composable
private fun LinearProgress() {
    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun GcField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
