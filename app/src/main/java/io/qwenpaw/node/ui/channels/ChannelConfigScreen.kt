package io.qwenpaw.node.ui.channels

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.channel.ChannelField
import io.qwenpaw.node.data.channel.ChannelFieldError
import io.qwenpaw.node.data.channel.ChannelFieldType
import io.qwenpaw.node.data.channel.channelAvailabilityRes
import io.qwenpaw.node.data.channel.channelOptionLabelRes
import io.qwenpaw.node.data.channel.isChannelFieldHidden
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

@Composable
fun ChannelConfigScreen(
    state: ChannelConfigUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onSetText: (String, String) -> Unit,
    onSetSwitch: (String, Boolean) -> Unit,
    onBeginSecret: (String) -> Unit,
    onSetSecret: (String, String) -> Unit,
    onClearSecret: (String) -> Unit,
    onCancelSecret: (String) -> Unit,
    onRefreshHealth: () -> Unit,
    onRequestRestart: () -> Unit,
    onConfirmRestart: () -> Unit,
    onDismissRestart: () -> Unit,
    onConfirmConflict: () -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenQr: () -> Unit = {},
    onRetryQr: () -> Unit = {},
    onCloseQr: () -> Unit = {},
    onRepair: () -> Unit = {},
    onOpenConsole: (() -> Unit)? = null,
) {
    var selectingField by remember { mutableStateOf<ChannelField?>(null) }

    WebEntryDialog(pageKey = "channel_config", onOpenConsole = onOpenConsole)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(
                    icon = QwenIcons.arrowLeft,
                    description = stringResource(R.string.action_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.channel_config_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.label.isNotBlank()) {
                        Text(
                            text = state.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = onSave,
                    enabled = state.fields.isNotEmpty() &&
                        !state.saving &&
                        !state.busy &&
                        !state.versionUnsupported &&
                        !state.missingPlugin,
                ) {
                    Text(
                        if (state.saving) {
                            stringResource(R.string.channel_config_busy)
                        } else {
                            stringResource(R.string.channel_config_save)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                // 已知版本过低：整页提示，表单、安装与保存都隐藏。
                state.versionUnsupported -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.channels_version_unsupported_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.channels_version_unsupported_message,
                            state.serverVersionRaw.orEmpty(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                // 插件没装或运行时缺失：配置 GET 必然失败，改给安装面板。
                state.missingPlugin -> MissingPluginPanel(
                    state = state,
                    onRetry = onRetry,
                    onRepair = onRepair,
                    onDismissNotice = onDismissNotice,
                    onOpenConsole = onOpenConsole,
                )

                state.loaded && state.fields.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error
                            ?: stringResource(R.string.channel_config_fields_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.availability == "missing_code" || state.availability == "failed") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.channel_config_install_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Web 提示放在滚动区里：它只是说明，不该长期占住顶部可视空间。
                    ConsoleHint(onOpenConsole = onOpenConsole)
                    if (state.versionUnknown) {
                        Text(
                            text = stringResource(R.string.channels_version_unknown),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.error?.let { message ->
                        NoticeRow(
                            text = message,
                            error = true,
                            onDismiss = onDismissNotice,
                        )
                    }
                    state.notice?.let { message ->
                        NoticeRow(
                            text = message,
                            error = false,
                            onDismiss = onDismissNotice,
                        )
                    }
                    HealthCard(
                        state = state,
                        onRefresh = onRefreshHealth,
                        onRestart = onRequestRestart,
                    )
                    state.availability?.let { availability ->
                        Text(
                            text = stringResource(
                                R.string.channel_config_availability,
                                channelAvailabilityRes(availability)
                                    ?.let { stringResource(it) } ?: availability,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.diagnostic?.let { diagnostic ->
                        Text(
                            text = stringResource(R.string.channel_config_diagnostic, diagnostic),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.repairAction != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = repairStatusText(state.repairAction),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onRepair,
                                enabled = !state.repairing,
                            ) { Text(repairActionLabel(state.repairAction)) }
                        }
                    }
                    if (state.qrAvailable) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.channel_qr_title),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onOpenQr) {
                                Text(stringResource(R.string.channel_qr_title))
                            }
                        }
                    }
                    state.fields.forEach { field ->
                        // console 的启用状态由保存逻辑强制为 true，不给用户改。
                        if (!isChannelFieldHidden(state.channelKey, field.name)) {
                            FieldRow(
                                field = field,
                                state = state,
                                onSetText = onSetText,
                                onSetSwitch = onSetSwitch,
                                onBeginSecret = onBeginSecret,
                                onSetSecret = onSetSecret,
                                onClearSecret = onClearSecret,
                                onCancelSecret = onCancelSecret,
                                onPickSelect = { selectingField = field },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = onRequestRestart,
                            enabled = !state.busy,
                        ) { Text(stringResource(R.string.channel_config_restart)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onSave,
                            enabled = !state.saving && !state.busy,
                        ) { Text(stringResource(R.string.channel_config_save)) }
                    }
                }
            }
        }
    }

    state.auth?.let { auth ->
        ChannelQrPanel(
            auth = auth,
            name = state.label,
            onRetry = onRetryQr,
            onClose = onCloseQr,
        )
    }

    selectingField?.let { field ->
        AlertDialog(
            onDismissRequest = { selectingField = null },
            title = { Text(fieldLabel(field)) },
            text = {
                Column {
                    field.options.forEach { option ->
                        Text(
                            text = channelOptionLabelRes(field.name, option)
                                ?.let { stringResource(it) } ?: option,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onSetText(field.name, option)
                                    selectingField = null
                                }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectingField = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = onDismissConflict,
            title = { Text(stringResource(R.string.channel_config_conflict_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.channel_config_conflict_message))
                    Spacer(Modifier.height(8.dp))
                    conflict.agents.forEach { agent ->
                        Text(
                            text = "- " + (agent.agentName.ifBlank { agent.agentId }),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmConflict) {
                    Text(stringResource(R.string.channel_config_conflict_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConflict) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.confirmingRestart) {
        AlertDialog(
            onDismissRequest = onDismissRestart,
            title = { Text(stringResource(R.string.channel_config_restart_confirm_title)) },
            text = { Text(stringResource(R.string.channel_config_restart_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmRestart) {
                    Text(stringResource(R.string.channel_config_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestart) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun repairStatusText(action: String): String = when (action) {
    "install" -> stringResource(R.string.channels_status_missing_code)
    "enable" -> stringResource(R.string.channels_status_disabled_plugin)
    else -> stringResource(R.string.channels_status_validation_pending)
}

/**
 * 缺插件的安装面板。
 *
 * 这一状态下配置接口必然失败，显示通用错误只会让人以为配置坏了；
 * 面板只回答两件事：缺什么、点哪里安装。
 */
@Composable
private fun MissingPluginPanel(
    state: ChannelConfigUiState,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenConsole: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConsoleHint(onOpenConsole = onOpenConsole)
        state.error?.let { message ->
            NoticeRow(text = message, error = true, onDismiss = onDismissNotice)
        }
        state.notice?.let { message ->
            NoticeRow(text = message, error = false, onDismiss = onDismissNotice)
        }
        Text(
            text = stringResource(R.string.channel_config_missing_plugin_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                if (state.availability == "missing_runtime") {
                    R.string.channel_config_missing_runtime_message
                } else {
                    R.string.channel_config_missing_plugin_message
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.diagnostic?.let { diagnostic ->
            Text(
                text = stringResource(R.string.channel_config_diagnostic, diagnostic),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.repairAction != null) {
                TextButton(onClick = onRepair, enabled = !state.repairing) {
                    Text(
                        if (state.repairing) {
                            stringResource(R.string.channel_config_busy)
                        } else {
                            stringResource(R.string.channels_install_plugin)
                        },
                    )
                }
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun repairActionLabel(action: String): String = when (action) {
    "install" -> stringResource(R.string.channels_install)
    "enable" -> stringResource(R.string.channels_action_enable)
    else -> stringResource(R.string.channels_action_repair)
}

@Composable
private fun fieldLabel(field: ChannelField): String =
    field.labelRes?.let { stringResource(it) } ?: field.label

@Composable
private fun FieldRow(
    field: ChannelField,
    state: ChannelConfigUiState,
    onSetText: (String, String) -> Unit,
    onSetSwitch: (String, Boolean) -> Unit,
    onBeginSecret: (String) -> Unit,
    onSetSecret: (String, String) -> Unit,
    onClearSecret: (String) -> Unit,
    onCancelSecret: (String) -> Unit,
    onPickSelect: () -> Unit,
) {
    when (field.type) {
        ChannelFieldType.SWITCH -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(fieldLabel(field), style = MaterialTheme.typography.bodyMedium)
                field.help?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = state.values.switches[field.name] ?: false,
                onCheckedChange = { onSetSwitch(field.name, it) },
            )
        }

        ChannelFieldType.PASSWORD -> {
            SecretField(
                field = field,
                state = state,
                onBegin = { onBeginSecret(field.name) },
                onSet = { onSetSecret(field.name, it) },
                onClear = { onClearSecret(field.name) },
                onCancel = { onCancelSecret(field.name) },
            )
        }

        ChannelFieldType.SELECT -> Column {
            OutlinedTextField(
                value = state.values.text[field.name].orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(fieldLabel(field)) },
                placeholder = field.placeholder?.let { { Text(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickSelect),
            )
            field.help?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ChannelFieldType.JSON -> Column {
            OutlinedTextField(
                value = state.values.text[field.name].orEmpty(),
                onValueChange = { onSetText(field.name, it) },
                label = { Text(fieldLabel(field)) },
                placeholder = {
                    Text(if (field.jsonType == "array") "[]" else "{}")
                },
                minLines = 3,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                isError = state.fieldErrors[field.name] == ChannelFieldError.JSON ||
                    state.fieldErrors[field.name] == ChannelFieldError.JSON_TYPE,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldError(field, state)
            Text(
                text = stringResource(
                    when (field.jsonType) {
                        "array" -> R.string.channel_config_json_array_hint
                        "object" -> R.string.channel_config_json_object_hint
                        else -> R.string.channel_config_json_any_hint
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            field.help?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ChannelFieldType.NUMBER, ChannelFieldType.TEXT -> Column {
            OutlinedTextField(
                value = state.values.text[field.name].orEmpty(),
                onValueChange = { onSetText(field.name, it) },
                label = { Text(fieldLabel(field)) },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = if (field.type == ChannelFieldType.NUMBER) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                isError = state.fieldErrors[field.name] == ChannelFieldError.NUMBER,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldError(field, state)
            field.help?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SecretField(
    field: ChannelField,
    state: ChannelConfigUiState,
    onBegin: () -> Unit,
    onSet: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.values.secrets[field.name]
    val hasOriginal = draft?.hasOriginal == true
    Column {
        Text(fieldLabel(field), style = MaterialTheme.typography.bodyMedium)
        when {
            draft?.clearing == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.channel_config_secret_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.channel_config_secret_cancel))
                }
            }

            draft?.replacing == true || !hasOriginal -> {
                OutlinedTextField(
                    value = draft?.replacement.orEmpty(),
                    onValueChange = onSet,
                    label = { Text(stringResource(R.string.channel_config_secret_new)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hasOriginal) {
                            stringResource(R.string.channel_config_secret_keep_hint)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasOriginal) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.channel_config_secret_cancel))
                        }
                    }
                }
            }

            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.channel_config_secret_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBegin) {
                    Text(stringResource(R.string.channel_config_secret_replace))
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.channel_config_secret_clear))
                }
            }
        }
        field.help?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FieldError(field: ChannelField, state: ChannelConfigUiState) {
    when (state.fieldErrors[field.name]) {
        ChannelFieldError.NUMBER -> Text(
            text = stringResource(R.string.channel_config_error_number),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        ChannelFieldError.JSON -> Text(
            text = stringResource(R.string.channel_config_error_json),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        ChannelFieldError.JSON_TYPE -> Text(
            text = stringResource(
                if (field.jsonType == "object") {
                    R.string.channel_config_error_json_object
                } else {
                    R.string.channel_config_error_json_array
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        null -> Unit
    }
}

@Composable
private fun HealthCard(
    state: ChannelConfigUiState,
    onRefresh: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.channel_config_health_title),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.channel_config_health_refresh))
            }
        }
        val health = state.health
        if (health == null) {
            Text(
                text = stringResource(R.string.channel_config_health_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = when (health.status) {
                    "healthy" -> stringResource(R.string.channel_config_health_healthy)
                    "unhealthy" -> stringResource(R.string.channel_config_health_unhealthy)
                    "disabled" -> stringResource(R.string.channel_config_health_disabled)
                    else -> health.status
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (health.status == "unhealthy") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (health.detail.isNotBlank()) {
                Text(
                    text = stringResource(R.string.channel_config_health_detail, health.detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onRestart, enabled = !state.busy) {
                Text(stringResource(R.string.channel_config_restart))
            }
        }
    }
}

@Composable
private fun NoticeRow(text: String, error: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (error) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.action_close))
        }
    }
}
