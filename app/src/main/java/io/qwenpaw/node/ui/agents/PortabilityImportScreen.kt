package io.qwenpaw.node.ui.agents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PortabilityAssetDto
import io.qwenpaw.node.data.model.PortabilityJobDto
import io.qwenpaw.node.data.model.PortabilityProviderDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.settings.SwitchRow

/**
 * 「从其他客户端导入」向导。
 *
 * 五步：选来源 → 扫描 → 选资产 → 导入 → 结果。扫描与导入都靠轮询，
 * 页面离开时停止；再次进入会通过 `jobs/current` 恢复未完成的任务。
 */
@Composable
fun PortabilityImportScreen(
    state: PortabilityUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onToggleSource: (String) -> Unit,
    onCreateJob: () -> Unit,
    onToggleAsset: (String) -> Unit,
    onSessions: (String, Boolean) -> Unit,
    onAllowPluginExecution: (Boolean) -> Unit,
    onStartImport: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
    onStopPolling: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    LaunchedEffect(Unit) { onEnsureLoaded() }
    DisposableEffect(Unit) { onDispose { onStopPolling() } }
    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AdminTopBar(title = stringResource(R.string.portability_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
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
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.portability_action_dismiss_notice))
                }
            }

            when (state.step) {
                ImportStep.SOURCES -> SourcesStep(
                    state = state,
                    onToggle = onToggleSource,
                    onStart = onCreateJob,
                )
                ImportStep.SCANNING -> ScanningStep(onCancel = onCancel)
                ImportStep.INVENTORY -> InventoryStep(
                    state = state,
                    onToggleAsset = onToggleAsset,
                    onSessions = onSessions,
                    onAllowPluginExecution = onAllowPluginExecution,
                    onStart = onStartImport,
                    onCancel = onCancel,
                )
                ImportStep.RUNNING -> RunningStep(state = state, onCancel = onCancel)
                ImportStep.RESULT -> ResultStep(
                    state = state,
                    onRetry = onRetryFailed,
                    onDone = onBack,
                )
            }
        }
    }
}

@Composable
private fun SourcesStep(
    state: PortabilityUiState,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
) {
    SectionHeader(stringResource(R.string.portability_section_sources))
    Card {
        Column(modifier = Modifier.padding(8.dp)) {
            if (state.sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.portability_empty_sources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            state.sources.forEach { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = source.detected) { onToggle(source.source) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = source.source in state.selectedSources,
                        enabled = source.detected,
                        onCheckedChange = { onToggle(source.source) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.name.ifBlank { source.source }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (source.detected) {
                                stringResource(R.string.portability_source_detected)
                            } else {
                                stringResource(R.string.portability_source_not_detected)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Row(modifier = Modifier.padding(16.dp)) {
        TextButton(
            enabled = state.selectedSources.isNotEmpty() && !state.loading,
            onClick = onStart,
        ) { Text(stringResource(R.string.portability_action_start_scan)) }
    }
}

@Composable
private fun ScanningStep(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.padding(start = 12.dp))
        Text(
            text = stringResource(R.string.portability_hint_scanning),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    TextButton(onClick = onCancel, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun InventoryStep(
    state: PortabilityUiState,
    onToggleAsset: (String) -> Unit,
    onSessions: (String, Boolean) -> Unit,
    onAllowPluginExecution: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val job = state.job
    if (job == null) {
        Text(
            text = stringResource(R.string.portability_empty_job),
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val hasPlugins = job.providers.any { provider ->
        provider.assets.any {
            selectionField(it.assetType) == "plugins" && assetKey(provider.source, it) in state.selectedAssets
        }
    }
    job.providers.forEach { provider ->
        ProviderInventory(
            provider = provider,
            selected = state.selectedAssets,
            sessions = state.sessions[provider.source] ?: true,
            onToggleAsset = onToggleAsset,
            onSessions = { onSessions(provider.source, it) },
        )
    }
    if (hasPlugins) {
        SectionHeader(stringResource(R.string.portability_section_plugin_execution))
        Card {
            SwitchRow(
                title = stringResource(R.string.portability_allow_plugin_title),
                subtitle = stringResource(R.string.portability_allow_plugin_subtitle),
                checked = state.allowPluginExecution,
                onCheckedChange = onAllowPluginExecution,
            )
        }
    }
    Row(modifier = Modifier.padding(16.dp)) {
        TextButton(enabled = !state.loading, onClick = onStart) {
            Text(stringResource(R.string.portability_action_start_import))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun ProviderInventory(
    provider: PortabilityProviderDto,
    selected: Set<String>,
    sessions: Boolean,
    onToggleAsset: (String) -> Unit,
    onSessions: (Boolean) -> Unit,
) {
    SectionHeader(provider.source)
    Card {
        Column(modifier = Modifier.padding(8.dp)) {
            if (provider.error.isNotBlank()) {
                Text(
                    text = provider.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
            }
            SwitchRow(
                title = stringResource(R.string.portability_sessions_title),
                subtitle = pluralStringResource(
                    R.plurals.portability_sessions_total,
                    provider.sessionsTotal,
                    provider.sessionsTotal,
                ),
                checked = sessions,
                onCheckedChange = onSessions,
            )
            provider.assets.groupBy { selectionField(it.assetType) ?: it.assetType }.forEach { (type, assets) ->
                Text(
                    text = assetTypeLabel(type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                )
                assets.forEach { asset ->
                    val key = assetKey(provider.source, asset)
                    val selectable = asset.blockedReason.isBlank() && asset.state != "existing"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selectable) { onToggleAsset(key) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = key in selected,
                            enabled = selectable,
                            onCheckedChange = { onToggleAsset(key) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(asset.name.ifBlank { asset.sourceId }, style = MaterialTheme.typography.bodyMedium)
                            val hint = when {
                                asset.blockedReason.isNotBlank() -> asset.blockedReason
                                asset.state == "existing" -> stringResource(R.string.portability_asset_exists)
                                else -> asset.message
                            }
                            if (hint.isNotBlank()) {
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningStep(state: PortabilityUiState, onCancel: () -> Unit) {
    val job = state.job
    if (job == null) {
        Text(
            text = stringResource(R.string.portability_empty_running),
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        Text(
            text = if (job.state == "cancelling") {
                stringResource(R.string.portability_running_cancelling)
            } else {
                stringResource(R.string.portability_running_importing)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    job.providers.forEach { provider ->
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(provider.source, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        R.string.portability_provider_progress,
                        provider.sessionsImported,
                        provider.sessionsTotal,
                        provider.state,
                    ) + if (provider.error.isNotBlank()) " · ${provider.error}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (job.logs.isNotEmpty()) {
        SectionHeader(stringResource(R.string.portability_section_recent_logs))
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                job.logs.takeLast(6).forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    TextButton(onClick = onCancel, modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.portability_action_cancel_import))
    }
}

@Composable
private fun ResultStep(
    state: PortabilityUiState,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val job = state.job ?: return
    SectionHeader(stringResource(R.string.portability_section_result))
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(resultNotice(job), style = MaterialTheme.typography.bodyLarge)
            job.providers.forEach { provider ->
                Text(
                    text = stringResource(
                        R.string.portability_result_provider,
                        provider.source,
                        provider.sessionsImported,
                        provider.sessionsTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val failures = job.providers.flatMap { provider ->
        provider.assets.filter { it.state == "failed" }.map { provider.source to it }
    }
    if (failures.isNotEmpty()) {
        SectionHeader(
            title = stringResource(R.string.portability_section_failures),
            trailing = pluralStringResource(
                R.plurals.portability_failures_count,
                failures.size,
                failures.size,
            ),
        )
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                failures.take(20).forEach { (source, asset) ->
                    val typeLabel = assetTypeLabel(asset.assetType)
                    Text(
                        text = stringResource(
                            R.string.portability_result_failure_item,
                            typeLabel,
                            asset.name.ifBlank { asset.sourceId },
                            source,
                            asset.message,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(16.dp)) {
            TextButton(enabled = !state.loading, onClick = onRetry) {
                Text(stringResource(R.string.portability_action_retry_failed))
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
        }
    } else {
        TextButton(onClick = onDone, modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@Composable
private fun assetTypeLabel(type: String): String = when (type) {
    "memory" -> stringResource(R.string.portability_asset_memory)
    "cron", "scheduled_task" -> stringResource(R.string.portability_asset_cron)
    "skill", "skills" -> stringResource(R.string.portability_asset_skill)
    "mcp" -> "MCP"
    "plugin", "plugins" -> stringResource(R.string.portability_asset_plugin)
    else -> type
}
