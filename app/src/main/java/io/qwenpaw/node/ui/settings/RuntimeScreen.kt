package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.clickable
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
import io.qwenpaw.node.data.model.HarnessProviderDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.DestructiveActionDialog

private const val TAB_ACP = 0
private const val TAB_HARNESS = 1

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
    onDismissHarnessLogin: () -> Unit,
    onDismissHarnessDetail: () -> Unit,
    onDismissAcpNotice: () -> Unit,
    onDismissHarnessNotice: () -> Unit,
) {
    var tab by remember { mutableStateOf(TAB_ACP) }
    var editing by remember { mutableStateOf<AcpAgentForm?>(null) }
    var logoutTarget by remember { mutableStateOf<HarnessProviderDto?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { onEnsureLoaded() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AdminTopBar(
                title = "运行时",
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
            }

            if (tab == TAB_ACP) {
                AcpTab(
                    state = acp,
                    onSaveNodePath = onSaveNodePath,
                    onEdit = { editing = it },
                    onDismissNotice = onDismissAcpNotice,
                )
            } else {
                HarnessTab(
                    state = harness,
                    onProbe = onProbeHarness,
                    onLogin = onLoginHarness,
                    onLogout = { logoutTarget = it },
                    onDetail = onOpenHarnessDetail,
                    onDismissNotice = onDismissHarnessNotice,
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
                            text = "$key：$value",
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
                                text = "等待授权完成，最长等待 2 分钟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                login.url?.let { url ->
                    TextButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ),
                        )
                    }) { Text("打开验证页面") }
                }
            },
            dismissButton = { TextButton(onClick = onDismissHarnessLogin) { Text("关闭") } },
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
                TextButton(onClick = onDismissHarnessDetail) { Text("关闭") }
            },
        )
    }

    logoutTarget?.let { provider ->
        DestructiveActionDialog(
            title = "登出 ${provider.name.ifBlank { provider.id }}",
            message = "将清除该运行时在服务端保存的登录状态。",
            impacts = listOf("该运行时的模型与能力需要重新登录后才能使用"),
            confirmLabel = "登出",
            confirmDanger = false,
            onConfirm = {
                onLogoutHarness(provider.id)
                logoutTarget = null
            },
            onDismiss = { logoutTarget = null },
        )
    }
}

@Composable
private fun AcpTab(
    state: AcpUiState,
    onSaveNodePath: (String) -> Unit,
    onEdit: (AcpAgentForm) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var nodePath by remember(state.nodePath) { mutableStateOf(state.nodePath) }

    SectionHeader("说明")
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "ACP 的命令与参数会在服务端执行任意命令；关闭 trusted 会改变审批行为。修改前请确认命令来源。",
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
        TextButton(onClick = onDismissNotice) { Text("清除提示") }
    }

    SectionHeader("Node 运行时")
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            state.nodeRuntime?.effectiveNodePath?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "当前生效：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = nodePath,
                onValueChange = { nodePath = it },
                label = { Text("Node 路径") },
                supportingText = { Text("留空使用自动探测的结果") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = !state.saving,
                onClick = { onSaveNodePath(nodePath) },
            ) { Text("保存 Node 路径") }
            state.nodeRuntime?.candidates?.take(6)?.forEach { candidate ->
                Text(
                    text = "${candidate.label} · ${candidate.nodeVersion.ifBlank { "版本未知" }}" +
                        if (candidate.available) "" else " · 不可用：${candidate.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SectionHeader("内置 ACP 智能体", "${state.agents.size} 个")
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
                        Text(
                            text = "${if (form.enabled) "已启用" else "已停用"} · ${form.command.ifBlank { "未设置命令" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onEdit(form) }) { Text("编辑") }
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
    onDismissNotice: () -> Unit,
) {
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
        TextButton(onClick = onDismissNotice) { Text("清除提示") }
    }

    SectionHeader("外部智能体运行时", "${state.providers.size} 个")
    state.providers.forEach { provider ->
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.name.ifBlank { provider.id },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = harnessStateText(provider),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                !provider.available || !provider.installed -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        enabled = provider.available,
                        onClick = { onProbe(provider.id) },
                    ) { Text("探测") }
                    TextButton(
                        enabled = provider.available && provider.installed && !provider.authenticated,
                        onClick = { onLogin(provider.id) },
                    ) { Text("登录") }
                    TextButton(
                        enabled = provider.authenticated,
                        onClick = { onLogout(provider) },
                    ) { Text("登出") }
                }
                if (provider.available && provider.installed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { onDetail(provider.id, "models") }) { Text("模型") }
                        TextButton(onClick = { onDetail(provider.id, "mcp") }) { Text("MCP") }
                        TextButton(onClick = { onDetail(provider.id, "skills") }) { Text("技能") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun harnessStateText(provider: HarnessProviderDto): String = when {
    provider.comingSoon -> "尚未开放"
    !provider.available -> "当前版本不可用"
    !provider.installed -> "未安装：服务端缺少运行时"
    provider.authenticated -> "已登录"
    else -> "未登录"
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

    fun validateAndSubmit() {
        val mb = buffer.trim().toLongOrNull()
        if (mb == null || mb <= 0) {
            error = "缓冲区必须是大于 0 的整数（MB）"
            return
        }
        if (mode !in listOf("call_title", "update_detail", "call_detail")) {
            error = "工具解析模式取值无效"
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
        title = { Text("编辑 ${form.name}") },
        text = {
            Column(
                modifier = Modifier
                    .height(520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("参数（每行一个）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("环境变量", style = MaterialTheme.typography.labelMedium)
                env.forEachIndexed { index, entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = entry.first,
                            onValueChange = { value ->
                                env = env.toMutableList().also { it[index] = value to entry.second }
                            },
                            label = { Text("键") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = entry.second,
                            onValueChange = { value ->
                                env = env.toMutableList().also { it[index] = entry.first to value }
                            },
                            label = { Text("值") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            env = env.toMutableList().also { it.removeAt(index) }
                        }) { Text("删除") }
                    }
                }
                TextButton(onClick = { env = env + ("" to "") }) { Text("添加环境变量") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("信任该命令", modifier = Modifier.weight(1f))
                    Switch(checked = trusted, onCheckedChange = { trusted = it })
                }
                Text(
                    text = "关闭 trusted 会改变审批行为，仅在确认命令来源可信时开启。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text("工具解析模式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "call_title" to "标题",
                        "update_detail" to "更新详情",
                        "call_detail" to "调用详情",
                    ).forEach { (code, label) ->
                        Chip(label, selected = mode == code, onClick = { mode = code })
                    }
                }
                OutlinedTextField(
                    value = buffer,
                    onValueChange = { buffer = it },
                    label = { Text("stdio 缓冲区（MB）") },
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
            TextButton(enabled = !busy, onClick = { validateAndSubmit() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
