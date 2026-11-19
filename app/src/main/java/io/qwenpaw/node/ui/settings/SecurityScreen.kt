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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
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
        GuardKind.TOOL -> stringResource(R.string.security_confirm_tool_guard)
        GuardKind.FILE -> stringResource(R.string.security_confirm_file_guard)
        GuardKind.SANDBOX -> stringResource(R.string.security_confirm_sandbox)
        null -> ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AdminTopBar(title = stringResource(R.string.security_title), onBack = onBack) },
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

            SectionHeader(stringResource(R.string.security_section_tool_guard))
            Card {
                SwitchRow(
                    title = stringResource(R.string.security_tool_guard_title),
                    subtitle = stringResource(R.string.security_tool_guard_hint),
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
                        text = pluralStringResource(
                            R.plurals.security_rules_count,
                            state.rules.size,
                            state.rules.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showRules = !showRules }) {
                        Text(
                            stringResource(
                                if (showRules) {
                                    R.string.security_action_collapse
                                } else {
                                    R.string.security_action_view
                                },
                            ),
                        )
                    }
                }
                if (showRules) {
                    state.rules.take(30).forEach { rule ->
                        val severity = if (rule.severity.isBlank()) {
                            stringResource(R.string.security_rule_severity_unknown)
                        } else {
                            rule.severity
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.security_pair, severity, rule.id),
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
                            text = stringResource(R.string.security_rules_limited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.security_section_file_guard))
            Card {
                SwitchRow(
                    title = stringResource(R.string.security_file_guard_title),
                    subtitle = stringResource(R.string.security_file_guard_hint),
                    checked = state.fileGuard?.enabled == true,
                    enabled = !state.saving,
                    onCheckedChange = { enabled ->
                        if (enabled) onFileGuard(true) else pendingDisable = GuardKind.FILE
                    },
                )
                Divider()
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = stringResource(R.string.security_protected_paths),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val paths = state.fileGuard?.paths.orEmpty()
                    if (paths.isEmpty()) {
                        Text(
                            text = stringResource(R.string.security_no_extra_paths),
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
                                text = pluralStringResource(
                                    R.plurals.security_more_paths,
                                    paths.size - 20,
                                    paths.size - 20,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.security_section_sandbox))
            Card {
                SwitchRow(
                    title = stringResource(R.string.security_sandbox_title),
                    subtitle = stringResource(R.string.security_sandbox_hint),
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
                        sandbox == null -> stringResource(R.string.security_sandbox_unread)
                        sandbox.effective -> stringResource(R.string.security_sandbox_effective)
                        else -> stringResource(R.string.security_sandbox_ineffective)
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

            SectionHeader(stringResource(R.string.security_section_skill_scanner))
            Card {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.security_skill_scanner_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SKILL_SCANNER_BLOCK to stringResource(R.string.security_scanner_block),
                            SKILL_SCANNER_WARN to stringResource(R.string.security_scanner_warn),
                            SKILL_SCANNER_OFF to stringResource(R.string.security_scanner_off),
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
                    Text(stringResource(R.string.security_action_clear_notice))
                }
            }
        }
    }

    pendingDisable?.let { kind ->
        DestructiveActionDialog(
            title = stringResource(R.string.security_disable_title),
            message = confirmText,
            impacts = listOf(
                stringResource(R.string.security_disable_impact_immediate),
                stringResource(R.string.security_disable_impact_reopen),
            ),
            confirmLabel = stringResource(R.string.action_close),
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
