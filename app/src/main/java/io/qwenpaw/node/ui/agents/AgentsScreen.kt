package io.qwenpaw.node.ui.agents

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.ui.backup.AgentExportUiState
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.settings.SwitchRow
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.DownloadSink
import io.qwenpaw.node.ui.workspace.SingleFileSink

/**
 * 智能体管理。
 *
 * 删除与禁用都由服务端兜底（default 不可删、不可禁用），界面按同样的规则置灰，
 * 避免用户点了才看到服务端报错。
 */
@Composable
fun AgentsScreen(
    state: AgentsUiState,
    exportState: AgentExportUiState?,
    portabilityReady: Boolean,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onCreate: (name: String, description: String, id: String, onResult: (Boolean) -> Unit) -> Unit,
    onCopy: (sourceId: String, name: String, copySkills: Boolean, copyJobs: Boolean, onResult: (Boolean) -> Unit) -> Unit,
    onRename: (agentId: String, name: String, description: String, onResult: (Boolean) -> Unit) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onUseAsCurrent: (String) -> Unit,
    onExport: (AgentDto, DownloadSink) -> Unit,
    onDismissExport: () -> Unit,
    onOpenPortability: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AgentDto?>(null) }
    var copying by remember { mutableStateOf<AgentDto?>(null) }
    var deleting by remember { mutableStateOf<AgentDto?>(null) }
    var exportTarget by remember { mutableStateOf<AgentDto?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            onExport(target, SingleFileSink(context.contentResolver, uri))
        }
    }

    LaunchedEffect(Unit) { onEnsureLoaded() }
    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AdminTopBar(
                title = "智能体管理",
                onBack = onBack,
                action = "新建",
                busy = state.busy,
                onAction = { creating = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (state.loading && !state.loaded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            state.notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            if (state.error != null || state.notice != null) {
                TextButton(
                    onClick = onDismissNotice,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) { Text("清除提示") }
            }

            exportState?.let { export ->
                SectionHeader("导出")
                Card {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = when (export.stage) {
                                "creating" -> "正在创建临时备份…"
                                "building" -> "正在打包智能体「${export.agentName}」…"
                                "exporting" -> "正在写入所选位置…"
                                "done" -> "导出完成"
                                "cancelled" -> "导出已取消"
                                else -> "导出失败"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        export.error?.let { message ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (export.stage == "failed" || export.stage == "done") {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (!export.busy) {
                            TextButton(onClick = onDismissExport) { Text("知道了") }
                        }
                    }
                }
            }

            SectionHeader("从其他客户端导入")
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "导入到当前智能体",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (portabilityReady) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = if (portabilityReady) {
                                "从 Codex / Qoder 等客户端导入会话、记忆与技能"
                            } else {
                                "需要配套插件 v0.2.0 或更高（portability 能力）"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onOpenPortability,
                        enabled = portabilityReady,
                    ) { Text("开始") }
                }
            }

            SectionHeader("智能体", "${state.rows.size} 个")
            state.rows.forEach { row ->
                AgentCard(
                    row = row,
                    current = row.agent.id == state.currentAgentId,
                    busy = state.busy,
                    onSetEnabled = { onSetEnabled(row.agent.id, it) },
                    onSetPinned = { onSetPinned(row.agent.id, it) },
                    onMoveUp = { onMove(row.agent.id, -1) },
                    onMoveDown = { onMove(row.agent.id, 1) },
                    onUse = { onUseAsCurrent(row.agent.id) },
                    onEdit = { editing = row.agent },
                    onCopy = { copying = row.agent },
                    onExport = {
                        exportTarget = row.agent
                        exportLauncher.launch("QwenPaw-agent-${row.agent.id}.zip")
                    },
                    onDelete = { deleting = row.agent },
                )
            }
        }
    }

    if (creating) {
        AgentFormDialog(
            title = "新建智能体",
            showId = true,
            busy = state.busy,
            onDismiss = { creating = false },
            onConfirm = { name, id, description, done ->
                onCreate(name, description, id) { ok ->
                    if (ok) creating = false
                    done(ok)
                }
            },
        )
    }

    editing?.let { agent ->
        AgentFormDialog(
            title = "编辑智能体",
            initialName = agent.name,
            initialDescription = agent.description,
            showId = false,
            busy = state.busy,
            onDismiss = { editing = null },
            onConfirm = { name, _, description, done ->
                onRename(agent.id, name, description) { ok ->
                    if (ok) editing = null
                    done(ok)
                }
            },
        )
    }

    copying?.let { agent ->
        CopyDialog(
            agent = agent,
            busy = state.busy,
            onDismiss = { copying = null },
            onConfirm = { name, skills, jobs, done ->
                onCopy(agent.id, name, skills, jobs) { ok ->
                    if (ok) copying = null
                    done(ok)
                }
            },
        )
    }

    deleting?.let { agent ->
        DestructiveActionDialog(
            title = "删除智能体",
            message = "将删除「${agent.name.ifBlank { agent.id }}」及其工作区。",
            impacts = listOf(
                "工作区目录：${agent.workspaceDir.ifBlank { "服务端默认位置" }}",
                "该智能体的会话、定时任务与技能",
                "服务端没有回收站，删除后无法恢复",
            ),
            confirmLabel = "删除",
            onConfirm = {
                onDelete(agent.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AgentCard(
    row: AgentRow,
    current: Boolean,
    busy: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val agent = row.agent
    val isDefault = agent.id == "default"
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agent.name.ifBlank { agent.id },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (isDefault) Badge("默认")
                if (current) {
                    Spacer(Modifier.width(6.dp))
                    Badge("当前实例")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${agent.id} · ${statusText(row)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (!agent.enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "启用",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    color = if (isDefault) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Switch(
                    checked = agent.enabled,
                    enabled = !isDefault && !busy,
                    onCheckedChange = onSetEnabled,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallAction("上移", onClick = { if (!isDefault) onMoveUp() }, enabled = !isDefault)
                SmallAction("下移", onClick = { if (!isDefault) onMoveDown() }, enabled = !isDefault)
                SmallAction(
                    if (agent.pinned) "取消置顶" else "置顶",
                    onClick = { if (!isDefault) onSetPinned(!agent.pinned) },
                    enabled = !isDefault,
                )
                SmallAction("设为当前", onClick = onUse)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallAction("编辑", onEdit)
                SmallAction("复制", onCopy)
                SmallAction("导出", onExport)
                SmallAction("删除", onDelete, enabled = !isDefault)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .then(Modifier),
    )
}

@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun statusText(row: AgentRow): String {
    val agent = row.agent
    val status = row.status
    return when {
        !agent.enabled || status?.status == "disabled" -> "已禁用"
        status?.status == "running" -> "运行中 · ${status.runningTaskCount} 个任务"
        status == null -> "状态未知"
        else -> "空闲"
    }
}

@Composable
private fun AgentFormDialog(
    title: String,
    showId: Boolean,
    busy: Boolean,
    initialName: String = "",
    initialId: String = "",
    initialDescription: String = "",
    onDismiss: () -> Unit,
    onConfirm: (name: String, id: String, description: String, onResult: (Boolean) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var id by remember { mutableStateOf(initialId) }
    var description by remember { mutableStateOf(initialDescription) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showId) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it; error = null },
                        label = { Text("标识（可留空）") },
                        supportingText = { Text("2–64 位字母、数字、下划线或连字符；不可为 default") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    if (showId && id.isNotBlank()) {
                        val valid = id.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}[a-zA-Z0-9]$")) &&
                            id != "default"
                        if (!valid) {
                            error = "标识格式不正确"
                            return@TextButton
                        }
                    }
                    onConfirm(name, id, description) { ok ->
                        if (!ok) error = "操作失败，请查看上方提示"
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CopyDialog(
    agent: AgentDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, skills: Boolean, jobs: Boolean, onResult: (Boolean) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("${agent.name.ifBlank { agent.id }} 副本") }
    var skills by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("复制智能体") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "复制技能",
                    subtitle = "把该智能体的技能一并复制到新智能体",
                    checked = skills,
                    onCheckedChange = { skills = it },
                )
                SwitchRow(
                    title = "复制定时任务",
                    subtitle = "把该智能体的定时任务一并复制",
                    checked = jobs,
                    onCheckedChange = { jobs = it },
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onConfirm(name, skills, jobs) { ok ->
                        if (!ok) error = "复制失败，请查看上方提示"
                    }
                },
            ) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
