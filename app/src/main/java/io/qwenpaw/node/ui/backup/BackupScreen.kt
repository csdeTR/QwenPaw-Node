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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.BackupMetaDto
import io.qwenpaw.node.data.model.BackupScopeDto
import io.qwenpaw.node.data.remote.BackupImportSource
import io.qwenpaw.node.ui.TimeLabels
import io.qwenpaw.node.ui.common.DestructiveActionDialog
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
 * [BackupImportSource] 生成，确认覆盖与信任确认时由 `newBody` 重新打开输入流。
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    actions: BackupActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importSource by remember { mutableStateOf<BackupImportSource?>(null) }
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
            val prepared = runCatching { BackupImportSource.prepare(context, uri) }
                .getOrElse {
                    android.widget.Toast.makeText(
                        context,
                        "无法读取所选文件：${it.message.orEmpty()}",
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
                    IconCircle(icon = QwenIcons.arrowLeft, description = "返回", onClick = onBack)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "备份",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircle(icon = QwenIcons.refresh, description = "刷新", onClick = actions.refresh)
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
                    text = "备份包含服务端配置与智能体工作区，可在换机或误操作前留档。" +
                        "创建与导入都会上传工作区文件，请留意当前网络流量。",
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
                SectionHeader("新建备份")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = state.createName,
                            onValueChange = actions.setCreateName,
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.createDescription,
                            onValueChange = actions.setCreateDescription,
                            label = { Text("说明（可选）") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        ScopeSwitch(
                            title = "智能体工作区",
                            subtitle = "包含会话文件与项目目录",
                            checked = state.createScope.includeAgents,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeAgents = it))
                            },
                        )
                        ScopeSwitch(
                            title = "全局配置",
                            subtitle = "模型、渠道与界面相关的服务端配置",
                            checked = state.createScope.includeGlobalConfig,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeGlobalConfig = it))
                            },
                        )
                        ScopeSwitch(
                            title = "密钥",
                            subtitle = "默认不包含；包含后备份包会持有明文密钥",
                            checked = state.createScope.includeSecrets,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeSecrets = it))
                            },
                        )
                        ScopeSwitch(
                            title = "技能池",
                            subtitle = "已安装的技能与依赖",
                            checked = state.createScope.includeSkillPool,
                            onCheckedChange = {
                                actions.setCreateScope(state.createScope.copy(includeSkillPool = it))
                            },
                        )
                        if (state.createScope.includeAgents) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "选择智能体",
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
                                    text = "服务端没有可选的智能体。",
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
                            Text("开始备份")
                        }
                    }
                }
            }

            state.export?.let { transfer ->
                item {
                    val total = transfer.total
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = "正在导出：${DownloadPolicy.formatBytes(transfer.read)}" +
                                if (total != null && total > 0) " / ${DownloadPolicy.formatBytes(total)}" else "",
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
                SectionHeader("导入备份", "选择 .zip")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "从本地选择一个备份包导入服务端。同 id 的备份已存在时，" +
                                "会先询问是否覆盖。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text("选择备份包")
                        }
                    }
                }
            }

            item {
                SectionHeader("已有备份", "${state.backups.size} 个")
            }
            if (state.backups.isEmpty()) {
                item {
                    Text(
                        text = "服务端还没有备份。",
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
        DestructiveActionDialog(
            title = "删除备份",
            message = "将删除备份「${target.name.ifBlank { target.id }}」，服务端不保留回收站。",
            impacts = listOf(
                "备份创建时间：${TimeLabels.fullStamp(target.createdAt).orEmpty().ifBlank { "未知" }}",
                "智能体数量：${target.agentCount}",
                "删除后无法从该备份恢复。",
            ),
            confirmLabel = if (state.deleting) "正在删除…" else "删除",
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
    SectionHeader("备份任务")
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = when (job.status) {
                    "pending" -> "等待开始"
                    "running" -> "正在备份"
                    "cancel_requested" -> "正在取消"
                    "completed" -> "已完成"
                    "failed" -> "备份失败"
                    "cancelled" -> "已取消"
                    else -> job.status
                } + if (job.phase.isNotBlank()) " · ${phaseLabel(job.phase)}" else "",
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
                    TextButton(onClick = actions.cancelJob) { Text("取消任务") }
                }
                if (!state.jobStreaming && !job.isTerminal) {
                    TextButton(onClick = actions.retryJob) { Text("重新获取进度") }
                }
                if (state.jobStreaming) {
                    Text(
                        text = "进度实时更新",
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
                    "${backup.agentCount} 个智能体",
                    scopeSummary(backup.scope),
                    backup.qwenpawVersion.takeIf { it.isNotBlank() }?.let { "服务端 $it" },
                    if (backup.acceptedViaTrust == true) "曾信任导入" else null,
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
                    Text(if (state.detailLoadingId == backup.id) "读取中…" else "详情")
                }
                TextButton(onClick = onExport, enabled = state.exportId == null) { Text("导出") }
                TextButton(onClick = onRestore) { Text("恢复") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            if (detail != null) {
                HorizontalDivider()
                if (detail.workspaceStats.isEmpty()) {
                    Text(
                        text = "该备份不包含智能体工作区。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    detail.workspaceStats.forEach { (agentId, stats) ->
                        Text(
                            text = "${state.agents.firstOrNull { it.id == agentId }?.name?.takeIf { it.isNotBlank() } ?: agentId}" +
                                "：${stats.files} 个文件，${DownloadPolicy.formatBytes(stats.size)}",
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
                TextButton(onClick = onDismissDetail) { Text("收起") }
            }
        }
    }
}

@Composable
private fun RestoreDialog(state: BackupUiState, draft: RestoreDraft, actions: BackupActions) {
    val trustAllowed = draft.trustCode == null || draft.trustMode() != null
    AlertDialog(
        onDismissRequest = actions.dismissRestore,
        title = { Text("恢复备份", fontWeight = FontWeight.SemiBold) },
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
                            text = mode.label,
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
                        text = "智能体工作区",
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
                    title = "全局配置",
                    checked = draft.includeGlobalConfig,
                    onCheckedChange = { actions.updateRestore(draft.copy(includeGlobalConfig = it)) },
                )
                ScopeSwitch(
                    title = "密钥",
                    subtitle = if (draft.secretsAvailable) null else "该备份不包含密钥",
                    checked = draft.includeSecrets && draft.secretsAvailable,
                    enabled = draft.secretsAvailable,
                    onCheckedChange = { actions.updateRestore(draft.copy(includeSecrets = it)) },
                )
                ScopeSwitch(
                    title = "技能池",
                    checked = draft.includeSkillPool,
                    onCheckedChange = { actions.updateRestore(draft.copy(includeSkillPool = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "影响范围",
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
                        state.restoreApplying -> "正在恢复…"
                        draft.trustMode() != null -> "信任并恢复"
                        else -> "恢复"
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
            TextButton(onClick = actions.dismissRestore) { Text("取消") }
        },
    )
}

@Composable
private fun ImportDialogs(state: BackupUiState, actions: BackupActions) {
    when (val importing = state.import) {
        ImportState.Idle -> Unit
        is ImportState.Uploading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("正在导入") },
            text = {
                Column {
                    Text(
                        text = importing.fileName.ifBlank { "备份包" },
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
                            text = "已上传 ${DownloadPolicy.formatBytes(importing.read)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = actions.dismissImport) { Text("取消导入") }
            },
        )

        is ImportState.Saving -> AlertDialog(
            onDismissRequest = { },
            title = { Text("正在写入") },
            text = { Text("服务端正在解包并校验备份，请稍候。") },
            confirmButton = { },
        )

        is ImportState.Conflict -> DestructiveActionDialog(
            title = "备份已存在",
            message = "服务端已有 id 为「${importing.existing.id}」的备份。继续将覆盖该备份。",
            impacts = listOf(
                "已有备份：${importing.existing.name.ifBlank { importing.existing.id }}",
                "创建时间：${TimeLabels.fullStamp(importing.existing.createdAt).orEmpty().ifBlank { "未知" }}",
                "覆盖后无法恢复原有备份。",
            ),
            confirmLabel = "覆盖导入",
            onConfirm = actions.confirmImportOverwrite,
            onDismiss = actions.dismissImport,
        )

        is ImportState.TrustRequired -> AlertDialog(
            onDismissRequest = actions.dismissImport,
            title = { Text(if (importing.canTrust) "需要确认信任" else "无法导入") },
            text = {
                Column {
                    Text(
                        text = importing.message.ifBlank { "该备份的签名无法验证。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (importing.canTrust) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "继续将信任该备份的内容，并以本机密钥重新签名。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (importing.canTrust) {
                    TextButton(onClick = actions.trustImport) { Text("信任并导入") }
                }
            },
            dismissButton = {
                TextButton(onClick = actions.dismissImport) {
                    Text(if (importing.canTrust) "取消" else "关闭")
                }
            },
        )

        is ImportState.Done -> AlertDialog(
            onDismissRequest = actions.dismissImport,
            title = { Text("导入完成") },
            text = {
                Text(
                    text = "已导入备份「${importing.backup.name.ifBlank { importing.backup.id }}」。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = actions.dismissImport) { Text("好") }
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
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

private fun phaseLabel(phase: String): String = when (phase) {
    "preparing" -> "准备中"
    "agents" -> "写入智能体"
    "finalizing" -> "收尾"
    else -> phase
}

private fun scopeSummary(scope: BackupScopeDto): String {
    val parts = mutableListOf<String>()
    if (scope.includeAgents) parts += "智能体"
    if (scope.includeGlobalConfig) parts += "全局配置"
    if (scope.includeSecrets) parts += "密钥"
    if (scope.includeSkillPool) parts += "技能池"
    return if (parts.isEmpty()) "空范围" else parts.joinToString("、")
}
