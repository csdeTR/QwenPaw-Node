package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.DestructiveActionDialog

/** 安全页的四个开关。确认弹窗只关心「被关闭的是哪一组」。 */
private enum class GuardKind { TOOL, FILE, SANDBOX }

/**
 * 安全页：工具防护、文件防护、沙箱、技能扫描器。
 *
 * 只做开关与只读规则浏览；规则编辑仍留 Web 控制台。关闭任一组防护都要二次确认，
 * 因为这些开关关闭后不会立刻有可见变化，用户很容易忘记自己关过什么。
 */
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onToolGuard: (Boolean) -> Unit,
    onFileGuard: (Boolean) -> Unit,
    onSandbox: (Boolean) -> Unit,
    onSkillScanner: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var pendingDisable by remember { mutableStateOf<GuardKind?>(null) }
    var showRules by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onEnsureLoaded() }

    val confirmText = when (pendingDisable) {
        GuardKind.TOOL -> "关闭工具防护后，内置的危险命令检查不再拦截工具调用。"
        GuardKind.FILE -> "关闭文件防护后，敏感文件的读写不再经过防护检查。"
        GuardKind.SANDBOX -> "关闭沙箱后，命令将在宿主机环境中直接执行。"
        null -> ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AdminTopBar(title = "安全", onBack = onBack) },
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

            SectionHeader("工具防护")
            Card {
                SwitchRow(
                    title = "拦截危险工具调用",
                    subtitle = "按内置规则检查命令注入、破坏性操作等风险",
                    checked = state.toolGuardEnabled,
                    enabled = !state.saving,
                    onCheckedChange = { enabled ->
                        if (enabled) onToolGuard(true) else pendingDisable = GuardKind.TOOL
                    },
                )
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = "内置规则 ${state.rules.size} 条",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showRules = !showRules }) {
                        Text(if (showRules) "收起" else "查看")
                    }
                }
                if (showRules) {
                    state.rules.take(30).forEach { rule ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "${rule.severity.ifBlank { "规则" }} · ${rule.id}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (rule.description.isNotBlank()) {
                                Text(
                                    text = rule.description,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (state.rules.size > 30) {
                        Text(
                            text = "仅显示前 30 条，完整规则请在 Web 控制台查看。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            SectionHeader("文件防护")
            Card {
                SwitchRow(
                    title = "保护敏感文件",
                    subtitle = "限制工作区外敏感文件的读写与预览",
                    checked = state.fileGuard?.enabled == true,
                    enabled = !state.saving,
                    onCheckedChange = { enabled ->
                        if (enabled) onFileGuard(true) else pendingDisable = GuardKind.FILE
                    },
                )
                Divider()
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = "受保护路径",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val paths = state.fileGuard?.paths.orEmpty()
                    if (paths.isEmpty()) {
                        Text(
                            text = "未配置额外路径",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        paths.take(20).forEach { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (paths.size > 20) {
                            Text(
                                text = "另有 ${paths.size - 20} 条，请在 Web 控制台查看。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionHeader("沙箱")
            Card {
                SwitchRow(
                    title = "启用沙箱",
                    subtitle = "限制命令可访问的目录与系统能力",
                    checked = state.sandbox?.enabled == true,
                    enabled = !state.saving,
                    onCheckedChange = { enabled ->
                        if (enabled) onSandbox(true) else pendingDisable = GuardKind.SANDBOX
                    },
                )
                Divider()
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val sandbox = state.sandbox
                    val effectiveText = when {
                        sandbox == null -> "尚未读取"
                        sandbox.effective -> "当前已生效"
                        else -> "当前未生效"
                    }
                    Text(
                        text = effectiveText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sandbox?.effective == true) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    sandbox?.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionHeader("技能扫描器")
            Card {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = "导入技能时检查其中的脚本与配置。阻止模式会拒绝不安全技能，警告模式只记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SKILL_SCANNER_BLOCK to "阻止",
                            SKILL_SCANNER_WARN to "警告",
                            SKILL_SCANNER_OFF to "关闭",
                        ).forEach { (code, label) ->
                            Chip(
                                text = label,
                                selected = state.skillScannerMode == code,
                                onClick = { onSkillScanner(code) },
                            )
                        }
                    }
                }
            }

            if (state.notice != null || state.error != null) {
                TextButton(
                    onClick = onDismissNotice,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("清除提示")
                }
            }
        }
    }

    pendingDisable?.let { kind ->
        DestructiveActionDialog(
            title = "关闭防护",
            message = confirmText,
            impacts = listOf(
                "该修改立即生效，无需重启服务端。",
                "可在本页重新开启。",
            ),
            confirmLabel = "关闭",
            onConfirm = {
                when (kind) {
                    GuardKind.TOOL -> onToolGuard(false)
                    GuardKind.FILE -> onFileGuard(false)
                    GuardKind.SANDBOX -> onSandbox(false)
                }
                pendingDisable = null
            },
            onDismiss = { pendingDisable = null },
        )
    }
}
