package io.qwenpaw.node.ui.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PoolSkillDto
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.common.bottomBarInsets
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle
import kotlinx.coroutines.launch

/**
 * 技能池首页。
 *
 * 顶栏只放返回、标题、批量、新建与刷新；Web 提示与操作条放进列表第一项，
 * 随列表一起滚走，不再长期占用竖向空间。
 */
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
    // 以下五个参数来自旧的导入弹窗，现已改为独立页面（SkillPoolImportScreen），
    // 保留是为了让调用方无需同步改动即可编译；后续接线时可以删除。
    onUploadClose: () -> Unit,
    onUploadWorkspace: (String) -> Unit,
    onUploadSkill: (String) -> Unit,
    onUploadOverwrite: (Boolean) -> Unit,
    onUploadConfirm: () -> Unit,
    onImportZip: (SafZipSource) -> Unit,
    onOpenConsole: (() -> Unit)? = null,
    onSelectAllSkills: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    /** 从 URL（bundle_url）导入技能池。 */
    onImportUrl: (String) -> Unit = {},
) {
    var deleting by remember { mutableStateOf<PoolSkillDto?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf("") }
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
    if (urlDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.urlImporting) urlDialog = false },
            title = { Text(stringResource(R.string.skill_pool_url_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.skill_pool_url_import)) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.skill_pool_url_dialog_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlDraft.isNotBlank() && !state.urlImporting,
                    onClick = {
                        onImportUrl(urlDraft)
                        urlDialog = false
                    },
                ) {
                    Text(stringResource(R.string.skill_pool_url_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { urlDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
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
                TextButton(onClick = { urlDialog = true }) {
                    Text(stringResource(R.string.skill_pool_url_import))
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
        bottomBar = {
            if (state.batchSelecting) {
                BatchBar(
                    selectedCount = state.batchSelected.size,
                    onSelectAll = onSelectAllSkills,
                    onInvert = onInvertSelection,
                    onClear = onClearSelection,
                    onDelete = onBatchDelete,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 结果与错误提示是操作后的即时反馈，固定在列表上方；下载进度带取消按钮，同样固定。
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
            if (state.loading && !state.loaded) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        PoolHeader(
                            onUploadOpen = onUploadOpen,
                            onImportZip = { zipLauncher.launch("application/zip") },
                            onOpenBuiltin = onOpenBuiltin,
                            onReconcile = onReconcile,
                            reconciling = state.reconciling,
                            zipImporting = state.zipImporting,
                            onOpenConsole = onOpenConsole,
                        )
                    }
                    if (state.pool.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.skill_pool_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 40.dp),
                            )
                        }
                    } else {
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
                                modifier = Modifier.padding(horizontal = 12.dp),
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
}

/** 列表第一项：Web 提示与横向操作条，跟随列表滚动。 */
@Composable
private fun PoolHeader(
    onUploadOpen: () -> Unit,
    onImportZip: () -> Unit,
    onOpenBuiltin: () -> Unit,
    onReconcile: () -> Unit,
    reconciling: Boolean,
    zipImporting: Boolean,
    onOpenConsole: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
            TextButton(onClick = onImportZip, enabled = !zipImporting) {
                Text(stringResource(R.string.skill_pool_import_zip))
            }
            TextButton(onClick = onOpenBuiltin) {
                Text(stringResource(R.string.skill_pool_builtin))
            }
            TextButton(onClick = onReconcile, enabled = !reconciling) {
                Text(stringResource(R.string.skill_pool_reconcile))
            }
        }
    }
}

/** 批量模式底栏：列表滚动时仍可操作。 */
@Composable
private fun BatchBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(bottomBarInsets())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.acl_selected, selectedCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.skill_pool_select_all))
            }
            TextButton(onClick = onInvert) {
                Text(stringResource(R.string.skill_pool_invert_selection))
            }
            TextButton(onClick = onClear, enabled = selectedCount > 0) {
                Text(stringResource(R.string.skill_pool_select_none))
            }
            TextButton(onClick = onDelete, enabled = selectedCount > 0) {
                Text(stringResource(R.string.skill_pool_delete))
            }
        }
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
    modifier: Modifier = Modifier,
) {
    val autoSyncBadge = stringResource(R.string.skill_pool_auto_sync_badge)
    val autoUpdateBadge = stringResource(R.string.skill_pool_auto_update_badge)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
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

/** 结果与错误提示条，技能池各页共用。 */
@Composable
internal fun NoticeBanner(text: String, error: Boolean, onDismiss: () -> Unit) {
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
