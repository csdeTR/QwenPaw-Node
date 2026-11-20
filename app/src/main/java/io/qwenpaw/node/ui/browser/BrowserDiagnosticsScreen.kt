package io.qwenpaw.node.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BrowserCheckDto
import io.qwenpaw.node.data.model.BrowserChromeStatusDto
import io.qwenpaw.node.data.model.BrowserSelfTestDto
import io.qwenpaw.node.ui.TimeLabels
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.theme.LocalCaptionColor
import kotlin.math.roundToInt

/**
 * 浏览器控制链路诊断。
 *
 * 只读页面：状态与自检结果都来自服务端，手机端不推断结论。检查项失败属于
 * 自检结论的一部分，不当作请求失败；只有端到端失败才显示为错误。
 * 日志与修复依赖 Bridge 插件 0.5.0，插件不可用时整块替换为安装入口。
 */
@Composable
fun BrowserDiagnosticsScreen(
    state: BrowserDiagnosticsUiState,
    bridgeSupported: Boolean,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onSelfTest: () -> Unit,
    onReadLogs: () -> Unit,
    onRepair: () -> Unit,
    onOpenMarket: () -> Unit,
    onConsumeMessage: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { onEnsureLoaded() }
    var showLogs by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf(false) }

    WebEntryDialog(pageKey = "browser-diagnostics", onOpenConsole = onOpenConsole)

    if (confirmRepair) {
        AlertDialog(
            onDismissRequest = { confirmRepair = false },
            title = { Text(stringResource(R.string.browser_repair_confirm_title)) },
            text = { Text(stringResource(R.string.browser_repair_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRepair = false
                        onRepair()
                    },
                ) { Text(stringResource(R.string.browser_repair_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRepair = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showLogs) {
        LogsDialog(
            lines = state.logs,
            loading = state.logsLoading,
            error = state.logsError,
            onClose = { showLogs = false },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.browser_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 常驻说明不随内容滚动，否则页面拉到底部后就看不出这是 Web 更适合的操作。
            ConsoleHint(onOpenConsole)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                state.notice?.let { message ->
                    NoticeBanner(text = message, onDismiss = onConsumeMessage)
                }

                val status = state.status
                when {
                    state.unsupported -> UnsupportedCard()

                    state.loading && status == null -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }

                    status == null -> LoadFailedCard(
                        message = state.error ?: stringResource(R.string.browser_loading),
                        onRetry = onEnsureLoaded,
                    )

                    else -> {
                        state.error?.let { message ->
                            NoticeBanner(text = message, onDismiss = onConsumeMessage)
                        }

                        SectionHeader(stringResource(R.string.browser_section_status))
                        StatusCard(status)

                        SectionHeader(stringResource(R.string.browser_section_self_test))
                        SelfTestCard(
                            selfTest = state.selfTest ?: status.lastSelfTest,
                            running = state.selfTestRunning,
                            onSelfTest = onSelfTest,
                        )

                        SectionHeader(stringResource(R.string.browser_section_limits))
                        BodyCard(stringResource(R.string.browser_limits_body))

                        SectionHeader(stringResource(R.string.browser_section_traces))
                        BodyCard(stringResource(R.string.browser_traces_body))

                        SectionHeader(stringResource(R.string.browser_section_bridge))
                        BridgeCard(
                            supported = bridgeSupported,
                            repairing = state.repairing,
                            onReadLogs = {
                                showLogs = true
                                onReadLogs()
                            },
                            onRepair = { confirmRepair = true },
                            onOpenMarket = onOpenMarket,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: BrowserChromeStatusDto) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoRow(
                label = stringResource(R.string.browser_label_readiness),
                value = readinessText(status.readinessState),
                valueColor = readinessColor(status.readinessState),
            )
            InfoRow(
                label = stringResource(R.string.browser_label_connected),
                value = yesNo(status.connected),
                valueColor = if (status.connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            InfoRow(
                label = stringResource(R.string.browser_label_extension_version),
                value = status.extensionVersion?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.browser_value_absent),
            )
            InfoRow(
                label = stringResource(R.string.browser_label_connected_since),
                value = TimeLabels.fullStamp(status.connectedSince)
                    ?: status.connectedSince?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.browser_value_absent),
            )
            status.bridgeLifecycle?.let { lifecycle ->
                InfoRow(
                    label = stringResource(R.string.browser_label_bridge),
                    value = yesNo(lifecycle.connected),
                    valueColor = if (lifecycle.connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            status.contractHealth?.let { health ->
                ContractHealthBlock(health)
            }
            status.lastSelfTest?.let { test ->
                InfoRow(
                    label = stringResource(R.string.browser_label_last_self_test),
                    value = TimeLabels.fullStamp(test.checkedAt)
                        ?: test.checkedAt?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.browser_self_test_never),
                )
                Text(
                    text = stringResource(
                        R.string.browser_self_test_summary,
                        statusLabel(test.status),
                        test.durationMs.roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
            }
        }
    }
}

@Composable
private fun ContractHealthBlock(check: BrowserCheckDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.browser_label_contract),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusLabel(check.status, check.passed),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(check.status, check.passed),
                fontWeight = FontWeight.Medium,
            )
        }
        if (check.name.isNotBlank()) {
            Text(
                text = check.name,
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
            )
        }
        if (check.message.isNotBlank()) {
            Text(text = check.message, style = MaterialTheme.typography.bodySmall)
        }
        DriftLines(check)
    }
}

@Composable
private fun SelfTestCard(
    selfTest: BrowserSelfTestDto?,
    running: Boolean,
    onSelfTest: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selfTest == null) {
                Text(
                    text = stringResource(R.string.browser_self_test_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.browser_self_test_empty_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.browser_self_test_summary,
                        statusLabel(selfTest.status),
                        selfTest.durationMs.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.browser_self_test_checked_at,
                        TimeLabels.fullStamp(selfTest.checkedAt)
                            ?: selfTest.checkedAt?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.browser_self_test_never),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
                if (selfTest.checks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.browser_self_test_no_checks),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalCaptionColor.current,
                    )
                } else {
                    selfTest.checks.forEach { check -> CheckRow(check) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onSelfTest, enabled = !running) {
                    Text(
                        text = stringResource(
                            if (running) R.string.browser_retesting else R.string.browser_retest,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: BrowserCheckDto) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = check.name.ifBlank { stringResource(R.string.browser_check_unnamed) },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusLabel(check.status, check.passed),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(check.status, check.passed),
                fontWeight = FontWeight.Medium,
            )
        }
        if (check.message.isNotBlank()) {
            Text(
                text = check.message,
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
            )
        }
        check.repairAction?.takeIf { it.isNotBlank() }?.let { advice ->
            Text(
                text = stringResource(R.string.browser_check_repair, advice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        DriftLines(check)
    }
}

/** `contract_drift` 的附加信息；其它检查项没有这两个字段。 */
@Composable
private fun DriftLines(check: BrowserCheckDto) {
    check.severity?.takeIf { it.isNotBlank() }?.let { severity ->
        Text(
            text = stringResource(R.string.browser_check_drift, severity),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    check.detail?.takeIf { it.isNotBlank() }?.let { detail ->
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = LocalCaptionColor.current,
        )
    }
}

@Composable
private fun BridgeCard(
    supported: Boolean,
    repairing: Boolean,
    onReadLogs: () -> Unit,
    onRepair: () -> Unit,
    onOpenMarket: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (supported) {
                Text(
                    text = stringResource(R.string.browser_bridge_supported_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onReadLogs,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.browser_bridge_logs)) }
                    OutlinedButton(
                        onClick = onRepair,
                        modifier = Modifier.weight(1f),
                        enabled = !repairing,
                    ) {
                        Text(
                            text = stringResource(
                                if (repairing) R.string.browser_repairing else R.string.browser_bridge_repair,
                            ),
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.browser_bridge_need_plugin),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenMarket) {
                    Text(stringResource(R.string.browser_bridge_install))
                }
            }
        }
    }
}

@Composable
private fun BodyCard(text: String) {
    Card {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun UnsupportedCard() {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_unsupported),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.browser_unsupported_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
            )
        }
    }
}

@Composable
private fun LoadFailedCard(message: String, onRetry: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 日志只读展示：可以选中复制，不提供编辑与搜索。 */
@Composable
private fun LogsDialog(
    lines: List<String>?,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.browser_logs_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.browser_logs_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.browser_logs_loading),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    lines.isNullOrEmpty() -> Text(
                        text = stringResource(R.string.browser_logs_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            Text(
                                text = lines.orEmpty().joinToString("\n"),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun readinessText(state: String): String = when (state) {
    "ready" -> stringResource(R.string.browser_readiness_ready)
    "blocked" -> stringResource(R.string.browser_readiness_blocked)
    else -> state.takeIf { it.isNotBlank() } ?: stringResource(R.string.browser_value_absent)
}

@Composable
private fun readinessColor(state: String): Color = when (state) {
    "ready" -> MaterialTheme.colorScheme.primary
    "blocked" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.browser_connected_yes else R.string.browser_connected_no,
)

/** 检查项状态：优先看 `status` 原文，缺失时退回 `passed`。 */
@Composable
private fun statusLabel(status: String, passed: Boolean = false): String = when (status) {
    "passed" -> stringResource(R.string.browser_status_passed)
    "pending" -> stringResource(R.string.browser_status_pending)
    "failed" -> stringResource(R.string.browser_status_failed)
    "" -> if (passed) {
        stringResource(R.string.browser_status_passed)
    } else {
        stringResource(R.string.browser_status_unknown)
    }

    else -> status
}

@Composable
private fun statusColor(status: String, passed: Boolean = false): Color = when (status) {
    "passed" -> MaterialTheme.colorScheme.primary
    "pending" -> MaterialTheme.colorScheme.onSurfaceVariant
    "failed" -> MaterialTheme.colorScheme.error
    "" -> if (passed) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
