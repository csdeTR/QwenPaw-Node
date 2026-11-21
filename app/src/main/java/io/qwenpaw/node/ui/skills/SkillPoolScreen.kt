package io.qwenpaw.node.ui.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PoolSkillDto
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle
import kotlinx.coroutines.launch

@Composable
fun SkillPoolScreen(
    state: SkillPoolUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onBeginCreate: () -> Unit,
    onBeginEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleBatch: () -> Unit,
    onToggleBatchSelected: (String) -> Unit,
    onBatchDelete: () -> Unit,
    onOpenBuiltin: () -> Unit,
    onOpenDistribute: (String) -> Unit,
    onCloseDistribute: () -> Unit,
    onToggleDistributeTarget: (String) -> Unit,
    onSetDistributeAll: (Boolean) -> Unit,
    onSetDistributeOverwrite: (Boolean) -> Unit,
    onStartDistribute: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissMessage: () -> Unit,
    onUploadOpen: () -> Unit,
    onReconcile: () -> Unit,
    onUploadClose: () -> Unit,
    onUploadWorkspace: (String) -> Unit,
    onUploadSkill: (String) -> Unit,
    onUploadOverwrite: (Boolean) -> Unit,
    onUploadConfirm: () -> Unit,
    onImportZip: (SafZipSource) -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    var deleting by remember { mutableStateOf<PoolSkillDto?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val source = runCatching {
                    SafZipSource.prepare(context, uri, "skill.zip")
                }.getOrNull()
                if (source != null) onImportZip(source)
            }
        }
    }

    WebEntryDialog(pageKey = "skill_pool", onOpenConsole = onOpenConsole)
    LaunchedEffect(Unit) { onRefresh() }

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
                Text(
                    text = stringResource(R.string.skill_pool_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleBatch) {
                    Text(stringResource(R.string.skill_pool_batch))
                }
                TextButton(onClick = onBeginCreate) {
                    Text(stringResource(R.string.skill_pool_new))
                }
                IconCircle(
                    icon = QwenIcons.refresh,
                    description = stringResource(R.string.action_refresh),
                    onClick = onRefresh,
                    enabled = !state.loading,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ConsoleHint(onOpenConsole = onOpenConsole)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onUploadOpen) {
                        Text(stringResource(R.string.skill_pool_import_from_workspace))
                    }
                    TextButton(onClick = { zipLauncher.launch("application/zip") }) {
                        Text(stringResource(R.string.skill_pool_import_zip))
                    }
                    TextButton(onClick = onOpenBuiltin) {
                        Text(stringResource(R.string.skill_pool_builtin))
                    }
                    TextButton(onClick = onReconcile, enabled = !state.reconciling) {
                        Text(stringResource(R.string.skill_pool_reconcile))
                    }
                }
                state.message?.let { NoticeBanner(it, false, onDismissMessage) }
                state.error?.let { NoticeBanner(it, true, onDismissMessage) }
                state.download?.let { download ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.skill_pool_download_running) +
                                    " " + download.skillName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onCancelDownload) {
                                Text(stringResource(R.string.skill_pool_download_cancel))
                            }
                        }
                    }
                }
                if (state.batchSelecting) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.acl_selected, state.batchSelected.size),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onBatchDelete,
                                enabled = state.batchSelected.isNotEmpty(),
                            ) { Text(stringResource(R.string.skill_pool_delete)) }
                        }
                    }
                }
                when {
                    state.loading && !state.loaded -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    state.pool.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.skill_pool_empty)) }

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.pool, key = { it.name }) { skill ->
                            PoolRow(
                                skill = skill,
                                batchSelecting = state.batchSelecting,
                                selected = skill.name in state.batchSelected,
                                onRowClick = {
                                    if (state.batchSelecting) {
                                        onToggleBatchSelected(skill.name)
                                    } else {
                                        onBeginEdit(skill.name)
                                    }
                                },
                                onDistribute = { onOpenDistribute(skill.name) },
                                onDelete = { deleting = skill },
                            )
                        }
                    }
                }
            }
        }
    }

    deleting?.let { skill ->
        DestructiveActionDialog(
            title = stringResource(R.string.skill_pool_delete_title),
            message = stringResource(R.string.skill_pool_delete_message, skill.name),
            impacts = if (skill.external) {
                listOf(stringResource(R.string.skill_pool_external_warning))
            } else {
                emptyList()
            },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                deleting = null
                onDelete(skill.name)
            },
            onDismiss = { deleting = null },
        )
    }

    state.distributeOpen?.let { skillName ->
        DistributeDialog(
            skillName = skillName,
            state = state,
            onClose = onCloseDistribute,
            onToggleTarget = onToggleDistributeTarget,
            onSetAll = onSetDistributeAll,
            onSetOverwrite = onSetDistributeOverwrite,
            onStart = onStartDistribute,
        )
    }

    if (state.uploadOpen) {
        UploadDialog(
            state = state,
            onClose = onUploadClose,
            onWorkspace = onUploadWorkspace,
            onSkill = onUploadSkill,
            onOverwrite = onUploadOverwrite,
            onConfirm = onUploadConfirm,
        )
    }
}

@Composable
private fun PoolRow(
    skill: PoolSkillDto,
    batchSelecting: Boolean,
    selected: Boolean,
    onRowClick: () -> Unit,
    onDistribute: () -> Unit,
    onDelete: () -> Unit,
) {
    val autoSyncBadge = stringResource(R.string.skill_pool_auto_sync_badge)
    val autoUpdateBadge = stringResource(R.string.skill_pool_auto_update_badge)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (batchSelecting) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = buildString {
                        append(SkillCopy.sourceLabel(skill.source))
                        SkillCopy.syncStatusLabel(skill.syncStatus).takeIf { it.isNotBlank() }
                            ?.let { append(" $it") }
                        if (skill.autoSync) append(" $autoSyncBadge")
                        if (skill.autoUpdate) append(" $autoUpdateBadge")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            if (!batchSelecting) {
                TextButton(onClick = onDistribute) {
                    Text(stringResource(R.string.skill_pool_distribute))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.skill_pool_delete))
                }
            }
        }
    }
}

@Composable
private fun DistributeDialog(
    skillName: String,
    state: SkillPoolUiState,
    onClose: () -> Unit,
    onToggleTarget: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onSetOverwrite: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.skill_pool_distribute_title) + "：" + skillName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.distributeAll, onCheckedChange = onSetAll)
                    Text(stringResource(R.string.skill_pool_distribute_all))
                }
                if (!state.distributeAll) {
                    state.workspaces.forEach { ws ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = ws.agentId in state.distributeTargets,
                                onCheckedChange = { onToggleTarget(ws.agentId) },
                            )
                            Text(
                                text = ws.agentName.ifBlank { ws.agentId },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.distributeOverwrite, onCheckedChange = onSetOverwrite)
                    Text(stringResource(R.string.skill_pool_distribute_overwrite))
                }
                if (state.distributeConflicts.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.skill_pool_distribute_conflict_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.skill_pool_distribute_conflict_message))
                    state.distributeConflicts.forEach {
                        Text("- $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onStart,
                enabled = state.distributeAll || state.distributeTargets.isNotEmpty(),
            ) {
                Text(stringResource(R.string.skill_pool_distribute_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun UploadDialog(
    state: SkillPoolUiState,
    onClose: () -> Unit,
    onWorkspace: (String) -> Unit,
    onSkill: (String) -> Unit,
    onOverwrite: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    val selectedWorkspace = state.workspaces.firstOrNull { it.agentId == state.uploadWorkspace }
    val skills = selectedWorkspace?.skillNames.orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.skill_pool_upload_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.skill_pool_upload_workspace),
                    style = MaterialTheme.typography.labelMedium,
                )
                state.workspaces.forEach { ws ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = ws.agentId == state.uploadWorkspace,
                            onCheckedChange = { if (it) onWorkspace(ws.agentId) },
                        )
                        Text(
                            text = ws.agentName.ifBlank { ws.agentId },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.skill_pool_upload_skill),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (skills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skill_pool_upload_no_skills),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    skills.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = name == state.uploadSkill,
                                onCheckedChange = { if (it) onSkill(name) },
                            )
                            Text(name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.uploadOverwrite, onCheckedChange = onOverwrite)
                    Text(stringResource(R.string.skill_pool_upload_overwrite))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.uploadSkill.isNotBlank() && !state.uploading,
            ) { Text(stringResource(R.string.skill_pool_upload_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun NoticeBanner(text: String, error: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
    }
}