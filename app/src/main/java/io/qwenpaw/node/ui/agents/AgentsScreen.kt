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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
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
                title = stringResource(R.string.agents_title_manage),
                onBack = onBack,
                action = stringResource(R.string.agents_action_new),
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
                ) { Text(stringResource(R.string.agents_action_dismiss_notice)) }
            }

            exportState?.let { export ->
                SectionHeader(stringResource(R.string.agents_section_export))
                Card {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = when (export.stage) {
                                "creating" -> stringResource(R.string.agents_export_stage_creating)
                                "building" -> stringResource(
                                    R.string.agents_export_stage_building,
                                    export.agentName,
                                )
                                "exporting" -> stringResource(R.string.agents_export_stage_exporting)
                                "done" -> stringResource(R.string.agents_export_stage_done)
                                "cancelled" -> stringResource(R.string.agents_export_stage_cancelled)
                                else -> stringResource(R.string.agents_export_stage_failed)
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
                            TextButton(onClick = onDismissExport) {
                                Text(stringResource(R.string.agents_action_got_it))
                            }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.agents_section_import))
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.agents_import_target_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (portabilityReady) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = if (portabilityReady) {
                                stringResource(R.string.agents_import_subtitle)
                            } else {
                                stringResource(R.string.agents_import_requires_plugin)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onOpenPortability,
                        enabled = portabilityReady,
                    ) { Text(stringResource(R.string.agents_action_start)) }
                }
            }

            SectionHeader(
                title = stringResource(R.string.agents_section_agents),
                trailing = pluralStringResource(
                    R.plurals.agents_count,
                    state.rows.size,
                    state.rows.size,
                ),
            )
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
            title = stringResource(R.string.agents_dialog_create),
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
            title = stringResource(R.string.agents_dialog_edit),
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
        val workspaceDefault = stringResource(R.string.agents_workspace_default)
        DestructiveActionDialog(
            title = stringResource(R.string.agents_dialog_delete),
            message = stringResource(
                R.string.agents_delete_message,
                agent.name.ifBlank { agent.id },
            ),
            impacts = listOf(
                stringResource(
                    R.string.agents_delete_impact_workspace,
                    agent.workspaceDir.ifBlank { workspaceDefault },
                ),
                stringResource(R.string.agents_delete_impact_data),
                stringResource(R.string.agents_delete_impact_no_trash),
            ),
            confirmLabel = stringResource(R.string.action_delete),
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
                if (isDefault) Badge(stringResource(R.string.agents_badge_default))
                if (current) {
                    Spacer(Modifier.width(6.dp))
                    Badge(stringResource(R.string.agents_badge_current))
                }
            }
            Spacer(Modifier.height(4.dp))
            val statusLabel = statusText(row)
            Text(
                text = "${agent.id} · $statusLabel",
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
                    text = stringResource(R.string.agents_label_enabled),
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
                SmallAction(
                    stringResource(R.string.agents_action_move_up),
                    onClick = { if (!isDefault) onMoveUp() },
                    enabled = !isDefault,
                )
                SmallAction(
                    stringResource(R.string.agents_action_move_down),
                    onClick = { if (!isDefault) onMoveDown() },
                    enabled = !isDefault,
                )
                SmallAction(
                    if (agent.pinned) {
                        stringResource(R.string.agents_action_unpin)
                    } else {
                        stringResource(R.string.agents_action_pin)
                    },
                    onClick = { if (!isDefault) onSetPinned(!agent.pinned) },
                    enabled = !isDefault,
                )
                SmallAction(stringResource(R.string.agents_action_use_current), onClick = onUse)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallAction(stringResource(R.string.action_edit), onEdit)
                SmallAction(stringResource(R.string.action_copy), onCopy)
                SmallAction(stringResource(R.string.agents_section_export), onExport)
                SmallAction(stringResource(R.string.action_delete), onDelete, enabled = !isDefault)
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

@Composable
private fun statusText(row: AgentRow): String {
    val agent = row.agent
    val status = row.status
    return when {
        !agent.enabled || status?.status == "disabled" ->
            stringResource(R.string.agents_state_disabled)
        status?.status == "running" -> pluralStringResource(
            R.plurals.agents_status_running,
            status.runningTaskCount,
            status.runningTaskCount,
        )
        status == null -> stringResource(R.string.agents_status_unknown)
        else -> stringResource(R.string.agents_status_idle)
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
    // 提交回调不在组合上下文里，文案先在组合期取好。
    val idInvalidText = stringResource(R.string.agents_error_id_invalid)
    val operationFailedText = stringResource(R.string.agents_error_operation_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.agents_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showId) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it; error = null },
                        label = { Text(stringResource(R.string.agents_field_id_optional)) },
                        supportingText = {
                            Text(stringResource(R.string.agents_field_id_hint))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.agents_field_description_optional)) },
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
                            error = idInvalidText
                            return@TextButton
                        }
                    }
                    onConfirm(name, id, description) { ok ->
                        if (!ok) error = operationFailedText
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CopyDialog(
    agent: AgentDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, skills: Boolean, jobs: Boolean, onResult: (Boolean) -> Unit) -> Unit,
) {
    val defaultName = stringResource(R.string.agents_copy_name_suffix, agent.name.ifBlank { agent.id })
    var name by remember { mutableStateOf(defaultName) }
    var skills by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 提交回调不在组合上下文里，文案先在组合期取好。
    val copyFailedText = stringResource(R.string.agents_error_copy_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agents_dialog_copy)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.agents_field_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = stringResource(R.string.agents_copy_skills_title),
                    subtitle = stringResource(R.string.agents_copy_skills_subtitle),
                    checked = skills,
                    onCheckedChange = { skills = it },
                )
                SwitchRow(
                    title = stringResource(R.string.agents_copy_jobs_title),
                    subtitle = stringResource(R.string.agents_copy_jobs_subtitle),
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
                        if (!ok) error = copyFailedText
                    }
                },
            ) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
