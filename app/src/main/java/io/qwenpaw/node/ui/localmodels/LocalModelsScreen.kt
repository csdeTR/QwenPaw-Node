package io.qwenpaw.node.ui.localmodels

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.localmodel.isActiveDownload
import io.qwenpaw.node.data.localmodel.progressFraction
import io.qwenpaw.node.data.localmodel.progressLabelRes
import io.qwenpaw.node.data.model.LocalModelDto
import io.qwenpaw.node.data.model.LocalModelProgressDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.ChoiceDialog
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.workspace.DownloadPolicy

/**
 * 本地模型：运行时状态与启停、配置、可下载清单、下载进度与模型删除。
 *
 * 下载发生在服务端，手机只触发与观察。运行时以配置中的端口对外提供接口，
 * 因此启用与停用都要说明后果：启动占用端口与内存，更新会先停服务。
 * 配置只提交改动过的字段，保存后回读服务端归一化之后的值。
 */
@Composable
fun LocalModelsScreen(
    state: LocalModelsUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onStopPolling: () -> Unit,
    onInstallRuntime: () -> Unit,
    onDownloadModel: (String) -> Unit,
    onCancel: () -> Unit,
    onConsumeMessage: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
    onDeleteModel: (String) -> Unit = {},
    onStartServer: (String) -> Unit = {},
    onStopServer: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onUpdateRuntime: () -> Unit = {},
    onSaveConfig: () -> Unit = {},
    onConfigDraftChange: (LocalModelConfigDraft) -> Unit = {},
    onReloadConfig: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onEnsureLoaded() }
    DisposableEffect(Unit) { onDispose { onStopPolling() } }
    LaunchedEffect(state.messageRes) {
        val res = state.messageRes ?: return@LaunchedEffect
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
        onConsumeMessage()
    }

    var pendingDelete by remember { mutableStateOf<LocalModelDto?>(null) }
    var pickingModel by remember { mutableStateOf(false) }
    var confirmUpdate by remember { mutableStateOf(false) }

    WebEntryDialog(pageKey = "local_models", onOpenConsole = onOpenConsole)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AdminTopBar(title = stringResource(R.string.local_models_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            ConsoleHint(onOpenConsole = onOpenConsole)

            if (state.loading && !state.loaded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            SectionHeader(stringResource(R.string.local_models_section_server))
            RuntimeCard(
                state = state,
                onInstallRuntime = onInstallRuntime,
                onRequestStart = { pickingModel = true },
                onStopServer = onStopServer,
                onCheckUpdate = onCheckUpdate,
                onRequestUpdate = { confirmUpdate = true },
            )

            SectionHeader(stringResource(R.string.local_models_admin_section_config))
            ConfigCard(
                state = state,
                onDraftChange = onConfigDraftChange,
                onSave = onSaveConfig,
                onReload = onReloadConfig,
            )

            SectionHeader(stringResource(R.string.local_models_section_models))
            ModelsCard(
                models = state.models,
                loaded = state.loaded,
                busy = state.actionBusy || state.activeSource != null,
                deletingModelId = state.deletingModelId,
                onDownloadModel = onDownloadModel,
                onRequestDelete = { pendingDelete = it },
            )
            state.deleteError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            val progress = currentProgress(state)
            if (progress != null) {
                SectionHeader(stringResource(R.string.local_models_section_progress))
                ProgressCard(
                    progress = progress,
                    isRuntime = state.activeSource == DownloadSource.SERVER ||
                        (state.activeSource == null && state.serverProgress === progress),
                    active = state.activeSource != null,
                    busy = state.actionBusy,
                    onCancel = onCancel,
                )
            }

            Text(
                text = stringResource(R.string.local_models_admin_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    val downloaded = state.downloadedModels
    if (pickingModel) {
        ChoiceDialog(
            title = stringResource(R.string.local_models_admin_pick_model_title),
            message = stringResource(R.string.local_models_admin_pick_model_message),
            choices = downloaded.map { it.name.ifBlank { it.id } },
            onPick = { index ->
                pickingModel = false
                downloaded.getOrNull(index)?.let { onStartServer(it.id.ifBlank { it.name }) }
            },
            onDismiss = { pickingModel = false },
        )
    }

    pendingDelete?.let { model ->
        DestructiveActionDialog(
            title = stringResource(R.string.local_models_admin_delete_title),
            message = stringResource(
                R.string.local_models_admin_delete_message,
                model.name.ifBlank { model.id },
            ),
            impacts = listOf(
                stringResource(R.string.local_models_admin_delete_impact_files),
                stringResource(R.string.local_models_admin_delete_impact_runtime),
            ),
            confirmLabel = stringResource(R.string.local_models_admin_action_delete),
            onConfirm = {
                pendingDelete = null
                onDeleteModel(model.id.ifBlank { model.name })
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (confirmUpdate) {
        // 更新只停一次服务、不删数据，因此用常规强调色而不是红色。
        DestructiveActionDialog(
            title = stringResource(R.string.local_models_admin_action_update),
            message = stringResource(R.string.local_models_admin_update_message),
            confirmLabel = stringResource(R.string.local_models_admin_action_update),
            confirmDanger = false,
            onConfirm = {
                confirmUpdate = false
                onUpdateRuntime()
            },
            onDismiss = { confirmUpdate = false },
        )
    }
}

/** 进行中的优先；都已结束时展示最近一次非空闲的结果。 */
private fun currentProgress(state: LocalModelsUiState): LocalModelProgressDto? = when (state.activeSource) {
    DownloadSource.SERVER -> state.serverProgress
    DownloadSource.MODEL -> state.modelProgress
    null -> state.serverProgress?.takeIf { it.showable() } ?: state.modelProgress?.takeIf { it.showable() }
}

private fun LocalModelProgressDto.showable(): Boolean =
    status.isNotBlank() && status != "idle"

/**
 * 运行时卡：状态、启动与停止、更新检查。
 *
 * 只有 `installed && !available` 才给「启动」；没有已下载模型时按钮置灰并说明先下载，
 * 否则点开选择框会是一份空列表。更新按钮只在检查结果表示有新版本时出现。
 */
@Composable
private fun RuntimeCard(
    state: LocalModelsUiState,
    onInstallRuntime: () -> Unit,
    onRequestStart: () -> Unit,
    onStopServer: () -> Unit,
    onCheckUpdate: () -> Unit,
    onRequestUpdate: () -> Unit,
) {
    val server = state.server
    val hasModel = state.downloadedModels.isNotEmpty()
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            when {
                server == null -> Text(
                    text = stringResource(R.string.local_models_status_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                server.installed -> {
                    val port = server.port
                    Text(
                        text = if (server.available && port != null) {
                            stringResource(R.string.local_models_running, port)
                        } else {
                            stringResource(R.string.local_models_installed_not_running)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    server.modelName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = stringResource(R.string.local_models_model_name, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    server.message?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.local_models_admin_hint_runtime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.runtimeBusy || state.checkingUpdate) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            if (state.checkingUpdate) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.local_models_admin_checking_update,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (server.available) {
                                ActionChip(
                                    text = stringResource(R.string.local_models_admin_action_stop),
                                    onClick = onStopServer,
                                )
                            } else {
                                ActionChip(
                                    text = stringResource(R.string.local_models_admin_action_start),
                                    enabled = hasModel,
                                    onClick = onRequestStart,
                                )
                            }
                            ActionChip(
                                text = stringResource(R.string.local_models_admin_action_check_update),
                                onClick = onCheckUpdate,
                            )
                            if (state.update?.hasUpdate == true) {
                                ActionChip(
                                    text = stringResource(R.string.local_models_admin_action_update),
                                    onClick = onRequestUpdate,
                                )
                            }
                        }
                    }
                    if (!server.available && !hasModel) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.local_models_admin_pick_model_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Text(
                        text = server.message ?: stringResource(R.string.local_models_not_installed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (server.installable) {
                        TextButton(
                            enabled = !state.actionBusy && state.activeSource == null,
                            onClick = onInstallRuntime,
                        ) { Text(stringResource(R.string.local_models_install_runtime)) }
                    } else {
                        Text(
                            text = stringResource(R.string.local_models_not_installable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 更新与启停的结果放在状态分支之外：运行时状态读不到时，动作失败的原因仍要能看到。
            state.update?.takeIf { server?.installed == true }?.let { update ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (update.hasUpdate) {
                            R.string.local_models_admin_update_available
                        } else {
                            R.string.local_models_admin_update_latest
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (update.hasUpdate) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            state.runtimeError?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.updateError?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 配置卡。
 *
 * 端口留空即自动端口；「自动端口」按钮只是把输入框清空，语义与服务端一致，
 * 不额外记一个布尔量。生成参数收在可折叠的高级项里：它原样透传给生成请求，
 * 大多数用户不需要改，展开后占了半屏会挤掉端口与保存按钮。
 */
@Composable
private fun ConfigCard(
    state: LocalModelsUiState,
    onDraftChange: (LocalModelConfigDraft) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
) {
    val draft = state.configDraft
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            if (draft == null) {
                Text(
                    text = if (state.configLoading) {
                        stringResource(R.string.local_models_admin_config_loading)
                    } else {
                        state.configError ?: stringResource(R.string.local_models_admin_config_read_failed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.configLoading) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!state.configLoading) {
                    Spacer(Modifier.height(8.dp))
                    ActionChip(
                        text = stringResource(R.string.local_models_admin_action_reload_config),
                        onClick = onReload,
                    )
                }
            } else {
                ConfigFields(
                    draft = draft,
                    errors = state.configFieldErrors,
                    saving = state.savingConfig,
                    error = state.configError,
                    onDraftChange = onDraftChange,
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun ConfigFields(
    draft: LocalModelConfigDraft,
    errors: Map<LocalModelConfigField, LocalModelConfigError>,
    saving: Boolean,
    error: String?,
    onDraftChange: (LocalModelConfigDraft) -> Unit,
    onSave: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.local_models_admin_label_context),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(
            R.string.local_models_admin_hint_context,
            LocalModelAdminEdit.MIN_CONTEXT_LENGTH,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.contextText,
        onValueChange = { onDraftChange(draft.copy(contextText = it)) },
        singleLine = true,
        isError = errors[LocalModelConfigField.CONTEXT] != null,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    errors[LocalModelConfigField.CONTEXT]?.let { issue ->
        FieldError(issue)
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.local_models_admin_label_port),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        ActionChip(
            text = stringResource(R.string.local_models_admin_action_auto_port),
            onClick = { onDraftChange(draft.copy(portText = "")) },
        )
    }
    Text(
        text = stringResource(R.string.local_models_admin_hint_port),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.portText,
        onValueChange = { onDraftChange(draft.copy(portText = it)) },
        singleLine = true,
        isError = errors[LocalModelConfigField.PORT] != null,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    errors[LocalModelConfigField.PORT]?.let { issue ->
        FieldError(issue)
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { advanced = !advanced },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.local_models_admin_label_kwargs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (advanced) {
                    R.string.local_models_admin_action_collapse
                } else {
                    R.string.local_models_admin_action_expand
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    if (advanced) {
        Text(
            text = stringResource(R.string.local_models_admin_hint_kwargs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft.kwargsText,
            onValueChange = { onDraftChange(draft.copy(kwargsText = it)) },
            singleLine = false,
            isError = errors[LocalModelConfigField.KWARGS] != null,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
        )
        errors[LocalModelConfigField.KWARGS]?.let { issue ->
            FieldError(issue)
        }
    }

    error?.let { message ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(12.dp))
    SaveButton(
        label = if (saving) {
            stringResource(R.string.local_models_admin_action_saving)
        } else {
            stringResource(R.string.local_models_admin_action_save_config)
        },
        enabled = draft.isDirty && !saving,
        onClick = onSave,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.local_models_admin_hint_config_scope),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 一条校验错误的中文文案。上下限类的错误把数字插进去，用户不必回头数位数。 */
@Composable
private fun FieldError(error: LocalModelConfigError) {
    val text = when (error.issue) {
        LocalModelConfigIssue.CONTEXT_NUMBER ->
            stringResource(R.string.local_models_admin_error_context_number)

        LocalModelConfigIssue.CONTEXT_TOO_SMALL -> stringResource(
            R.string.local_models_admin_error_context_min,
            LocalModelAdminEdit.MIN_CONTEXT_LENGTH,
        )

        LocalModelConfigIssue.PORT_NUMBER ->
            stringResource(R.string.local_models_admin_error_port_number)

        LocalModelConfigIssue.PORT_RANGE -> stringResource(
            R.string.local_models_admin_error_port_range,
            LocalModelAdminEdit.PORT_MIN,
            LocalModelAdminEdit.PORT_MAX,
        )

        LocalModelConfigIssue.KWARGS_JSON ->
            stringResource(R.string.local_models_admin_error_kwargs_json)

        LocalModelConfigIssue.KWARGS_OBJECT ->
            stringResource(R.string.local_models_admin_error_kwargs_object)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ModelsCard(
    models: List<LocalModelDto>,
    loaded: Boolean,
    busy: Boolean,
    deletingModelId: String?,
    onDownloadModel: (String) -> Unit,
    onRequestDelete: (LocalModelDto) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            if (models.isEmpty()) {
                Text(
                    text = if (loaded) {
                        stringResource(R.string.local_models_empty)
                    } else {
                        stringResource(R.string.local_models_status_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            models.forEachIndexed { index, model ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model.name.ifBlank { model.id },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(DownloadPolicy.formatBytes(model.sizeBytes))
                                val source = when (model.source) {
                                    "huggingface" -> stringResource(R.string.local_models_source_huggingface)
                                    "modelscope" -> stringResource(R.string.local_models_source_modelscope)
                                    else -> null
                                }
                                source?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.downloaded) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.local_models_downloaded),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            if (deletingModelId == model.id.ifBlank { model.name }) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                ActionChip(
                                    text = stringResource(R.string.local_models_admin_action_delete),
                                    danger = true,
                                    enabled = deletingModelId == null && !busy,
                                    onClick = { onRequestDelete(model) },
                                )
                            }
                        }
                    } else {
                        TextButton(
                            enabled = !busy,
                            onClick = { onDownloadModel(model.id.ifBlank { model.name }) },
                        ) { Text(stringResource(R.string.local_models_download)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(
    progress: LocalModelProgressDto,
    isRuntime: Boolean,
    active: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (isRuntime) {
                            R.string.local_models_source_server
                        } else {
                            R.string.local_models_source_model
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(progressLabelRes(progress.status)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
            progress.modelName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.local_models_model_name, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            val fraction = progressFraction(progress.downloadedBytes, progress.totalBytes)
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = progress.totalBytes?.let {
                    "${stringResource(R.string.local_models_progress_bytes, DownloadPolicy.formatBytes(progress.downloadedBytes), DownloadPolicy.formatBytes(it))} · ${stringResource(R.string.local_models_progress_percent, (fraction ?: 0f).times(100).toInt())}"
                } ?: stringResource(
                    R.string.local_models_progress_downloaded,
                    DownloadPolicy.formatBytes(progress.downloadedBytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active && progress.speedBytesPerSec > 0) {
                Text(
                    text = stringResource(
                        R.string.local_models_progress_speed,
                        DownloadPolicy.formatBytes(progress.speedBytesPerSec.toLong()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActiveDownload(progress.status) && progress.error.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                TextButton(enabled = !busy, onClick = onCancel) {
                    Text(stringResource(R.string.local_models_cancel))
                }
            }
            progress.error?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 小号动作按钮。危险动作用 error 容器色，避免和普通动作混成一片。 */
@Composable
private fun ActionChip(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surface
                    danger -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                danger -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@Composable
private fun SaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
