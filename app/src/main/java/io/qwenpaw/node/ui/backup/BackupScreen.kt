package io.qwenpaw.node.ui.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BackupMetaDto
import io.qwenpaw.node.data.model.BackupScopeDto
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.TimeLabels
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.pageContentInsets
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.DownloadPolicy
import io.qwenpaw.node.ui.workspace.DownloadSink
import io.qwenpaw.node.ui.workspace.IconCircle
import io.qwenpaw.node.ui.workspace.SingleFileSink
import kotlinx.coroutines.launch
import okhttp3.RequestBody

/** 备份页面的动作集合。参数过多时逐条平铺会淹没状态，这里统一打包。 */
data class BackupActions(
    val refresh: () -> Unit,
    val retryJob: () -> Unit,
    val cancelJob: () -> Unit,
    val setCreateName: (String) -> Unit,
    val setCreateDescription: (String) -> Unit,
    val toggleCreateAgent: (String) -> Unit,
    val setCreateScope: (BackupScopeDto) -> Unit,
    val startCreate: () -> Unit,
    val openDetail: (String) -> Unit,
    val dismissDetail: () -> Unit,
    val requestDelete: (BackupMetaDto) -> Unit,
    val dismissDelete: () -> Unit,
    val confirmDelete: () -> Unit,
    val export: (String, DownloadSink) -> Unit,
    val beginImport: (String, () -> RequestBody) -> Unit,
    val reportImportProgress: (Long, Long?) -> Unit,
    val confirmImportOverwrite: () -> Unit,
    val trustImport: () -> Unit,
    val dismissImport: () -> Unit,
    val prepareRestore: (String) -> Unit,
    val updateRestore: (RestoreDraft) -> Unit,
    val dismissRestore: () -> Unit,
    val confirmRestore: () -> Unit,
    val dismissNotice: () -> Unit,
    val dismissError: () -> Unit,
    val dismissExportError: () -> Unit,
)

/**
 * 备份：创建、列表、导出、导入、删除与恢复。
 *
 * SAF 的选择器必须由界面启动，因此两个 launcher 放在本文件内；请求体由
 * [SafZipSource] 生成，确认覆盖与信任确认时由 `newBody` 重新打开输入流。
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    actions: BackupActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importSource by remember { mutableStateOf<SafZipSource?>(null) }
    var pendingExport by remember { mutableStateOf<BackupMetaDto?>(null) }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_SHORT).show()
        actions.dismissNotice()
    }
    LaunchedEffect(state.import) {
        val importing = state.import
        if (importing is ImportState.Idle || importing is ImportState.Done) {
            importSource?.cleanup()
            importSource = null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri != null && target != null) {
            actions.export(target.id, SingleFileSink(context.contentResolver, uri))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val prepared = runCatching { SafZipSource.prepare(context, uri, "backup.zip") }
                .getOrElse {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(
                            R.string.backup_error_read_file,
                            it.message.orEmpty(),
                        ),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
            importSource?.cleanup()
            importSource = prepared
            actions.beginImport(prepared.fileName) {
                prepared.newBody { read, total -> actions.reportImportProgress(read, total) }
            }
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = pageContentInsets(),
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
                        text = stringResource(R.string.backup_title),
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
            item {
                Text(
                    text = stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            state.error?.let { message ->
                item {
                    NoticeCard(message = message, danger = true, onDismiss = actions.dismissError)
                }
            }
            state.exportError?.let { message ->
                item {
                    NoticeCard(message = message, danger = true, onDismiss = actions.dismissExportError)
                }
            }
            state.job?.let { job ->
                item { JobCard(state, actions) }
            }
            item {
                SectionHeader(stringResource(R.string.backup_create_section))
                Card {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = state.createName,
                            onValueChange = actions.setCreateName,
                            label = { Text(stringResource(R.string.backup_create_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.createDescription,
                            onValueChange = actions.setCreateDescription,
                            label = { Text(stringResource(R.string.backup_create_description_label)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        ScopeSwitch(
                            title = stringResource(R.string.backup_create_agents_title),
                            subtitle = stringResource(R.string.backup_create_agents_subtitle),
                            checked = state.createScope.includeAgents,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeAgents = it))
                            },
                        )
                        ScopeSwitch(
                            title = stringResource(R.string.backup_scope_global_config),
                            subtitle = stringResource(R.string.backup_create_global_subtitle),
                            checked = state.createScope.includeGlobalConfig,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeGlobalConfig = it))
                            },
                        )
                        ScopeSwitch(
                            title = stringResource(R.string.backup_scope_secrets),
                            subtitle = stringResource(R.string.backup_create_secrets_subtitle),
                            checked = state.createScope.includeSecrets,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeSecrets = it))
                            },
                        )
                        ScopeSwitch(
                            title = stringResource(R.string.backup_scope_skill_pool),
                            subtitle = stringResource(R.string.backup_create_skill_pool_subtitle),
                            checked = state.createScope.includeSkillPool,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeSkillPool = it))
                            },
                        )
                        if (state.createScope.includeAgents) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.backup_create_select_agents),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.agents.forEach { agent ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { actions.toggleCreateAgent(agent.id) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Switch(
                                        checked = agent.id in state.createAgentIds,
                                        onCheckedChange = { actions.toggleCreateAgent(agent.id) },
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = agent.name.ifBlank { agent.id },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (state.agents.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.backup_create_no_agents),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        state.createError?.let { message ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = actions.startCreate,
                            enabled = !state.creating,
                        ) {
                            if (state.creating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.backup_create_start))
                        }
                    }
                }
            }

            state.export?.let { transfer ->
                item {
                    val total = transfer.total
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = if (total != null && total > 0) {
                                stringResource(
                                    R.string.backup_export_progress_total,
                                    DownloadPolicy.formatBytes(transfer.read),
                                    DownloadPolicy.formatBytes(total),
                                )
                            } else {
                                stringResource(
                                    R.string.backup_export_progress,
                                    DownloadPolicy.formatBytes(transfer.read),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { (transfer.read.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.backup_import_section),
                    stringResource(R.string.backup_import_section_trailing),
                )
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.backup_import_section_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text(stringResource(R.string.backup_import_pick))
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.backup_list_section),
                    pluralStringResource(
                        R.plurals.backup_list_count,
                        state.backups.size,
                        state.backups.size,
                    ),
                )
            }
            if (state.backups.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.backup_list_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.backups, key = { it.id }) { backup ->
                BackupRow(
                    backup = backup,
                    state = state,
                    onDetail = { actions.openDetail(backup.id) },
                    onDismissDetail = actions.dismissDetail,
                    onExport = {
                        pendingExport = backup
                        exportLauncher.launch("${backup.id}.zip")
                    },
                    onRestore = { actions.prepareRestore(backup.id) },
                    onDelete = { actions.requestDelete(backup) },
                )
            }
        }
    }

    state.deleteTarget?.let { target ->
        val unknown = stringResource(R.string.backup_unknown_value)
        DestructiveActionDialog(
            title = stringResource(R.string.backup_delete_title),
            message = stringResource(
                R.string.backup_delete_message,
                target.name.ifBlank { target.id },
            ),
            impacts = listOf(
                stringResource(
                    R.string.backup_delete_impact_created,
                    TimeLabels.fullStamp(target.createdAt).orEmpty().ifBlank { unknown },
                ),
                stringResource(R.string.backup_delete_impact_agents, target.agentCount),
                stringResource(R.string.backup_delete_impact_irreversible),
            ),
            confirmLabel = if (state.deleting) {
                stringResource(R.string.backup_deleting)
            } else {
                stringResource(R.string.action_delete)
            },
            onConfirm = { if (!state.deleting) actions.confirmDelete() },
            onDismiss = actions.dismissDelete,
        )
    }

    state.restore?.let { draft ->
        RestoreDialog(state = state, draft = draft, actions = actions)
    }

    ImportDialogs(state = state, actions = actions)
}

@Composable
private fun JobCard(state: BackupUiState, actions: BackupActions) {
    val job = state.job ?: return
    SectionHeader(stringResource(R.string.backup_job_section))
    Card {
        Column(Modifier.padding(14.dp)) {
            val statusText = when (job.status) {
                "pending" -> stringResource(R.string.backup_job_pending)
                "running" -> stringResource(R.string.backup_job_running)
                "cancel_requested" -> stringResource(R.string.backup_job_cancelling)
                "completed" -> stringResource(R.string.backup_job_completed)
                "failed" -> stringResource(R.string.backup_job_failed)
                "cancelled" -> stringResource(R.string.action_cancelled)
                else -> job.status
            }
            val phaseText = if (job.phase.isNotBlank()) phaseLabel(job.phase) else ""
            Text(
                text = if (phaseText.isNotEmpty()) "$statusText · $phaseText" else statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (job.totalAgents > 0) {
                Text(
                    text = "${job.agentIndex} / ${job.totalAgents}" +
                        (job.currentAgent?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (job.percent.coerceIn(0, 100) / 100f) },
                modifier = Modifier.fillMaxWidth(),
            )
            job.error?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.jobError?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!job.isTerminal) {
                    TextButton(onClick = actions.cancelJob) {
                        Text(stringResource(R.string.backup_job_cancel))
                    }
                }
                if (!state.jobStreaming && !job.isTerminal) {
                    TextButton(onClick = actions.retryJob) {
                        Text(stringResource(R.string.backup_job_retry))
                    }
                }
                if (state.jobStreaming) {
                    Text(
                        text = stringResource(R.string.backup_job_streaming),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupRow(
    backup: BackupMetaDto,
    state: BackupUiState,
    onDetail: () -> Unit,
    onDismissDetail: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val detail = state.detail?.takeIf { it.id == backup.id }
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = backup.name.ifBlank { backup.id },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = listOfNotNull(
                    TimeLabels.fullStamp(backup.createdAt)?.takeIf { it.isNotBlank() },
                    pluralStringResource(
                        R.plurals.backup_meta_agent_count,
                        backup.agentCount,
                        backup.agentCount,
                    ),
                    scopeSummary(backup.scope),
                    backup.qwenpawVersion.takeIf { it.isNotBlank() }?.let {
                        stringResource(R.string.backup_meta_server_version, it)
                    },
                    if (backup.acceptedViaTrust == true) {
                        stringResource(R.string.backup_meta_trusted)
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            backup.description.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                TextButton(onClick = onDetail, enabled = state.detailLoadingId != backup.id) {
                    Text(
                        if (state.detailLoadingId == backup.id) {
                            stringResource(R.string.backup_detail_loading)
                        } else {
                            stringResource(R.string.backup_detail)
                        },
                    )
                }
                TextButton(onClick = onExport, enabled = state.exportId == null) {
                    Text(stringResource(R.string.backup_export))
                }
                TextButton(onClick = onRestore) { Text(stringResource(R.string.backup_restore)) }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (detail != null) {
                HorizontalDivider()
                if (detail.workspaceStats.isEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_detail_no_workspace),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    detail.workspaceStats.forEach { (agentId, stats) ->
                        val agentName = state.agents.firstOrNull { it.id == agentId }
                            ?.name?.takeIf { it.isNotBlank() } ?: agentId
                        Text(
                            text = pluralStringResource(
                                R.plurals.backup_detail_workspace_stats,
                                stats.files,
                                agentName,
                                stats.files,
                                DownloadPolicy.formatBytes(stats.size),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                state.detailError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onDismissDetail) {
                    Text(stringResource(R.string.backup_detail_collapse))
                }
            }
        }
    }
}

@Composable
private fun RestoreDialog(state: BackupUiState, draft: RestoreDraft, actions: BackupActions) {
    val trustAllowed = draft.trustCode == null || draft.trustMode() != null
    AlertDialog(
        onDismissRequest = actions.dismissRestore,
        title = { Text(stringResource(R.string.backup_restore_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RestoreMode.entries.forEach { mode ->
                        val selected = draft.mode == mode
                        Text(
                            text = stringResource(mode.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { actions.updateRestore(draft.withMode(mode)) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                if (draft.mode == RestoreMode.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.backup_create_agents_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    draft.backup.workspaceStats.keys.forEach { id ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { actions.updateRestore(draft.toggleAgent(id)) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = id in draft.agentIds,
                                onCheckedChange = { actions.updateRestore(draft.toggleAgent(id)) },
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = state.agents.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                ScopeSwitch(
                    title = stringResource(R.string.backup_scope_global_config),
                    checked = draft.includeGlobalConfig,
                    onCheckedChange = { actions.updateRestore(draft.copy(includeGlobalConfig = it)) },
                )
                ScopeSwitch(
                    title = stringResource(R.string.backup_scope_secrets),
                    subtitle = if (draft.secretsAvailable) {
                        null
                    } else {
                        stringResource(R.string.backup_restore_secrets_unavailable)
                    },
                    checked = draft.includeSecrets && draft.secretsAvailable,
                    enabled = draft.secretsAvailable,
                    onCheckedChange = { actions.updateRestore(draft.copy(includeSecrets = it)) },
                )
                ScopeSwitch(
                    title = stringResource(R.string.backup_scope_skill_pool),
                    checked = draft.includeSkillPool,
                    onCheckedChange = { actions.updateRestore(draft.copy(includeSkillPool = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.backup_restore_impacts_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (draft.mode == RestoreMode.FULL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                draft.impacts().forEach { line ->
                    Text(
                        text = "· $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                state.restoreError?.let { message ->
                    Spacer(Modifier.height(8.dp))
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
                onClick = actions.confirmRestore,
                enabled = !state.restoreApplying && trustAllowed,
            ) {
                Text(
                    text = when {
                        state.restoreApplying -> stringResource(R.string.backup_restore_applying)
                        draft.trustMode() != null -> stringResource(R.string.backup_restore_trust)
                        else -> stringResource(R.string.backup_restore)
                    },
                    color = if (trustAllowed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = actions.dismissRestore) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ImportDialogs(state: BackupUiState, actions: BackupActions) {
    when (val importing = state.import) {
        ImportState.Idle -> Unit
        is ImportState.Uploading -> AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.backup_import_uploading_title)) },
            text = {
                Column {
                    Text(
                        text = importing.fileName.ifBlank {
                            stringResource(R.string.backup_import_file_fallback)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val total = importing.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (importing.read.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${DownloadPolicy.formatBytes(importing.read)} / ${DownloadPolicy.formatBytes(total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.backup_import_uploaded,
                                DownloadPolicy.formatBytes(importing.read),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = actions.dismissImport) {
                    Text(stringResource(R.string.backup_import_cancel))
                }
            },
        )

        is ImportState.Saving -> AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.backup_import_saving_title)) },
            text = { Text(stringResource(R.string.backup_import_saving_message)) },
            confirmButton = { },
        )

        is ImportState.Conflict -> DestructiveActionDialog(
            title = stringResource(R.string.backup_import_conflict_title),
            message = stringResource(
                R.string.backup_import_conflict_message,
                importing.existing.id,
            ),
            impacts = listOf(
                stringResource(
                    R.string.backup_import_conflict_existing,
                    importing.existing.name.ifBlank { importing.existing.id },
                ),
                stringResource(
                    R.string.backup_import_conflict_created,
                    TimeLabels.fullStamp(importing.existing.createdAt).orEmpty()
                        .ifBlank { stringResource(R.string.backup_unknown_value) },
                ),
                stringResource(R.string.backup_import_conflict_irreversible),
            ),
            confirmLabel = stringResource(R.string.backup_import_overwrite),
            onConfirm = actions.confirmImportOverwrite,
            onDismiss = actions.dismissImport,
        )

        is ImportState.TrustRequired -> AlertDialog(
            onDismissRequest = actions.dismissImport,
            title = {
                Text(
                    if (importing.canTrust) {
                        stringResource(R.string.backup_import_trust_title)
                    } else {
                        stringResource(R.string.backup_import_blocked_title)
                    },
                )
            },
            text = {
                Column {
                    Text(
                        text = importing.message.ifBlank {
                            stringResource(R.string.backup_import_signature_unknown)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (importing.canTrust) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.backup_import_trust_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (importing.canTrust) {
                    TextButton(onClick = actions.trustImport) {
                        Text(stringResource(R.string.backup_import_trust))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = actions.dismissImport) {
                    Text(
                        if (importing.canTrust) {
                            stringResource(R.string.action_cancel)
                        } else {
                            stringResource(R.string.action_close)
                        },
                    )
                }
            },
        )

        is ImportState.Done -> AlertDialog(
            onDismissRequest = actions.dismissImport,
            title = { Text(stringResource(R.string.backup_import_done_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.backup_import_done_message,
                        importing.backup.name.ifBlank { importing.backup.id },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = actions.dismissImport) {
                    Text(stringResource(R.string.backup_import_done_confirm))
                }
            },
        )
    }
}

@Composable
private fun ScopeSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NoticeCard(message: String, danger: Boolean, onDismiss: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun phaseLabel(phase: String): String = when (phase) {
    "preparing" -> stringResource(R.string.backup_phase_preparing)
    "agents" -> stringResource(R.string.backup_phase_agents)
    "finalizing" -> stringResource(R.string.backup_phase_finalizing)
    else -> phase
}

@Composable
private fun scopeSummary(scope: BackupScopeDto): String {
    val parts = mutableListOf<String>()
    if (scope.includeAgents) parts += stringResource(R.string.backup_scope_agents)
    if (scope.includeGlobalConfig) parts += stringResource(R.string.backup_scope_global_config)
    if (scope.includeSecrets) parts += stringResource(R.string.backup_scope_secrets)
    if (scope.includeSkillPool) parts += stringResource(R.string.backup_scope_skill_pool)
    return if (parts.isEmpty()) {
        stringResource(R.string.backup_scope_empty)
    } else {
        parts.joinToString(stringResource(R.string.backup_list_separator))
    }
}
