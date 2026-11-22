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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PluginListItemDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.PlainTextEditor
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.common.pageContentInsets
import io.qwenpaw.node.ui.theme.qwenSwitchColors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 插件详情：状态、启停、卸载、依赖修复与配置写入。
 *
 * 配置走整段替换且服务端读不回原值，因此这一页不做表单化编辑，只在等宽编辑器里
 * 提交 JSON，并在保存前要求危险确认。其余动作都只点一次服务端，本地状态以返回后
 * 重新拉到的详情为准。
 */
@Composable
fun PluginManageScreen(
    state: PluginManageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onRepairDependencies: () -> Unit,
    onBeginConfigEdit: () -> Unit,
    onUpdateConfigDraft: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onCancelConfigEdit: () -> Unit,
    onConfirmInterrupt: () -> Unit,
    onDismissInterrupt: () -> Unit,
    onConfirmLegacy: () -> Unit,
    onDismissLegacy: () -> Unit,
    onDismissNotice: () -> Unit,
    onEnsureLoaded: () -> Unit = {},
    onOpenDependency: (String) -> Unit = {},
    onConsumeOpenDependency: () -> Unit = {},
    onOpenConsole: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmConfigSave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onEnsureLoaded() }
    // 依赖冲突只描述「刚才这一次动作」：先消费再跳转，回到本页时不会重复打开升级页。
    LaunchedEffect(state.openDependencyOperation) {
        val operationId = state.openDependencyOperation ?: return@LaunchedEffect
        onConsumeOpenDependency()
        onOpenDependency(operationId)
    }

    WebEntryDialog(pageKey = "plugin_manage", onOpenConsole = onOpenConsole)

    val config = state.config
    if (config != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConfigWarning(state.pluginId)
            state.actionError?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            if (config.errors.isNotEmpty()) {
                config.errors.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PlainTextEditor(
                    title = stringResource(R.string.plugin_admin_config_title),
                    backDescription = stringResource(R.string.action_back),
                    draft = config.draft,
                    dirty = config.dirty,
                    saving = config.saving,
                    saved = config.saved,
                    error = config.syntaxError,
                    onDraftChange = onUpdateConfigDraft,
                    onSave = { confirmConfigSave = true },
                    onExit = onCancelConfigEdit,
                    hint = stringResource(R.string.plugin_admin_hint_config, state.pluginId),
                )
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = pageContentInsets(),
            topBar = {
                AdminTopBar(
                    title = state.plugin?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.plugin_admin_title),
                    onBack = onBack,
                    action = stringResource(R.string.action_refresh),
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
                when {
                    state.pluginId.isBlank() -> Hint(
                        text = stringResource(R.string.plugin_admin_empty_no_plugin),
                    )

                    state.deleted -> Hint(
                        text = stringResource(R.string.plugin_admin_empty_deleted),
                    )

                    state.loading && state.plugin == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.plugin_admin_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    state.loadError != null -> Text(
                        text = stringResource(R.string.plugin_admin_error_load_failed, state.loadError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(onClick = onRefresh),
                    )

                    state.plugin != null -> {
                        if (state.unsupported) {
                            Text(
                                text = stringResource(R.string.plugin_admin_error_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        state.actionError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        PluginHeader(
                            plugin = state.plugin,
                            acting = state.acting,
                            onSetEnabled = onSetEnabled,
                        )
                        PluginActions(
                            plugin = state.plugin,
                            acting = state.acting,
                            onUnload = onUnload,
                            onRepairDependencies = onRepairDependencies,
                            onDelete = { confirmDelete = true },
                            onBeginConfigEdit = onBeginConfigEdit,
                        )
                    }

                    else -> Hint(text = stringResource(R.string.plugin_admin_empty_no_plugin))
                }
            }
        }
    }

    if (confirmDelete && state.plugin != null) {
        val name = state.plugin.name.ifBlank { state.plugin.id }
        DestructiveActionDialog(
            title = stringResource(R.string.plugin_admin_delete_title, name),
            message = stringResource(R.string.plugin_admin_delete_message),
            impacts = listOf(
                stringResource(R.string.plugin_admin_delete_impact_files),
                stringResource(R.string.plugin_admin_delete_impact_config),
                stringResource(R.string.plugin_admin_delete_impact_tasks),
            ),
            undoable = false,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmConfigSave) {
        DestructiveActionDialog(
            title = stringResource(R.string.plugin_admin_config_save_title),
            message = stringResource(R.string.plugin_admin_config_save_message, state.pluginId),
            impacts = listOf(
                stringResource(R.string.plugin_admin_config_impact_scope, state.pluginId),
                stringResource(R.string.plugin_admin_config_impact_backup),
            ),
            undoable = false,
            confirmLabel = stringResource(R.string.plugin_admin_config_save_confirm),
            onConfirm = {
                confirmConfigSave = false
                onSaveConfig()
            },
            onDismiss = { confirmConfigSave = false },
        )
    }

    state.interruptPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissInterrupt,
            title = { Text(stringResource(R.string.plugin_admin_interrupt_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.plugin_admin_interrupt_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (prompt.message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = prompt.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmInterrupt) {
                    Text(stringResource(R.string.plugin_admin_action_interrupt_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissInterrupt) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.legacyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissLegacy,
            title = { Text(stringResource(R.string.plugin_admin_legacy_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.plugin_admin_legacy_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (prompt.hooks.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.plugin_admin_empty_diagnostics),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    prompt.hooks.forEach { hook ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hook,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmLegacy) {
                    Text(stringResource(R.string.plugin_admin_action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissLegacy) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.notice?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.plugin_admin_notice_title)) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.plugin_admin_action_got_it))
                }
            },
        )
    }
}

/** 配置编辑前的黄条。整段替换是这一页唯一不可挽回的写入，说明必须常驻可见。 */
@Composable
private fun ConfigWarning(pluginId: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.plugin_admin_warning_config, pluginId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun PluginHeader(
    plugin: PluginListItemDto,
    acting: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name.ifBlank { plugin.id },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaLine(stringResource(R.string.plugin_admin_label_id), plugin.id)
                }
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onSetEnabled,
                    enabled = !acting,
                    colors = qwenSwitchColors(),
                )
            }
            if (plugin.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (plugin.version.isNotBlank()) {
                MetaLine(stringResource(R.string.plugin_admin_label_version), plugin.version)
            }
            if (plugin.author.isNotBlank()) {
                MetaLine(stringResource(R.string.plugin_admin_label_author), plugin.author)
            }
            MetaLine(
                stringResource(R.string.plugin_admin_label_enabled),
                boolText(plugin.enabled),
            )
            MetaLine(
                stringResource(R.string.plugin_admin_label_loaded),
                boolText(plugin.loaded),
            )
            // status 只在 2.3.0 起才有，空串表示旧服务端，不补一个假状态。
            if (plugin.status.isNotBlank()) {
                MetaLine(stringResource(R.string.plugin_admin_label_status), plugin.status)
            }
        }
    }
    DiagnosticsCard(plugin.diagnostics)
}

@Composable
private fun DiagnosticsCard(diagnostics: List<JsonElement>) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.plugin_admin_label_diagnostics),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            if (diagnostics.isEmpty()) {
                Text(
                    text = stringResource(R.string.plugin_admin_empty_diagnostics),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                diagnostics.forEach { element ->
                    Text(
                        text = diagnosticText(element),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginActions(
    plugin: PluginListItemDto,
    acting: Boolean,
    onUnload: () -> Unit,
    onRepairDependencies: () -> Unit,
    onDelete: () -> Unit,
    onBeginConfigEdit: () -> Unit,
) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.plugin_admin_section_actions),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = stringResource(R.string.plugin_admin_action_edit_config),
                hint = stringResource(R.string.plugin_admin_hint_config, plugin.id),
                enabled = !acting,
                onClick = onBeginConfigEdit,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(
                label = stringResource(R.string.plugin_admin_action_repair),
                hint = stringResource(R.string.plugin_admin_hint_repair),
                enabled = !acting,
                onClick = onRepairDependencies,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(
                label = stringResource(R.string.plugin_admin_action_unload),
                hint = stringResource(R.string.plugin_admin_hint_unload),
                enabled = !acting,
                onClick = onUnload,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(
                label = stringResource(R.string.plugin_admin_action_delete),
                hint = null,
                enabled = !acting,
                danger = true,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    hint: String?,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

@Composable
private fun boolText(value: Boolean): String =
    stringResource(if (value) R.string.plugin_admin_value_on else R.string.plugin_admin_value_off)

/** 诊断元素的形状随插件而变，只做文本化展示，不按字段解析。 */
private fun diagnosticText(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.content
    else -> element.toString()
}
