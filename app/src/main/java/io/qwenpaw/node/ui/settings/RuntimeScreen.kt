package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import io.qwenpaw.node.data.model.HarnessProviderDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.pageContentInsets
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAB_ACP = 0
private const val TAB_HARNESS = 1
private const val TAB_AGENT = 2

/**
 * 运行时：ACP 表单与 Harness 登录。
 *
 * ACP 的命令与参数会在服务端执行任意命令，顶部常驻风险说明；Harness 把
 * 「未安装 / 未登录 / 已登录」三态分开，避免用户去登录一个不存在的运行时。
 */
@Composable
fun RuntimeScreen(
    acp: AcpUiState,
    harness: HarnessUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onSaveNodePath: (String) -> Unit,
    onSaveAcpAgent: (AcpAgentForm) -> Unit,
    onProbeHarness: (String) -> Unit,
    onLoginHarness: (String) -> Unit,
    onLogoutHarness: (String) -> Unit,
    onOpenHarnessDetail: (String, String) -> Unit,
    /** 未安装的后端：跳市场并预填安装地址。 */
    onInstallHarness: (String) -> Unit = {},
    onDismissHarnessLogin: () -> Unit,
    onDismissHarnessDetail: () -> Unit,
    onDismissAcpNotice: () -> Unit,
    onDismissHarnessNotice: () -> Unit,
    agentExtras: AgentExtrasUiState = AgentExtrasUiState(),
    onLoadAgentExtras: () -> Unit = {},
    onPromptFile: (String, Boolean) -> Unit = { _, _ -> },
    onAgentLanguage: (String) -> Unit = {},
    onCodingEnabled: (Boolean) -> Unit = {},
    onAdvisorEnabled: (Boolean) -> Unit = {},
    onAdvisorFlag: (String, Boolean) -> Unit = { _, _ -> },
    onAdvisorNumbers: (Int?, Int?) -> Unit = { _, _ -> },
    onDismissAgentExtrasNotice: () -> Unit = {},
    onLoopCreate: () -> Unit = {},
    onLoopEdit: (String) -> Unit = {},
    onLoopDelete: (String) -> Unit = {},
    onLoopDuplicate: (String) -> Unit = {},
    onLoopDraft: (String) -> Unit = {},
    onLoopSave: () -> Unit = {},
    onLoopClose: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(TAB_ACP) }
    var editing by remember { mutableStateOf<AcpAgentForm?>(null) }
    var logoutTarget by remember { mutableStateOf<HarnessProviderDto?>(null) }
    val context = LocalContext.current
    val confirmBrowser = io.qwenpaw.node.ui.common.rememberBrowserConfirm()

    LaunchedEffect(Unit) { onEnsureLoaded() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = pageContentInsets(),
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.runtime_title),
                onBack = onBack,
                busy = acp.saving || harness.saving,
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
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("ACP", selected = tab == TAB_ACP, onClick = { tab = TAB_ACP })
                Chip("Harness", selected = tab == TAB_HARNESS, onClick = { tab = TAB_HARNESS })
                Chip(
                    stringResource(R.string.runtime_tab_agent),
                    selected = tab == TAB_AGENT,
                    onClick = {
                        tab = TAB_AGENT
                        onLoadAgentExtras()
                    },
                )
            }

            when (tab) {
                TAB_ACP -> AcpTab(
                    state = acp,
                    onSaveNodePath = onSaveNodePath,
                    onEdit = { editing = it },
                    onDismissNotice = onDismissAcpNotice,
                )
                TAB_HARNESS -> HarnessTab(
                    state = harness,
                    onProbe = onProbeHarness,
                    onLogin = onLoginHarness,
                    onLogout = { logoutTarget = it },
                    onDetail = onOpenHarnessDetail,
                    onInstall = onInstallHarness,
                    onDismissNotice = onDismissHarnessNotice,
                )
                else -> AgentExtrasTab(
                    state = agentExtras,
                    onPromptFile = onPromptFile,
                    onLanguage = onAgentLanguage,
                    onCoding = onCodingEnabled,
                    onAdvisor = onAdvisorEnabled,
                    onAdvisorFlag = onAdvisorFlag,
                    onAdvisorNumbers = onAdvisorNumbers,
                    onLoopCreate = onLoopCreate,
                    onLoopEdit = onLoopEdit,
                    onLoopDelete = onLoopDelete,
                    onLoopDuplicate = onLoopDuplicate,
                    onDismissNotice = onDismissAgentExtrasNotice,
                )
            }
        }
    }

    editing?.let { form ->
        AcpAgentDialog(
            form = form,
            busy = acp.saving,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                onSaveAcpAgent(updated)
                editing = null
            },
        )
    }

    harness.login?.let { login ->
        AlertDialog(
            onDismissRequest = onDismissHarnessLogin,
            title = { Text(login.title) },
            text = {
                Column {
                    login.fields.forEach { (key, value) ->
                        Text(
                            text = stringResource(R.string.runtime_login_field, key, value),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    if (harness.waitingLogin != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .width(16.dp)
                                    .height(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.runtime_waiting_login),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                login.url?.let { url ->
                    TextButton(onClick = { confirmBrowser(url) }) {
                        Text(stringResource(R.string.runtime_open_verify_page))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissHarnessLogin) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    harness.detail?.let { detail ->
        AlertDialog(
            onDismissRequest = onDismissHarnessDetail,
            title = { Text(detail.title) },
            text = {
                Column(
                    modifier = Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    detail.lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissHarnessDetail) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    logoutTarget?.let { provider ->
        DestructiveActionDialog(
            title = stringResource(
                R.string.runtime_logout_title,
                provider.name.ifBlank { provider.id },
            ),
            message = stringResource(R.string.runtime_logout_message),
            impacts = listOf(stringResource(R.string.runtime_logout_impact)),
            confirmLabel = stringResource(R.string.runtime_action_logout),
            confirmDanger = false,
            onConfirm = {
                onLogoutHarness(provider.id)
                logoutTarget = null
            },
            onDismiss = { logoutTarget = null },
        )
    }

    if (agentExtras.customLoopDraft != null) {
        LoopEditorDialog(
            draft = agentExtras.customLoopDraft,
            saving = agentExtras.customLoopSaving,
            error = agentExtras.customLoopError,
            onDraft = onLoopDraft,
            onSave = onLoopSave,
            onDismiss = onLoopClose,
        )
    }
}

@Composable
private fun LoopEditorDialog(
    draft: String,
    saving: Boolean,
    error: String?,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.runtime_loop_editor_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp),
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.runtime_loop_editor_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onSave) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun AcpTab(
    state: AcpUiState,
    onSaveNodePath: (String) -> Unit,
    onEdit: (AcpAgentForm) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var nodePath by remember(state.nodePath) { mutableStateOf(state.nodePath) }

    SectionHeader(stringResource(R.string.runtime_section_note))
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.runtime_acp_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    state.error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    state.notice?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    if (state.error != null || state.notice != null) {
        TextButton(onClick = onDismissNotice) {
            Text(stringResource(R.string.runtime_action_clear_notice))
        }
    }

    SectionHeader(stringResource(R.string.runtime_section_node_runtime))
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            state.nodeRuntime?.effectiveNodePath?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.runtime_node_effective, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = nodePath,
                onValueChange = { nodePath = it },
                label = { Text(stringResource(R.string.runtime_node_path_label)) },
                supportingText = { Text(stringResource(R.string.runtime_node_path_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = !state.saving,
                onClick = { onSaveNodePath(nodePath) },
            ) { Text(stringResource(R.string.runtime_action_save_node_path)) }
            state.nodeRuntime?.candidates?.take(6)?.forEach { candidate ->
                val version = if (candidate.nodeVersion.isBlank()) {
                    stringResource(R.string.runtime_node_version_unknown)
                } else {
                    candidate.nodeVersion
                }
                Text(
                    text = if (candidate.available) {
                        stringResource(R.string.runtime_pair, candidate.label, version)
                    } else {
                        stringResource(
                            R.string.runtime_node_candidate_unavailable,
                            candidate.label,
                            version,
                            candidate.reason,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SectionHeader(
        stringResource(R.string.runtime_section_builtin_agents),
        pluralStringResource(
            R.plurals.runtime_item_count,
            state.agents.size,
            state.agents.size,
        ),
    )
    state.agents.forEach { form ->
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = form.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        val status = stringResource(
                            if (form.enabled) {
                                R.string.runtime_agent_enabled
                            } else {
                                R.string.runtime_agent_disabled
                            },
                        )
                        val command = if (form.command.isBlank()) {
                            stringResource(R.string.runtime_agent_command_unset)
                        } else {
                            form.command
                        }
                        Text(
                            text = stringResource(R.string.runtime_pair, status, command),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onEdit(form) }) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HarnessTab(
    state: HarnessUiState,
    onProbe: (String) -> Unit,
    onLogin: (String) -> Unit,
    onLogout: (HarnessProviderDto) -> Unit,
    onDetail: (String, String) -> Unit,
    onInstall: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val confirmBrowser = io.qwenpaw.node.ui.common.rememberBrowserConfirm()
    state.error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    state.notice?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    if (state.error != null || state.notice != null) {
        TextButton(onClick = onDismissNotice) {
            Text(stringResource(R.string.runtime_action_clear_notice))
        }
    }

    SectionHeader(
        stringResource(R.string.runtime_section_external_runtimes),
        pluralStringResource(
            R.plurals.runtime_item_count,
            state.providers.size,
            state.providers.size,
        ),
    )
    state.providers.forEach { provider ->
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = harnessStateText(provider),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                !provider.isAvailable || !provider.installed -> MaterialTheme.colorScheme.onSurfaceVariant
                                provider.authenticated -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                provider.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        enabled = provider.isAvailable,
                        onClick = { onProbe(provider.id) },
                    ) { Text(stringResource(R.string.runtime_action_probe)) }
                    TextButton(
                        enabled = provider.isAvailable && provider.installed && !provider.authenticated,
                        onClick = { onLogin(provider.id) },
                    ) { Text(stringResource(R.string.runtime_action_login)) }
                    TextButton(
                        enabled = provider.authenticated,
                        onClick = { onLogout(provider) },
                    ) { Text(stringResource(R.string.runtime_action_logout)) }
                }
                if (provider.isAvailable && provider.installed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { onDetail(provider.id, "models") }) {
                            Text(stringResource(R.string.runtime_tab_models))
                        }
                        TextButton(onClick = { onDetail(provider.id, "mcp") }) { Text("MCP") }
                        TextButton(onClick = { onDetail(provider.id, "skills") }) {
                            Text(stringResource(R.string.runtime_tab_skills))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (state.installable.isNotEmpty()) {
        SectionHeader(
            stringResource(R.string.runtime_section_installable),
            stringResource(R.string.runtime_install_hint),
        )
        state.installable.forEach { entry ->
            androidx.compose.material3.Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = entry.name.ifBlank { entry.pluginId },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (entry.description.isNotBlank()) {
                        Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onInstall(entry.installUrl) }) {
                        Text(stringResource(R.string.runtime_action_install))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun harnessStateText(provider: HarnessProviderDto): String = when (provider.status) {
    "disabled" -> stringResource(R.string.runtime_state_plugin_disabled)
    "failed" -> stringResource(R.string.runtime_state_plugin_failed)
    else -> when {
        provider.comingSoon -> stringResource(R.string.runtime_state_coming_soon)
        !provider.isAvailable -> stringResource(R.string.runtime_state_unavailable)
        // 新形状的列表没有 installed/authenticated，探测前不能直接说「未安装」。
        provider.status != null && !provider.installed && !provider.authenticated ->
            stringResource(R.string.runtime_state_detect_login)
        !provider.installed -> stringResource(R.string.runtime_state_not_installed)
        provider.authenticated -> stringResource(R.string.runtime_state_logged_in)
        else -> stringResource(R.string.runtime_state_logged_out)
    }
}

@Composable
private fun AcpAgentDialog(
    form: AcpAgentForm,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AcpAgentForm) -> Unit,
) {
    var enabled by remember { mutableStateOf(form.enabled) }
    var command by remember { mutableStateOf(form.command) }
    var args by remember { mutableStateOf(form.argsText) }
    var env by remember { mutableStateOf(form.env) }
    var trusted by remember { mutableStateOf(form.trusted) }
    var mode by remember { mutableStateOf(form.toolParseMode) }
    var buffer by remember { mutableStateOf(form.bufferMb) }
    var error by remember { mutableStateOf<String?>(null) }

    val bufferError = stringResource(R.string.runtime_error_buffer_invalid)
    val toolModeError = stringResource(R.string.runtime_error_tool_mode_invalid)

    fun validateAndSubmit() {
        val mb = buffer.trim().toLongOrNull()
        if (mb == null || mb <= 0) {
            error = bufferError
            return
        }
        if (mode !in listOf("call_title", "update_detail", "call_detail")) {
            error = toolModeError
            return
        }
        onConfirm(
            form.copy(
                enabled = enabled,
                command = command.trim(),
                argsText = args,
                env = env.filter { it.first.isNotBlank() },
                trusted = trusted,
                toolParseMode = mode,
                bufferMb = buffer.trim(),
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_agent_edit_title, form.name)) },
        text = {
            Column(
                modifier = Modifier
                    .height(520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.runtime_label_enabled),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.runtime_label_command)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.runtime_label_args)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.runtime_label_env),
                    style = MaterialTheme.typography.labelMedium,
                )
                env.forEachIndexed { index, entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = entry.first,
                            onValueChange = { value ->
                                env = env.toMutableList().also { it[index] = value to entry.second }
                            },
                            label = { Text(stringResource(R.string.runtime_label_env_key)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = entry.second,
                            onValueChange = { value ->
                                env = env.toMutableList().also { it[index] = entry.first to value }
                            },
                            label = { Text(stringResource(R.string.runtime_label_env_value)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            env = env.toMutableList().also { it.removeAt(index) }
                        }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
                TextButton(onClick = { env = env + ("" to "") }) {
                    Text(stringResource(R.string.runtime_action_add_env))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.runtime_label_trust),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = trusted, onCheckedChange = { trusted = it })
                }
                Text(
                    text = stringResource(R.string.runtime_trust_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.runtime_label_tool_mode),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "call_title" to stringResource(R.string.runtime_mode_title),
                        "update_detail" to stringResource(R.string.runtime_mode_update_detail),
                        "call_detail" to stringResource(R.string.runtime_mode_call_detail),
                    ).forEach { (code, label) ->
                        Chip(label, selected = mode == code, onClick = { mode = code })
                    }
                }
                OutlinedTextField(
                    value = buffer,
                    onValueChange = { buffer = it },
                    label = { Text(stringResource(R.string.runtime_label_buffer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
            TextButton(enabled = !busy, onClick = { validateAndSubmit() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 智能体附加设置：系统提示文件、MD 语言、编码模式与顾问模式。 */
@Composable
private fun AgentExtrasTab(
    state: AgentExtrasUiState,
    onPromptFile: (String, Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onCoding: (Boolean) -> Unit,
    onAdvisor: (Boolean) -> Unit,
    onAdvisorFlag: (String, Boolean) -> Unit,
    onAdvisorNumbers: (Int?, Int?) -> Unit,
    onLoopCreate: () -> Unit,
    onLoopEdit: (String) -> Unit,
    onLoopDelete: (String) -> Unit,
    onLoopDuplicate: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var pendingLoopDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    state.notice?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismissNotice)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    SectionHeader(stringResource(R.string.runtime_agent_prompt_title))
    Card {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.runtime_agent_prompt_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.availableFiles.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.runtime_agent_files_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.availableFiles.take(20).forEach { file ->
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = file,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = file in state.promptFiles,
                    enabled = !state.saving,
                    onCheckedChange = { onPromptFile(file, it) },
                )
            }
        }
    }

    SectionHeader(stringResource(R.string.runtime_agent_language_title))
    Card {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            var draft by remember(state.language) { mutableStateOf(state.language) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.runtime_agent_language_title)) },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = !state.saving,
                    onClick = { onLanguage(draft) },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Text(
                text = stringResource(R.string.runtime_agent_language_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SectionHeader(stringResource(R.string.runtime_agent_modes_title))
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.runtime_agent_coding_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.runtime_agent_coding_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.codingEnabled,
                enabled = !state.saving,
                onCheckedChange = onCoding,
            )
        }
        Divider()
        val advisorEnabled = state.advisor?.get("enabled")
            ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.runtime_agent_advisor_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.runtime_agent_advisor_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = advisorEnabled,
                enabled = !state.saving,
                onCheckedChange = onAdvisor,
            )
        }
        listOf(
            "plan_enabled" to R.string.runtime_agent_advisor_plan,
            "followup_enabled" to R.string.runtime_agent_advisor_followup,
            "on_demand_enabled" to R.string.runtime_agent_advisor_on_demand,
        ).forEach { (field, label) ->
            Divider()
            val checked = state.advisor?.get(field)?.jsonPrimitive?.booleanOrNull ?: false
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = checked,
                    enabled = !state.saving,
                    onCheckedChange = { onAdvisorFlag(field, it) },
                )
            }
        }
        Divider()
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            var maxConsultsDraft by remember(state.advisor) {
                mutableStateOf(
                    state.advisor?.get("max_consults")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            var recentDraft by remember(state.advisor) {
                mutableStateOf(
                    state.advisor?.get("recent_messages")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = maxConsultsDraft,
                    onValueChange = { maxConsultsDraft = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.runtime_agent_advisor_max_consults)) },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = recentDraft,
                    onValueChange = { recentDraft = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.runtime_agent_advisor_recent_messages)) },
                )
            }
            TextButton(
                enabled = !state.saving,
                onClick = {
                    onAdvisorNumbers(
                        maxConsultsDraft.trim().toIntOrNull(),
                        recentDraft.trim().toIntOrNull(),
                    )
                },
            ) {
                Text(stringResource(R.string.runtime_agent_advisor_numbers_save))
            }
        }
    }

    SectionHeader(stringResource(R.string.runtime_agent_loops_title))
    Card {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.runtime_agent_loops_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.loops.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.runtime_agent_loops_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.loops.forEach { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                val description = obj["description"]?.jsonPrimitive?.contentOrNull
                Spacer(Modifier.height(6.dp))
                Text(text = name, style = MaterialTheme.typography.bodyMedium)
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.customLoops.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.runtime_agent_custom_loops),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.customLoops.forEach { element ->
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                    Text(text = name, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            enabled = !state.saving,
                            onClick = { onLoopEdit(id) },
                        ) {
                            Text(stringResource(R.string.runtime_loop_edit))
                        }
                        TextButton(
                            enabled = !state.saving,
                            onClick = { onLoopDuplicate(id) },
                        ) {
                            Text(stringResource(R.string.runtime_loop_duplicate))
                        }
                        TextButton(
                            enabled = !state.saving,
                            onClick = { pendingLoopDelete = id to name },
                        ) {
                            Text(
                                text = stringResource(R.string.runtime_loop_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                enabled = !state.saving,
                onClick = onLoopCreate,
            ) {
                Text(stringResource(R.string.runtime_loop_create))
            }
        }
    }
    Spacer(Modifier.height(24.dp))

    pendingLoopDelete?.let { (id, name) ->
        DestructiveActionDialog(
            title = stringResource(R.string.runtime_loop_delete_title),
            message = stringResource(R.string.runtime_loop_delete_message, name),
            confirmLabel = stringResource(R.string.action_delete),
            undoable = false,
            onConfirm = {
                pendingLoopDelete = null
                onLoopDelete(id)
            },
            onDismiss = { pendingLoopDelete = null },
        )
    }
}
