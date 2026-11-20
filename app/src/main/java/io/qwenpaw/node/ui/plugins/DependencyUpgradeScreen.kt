package io.qwenpaw.node.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.WebEntryDialog

/**
 * 依赖升级：只读状态 + 应用/取消。
 *
 * 这一页没有列表入口，操作 id 只能由 409 带来。`waiting_restart` 是这一页的重点：
 * 升级需要服务端重启才生效，界面必须把命令摆出来并要求用户重新检查，
 * 不能因为「操作过程结束」就显示成功。
 */
@Composable
fun DependencyUpgradeScreen(
    state: DependencyUpgradeUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onEnsureStarted: () -> Unit = {},
    onStopPolling: () -> Unit = {},
    onOpenConsole: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { onEnsureStarted() }
    DisposableEffect(Unit) {
        onDispose { onStopPolling() }
    }
    WebEntryDialog(pageKey = "dependency_upgrade", onOpenConsole = onOpenConsole)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.plugin_admin_dep_title),
                onBack = onBack,
                action = stringResource(R.string.plugin_admin_dep_action_recheck),
                busy = state.loading || state.acting,
                onAction = onRefresh,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.operationId.isBlank()) {
                Hint(stringResource(R.string.plugin_admin_dep_empty_operation))
                return@Column
            }

            if (state.unsupported) {
                Text(
                    text = stringResource(R.string.plugin_admin_error_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.loadError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onRefresh),
                )
            }
            state.actionError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val operation = state.operation
            if (operation == null) {
                if (state.loading || !state.loaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.plugin_admin_dep_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Column
            }

            CardBox {
                Column(modifier = Modifier.padding(14.dp)) {
                    MetaLine(
                        stringResource(R.string.plugin_admin_dep_label_operation),
                        operation.operationId,
                    )
                    MetaLine(
                        stringResource(R.string.plugin_admin_dep_label_stage),
                        stageText(operation),
                    )
                    if (operation.message.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.plugin_admin_dep_label_server_message,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = operation.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (operation.error.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = operation.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            StageSection(
                operation = operation,
                acting = state.acting,
                onApply = onApply,
                onRefresh = onRefresh,
            )

            if (operation.canCancel && !operation.terminal) {
                ActionButton(
                    text = stringResource(R.string.plugin_admin_dep_action_cancel),
                    primary = false,
                    enabled = !state.acting,
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun StageSection(
    operation: DependencyUpgradeState,
    acting: Boolean,
    onApply: () -> Unit,
    onRefresh: () -> Unit,
) {
    when (operation.stage) {
        DependencyStage.NEEDS_CONFIRMATION -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_confirm),
        ) {
            ActionButton(
                text = if (acting) {
                    stringResource(R.string.plugin_admin_dep_action_applying_now)
                } else {
                    stringResource(R.string.plugin_admin_dep_action_apply)
                },
                primary = true,
                enabled = !acting,
                onClick = onApply,
            )
        }

        DependencyStage.PREPARING, DependencyStage.APPLYING -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_applying),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stageText(operation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DependencyStage.WAITING_RESTART -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_waiting_restart),
        ) {
            if (operation.maintenanceCommands.isEmpty()) {
                Text(
                    text = stringResource(R.string.plugin_admin_dep_no_commands),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.plugin_admin_dep_label_commands),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                operation.maintenanceCommands.forEach { command ->
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ActionButton(
                text = stringResource(R.string.plugin_admin_dep_action_recheck),
                primary = true,
                enabled = !acting,
                onClick = onRefresh,
            )
        }

        DependencyStage.REPAIR_REQUIRED -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_repair),
        ) {
            ActionButton(
                text = stringResource(R.string.plugin_admin_dep_action_recheck),
                primary = false,
                enabled = !acting,
                onClick = onRefresh,
            )
        }

        DependencyStage.SUCCEEDED -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_succeeded),
            error = false,
        ) {}

        DependencyStage.FAILED -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_failed),
        ) {}

        DependencyStage.CANCELLED -> StageCard(
            hint = stringResource(R.string.plugin_admin_dep_hint_cancelled),
        ) {}

        DependencyStage.UNKNOWN -> StageCard(
            hint = stringResource(
                R.string.plugin_admin_dep_stage_unknown,
                operation.statusText,
            ),
        ) {
            ActionButton(
                text = stringResource(R.string.plugin_admin_dep_action_recheck),
                primary = false,
                enabled = !acting,
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun StageCard(
    hint: String,
    error: Boolean = false,
    content: @Composable () -> Unit,
) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun stageText(operation: DependencyUpgradeState): String = when (operation.stage) {
    DependencyStage.PREPARING -> stringResource(R.string.plugin_admin_dep_stage_preparing)
    DependencyStage.NEEDS_CONFIRMATION ->
        stringResource(R.string.plugin_admin_dep_stage_needs_confirmation)
    DependencyStage.APPLYING -> stringResource(R.string.plugin_admin_dep_stage_applying)
    DependencyStage.WAITING_RESTART ->
        stringResource(R.string.plugin_admin_dep_stage_waiting_restart)
    DependencyStage.REPAIR_REQUIRED ->
        stringResource(R.string.plugin_admin_dep_stage_repair_required)
    DependencyStage.SUCCEEDED -> stringResource(R.string.plugin_admin_dep_stage_succeeded)
    DependencyStage.FAILED -> stringResource(R.string.plugin_admin_dep_stage_failed)
    DependencyStage.CANCELLED -> stringResource(R.string.plugin_admin_dep_stage_cancelled)
    DependencyStage.UNKNOWN -> stringResource(
        R.string.plugin_admin_dep_stage_unknown,
        operation.statusText,
    )
}

@Composable
private fun MetaLine(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surface
                    primary -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                primary -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
}
