package io.qwenpaw.node.ui.localmodels

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.localmodel.isActiveDownload
import io.qwenpaw.node.data.localmodel.progressFraction
import io.qwenpaw.node.data.localmodel.progressLabelRes
import io.qwenpaw.node.data.model.LocalModelDto
import io.qwenpaw.node.data.model.LocalModelProgressDto
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader
import io.qwenpaw.node.ui.workspace.DownloadPolicy

/**
 * 本地模型：运行时状态、可下载清单与下载进度。
 *
 * 下载发生在服务端，手机只触发与观察。启动/停止运行时、删除模型与配置
 * 属于 0.7.3，本页不提供。
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
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onEnsureLoaded() }
    DisposableEffect(Unit) { onDispose { onStopPolling() } }
    LaunchedEffect(state.messageRes) {
        val res = state.messageRes ?: return@LaunchedEffect
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
        onConsumeMessage()
    }

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
            ServerCard(state = state, onInstallRuntime = onInstallRuntime)

            SectionHeader(stringResource(R.string.local_models_section_models))
            ModelsCard(
                models = state.models,
                loaded = state.loaded,
                busy = state.actionBusy || state.activeSource != null,
                onDownloadModel = onDownloadModel,
            )

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
        }
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

@Composable
private fun ServerCard(state: LocalModelsUiState, onInstallRuntime: () -> Unit) {
    val server = state.server
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
        }
    }
}

@Composable
private fun ModelsCard(
    models: List<LocalModelDto>,
    loaded: Boolean,
    busy: Boolean,
    onDownloadModel: (String) -> Unit,
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
                        Text(
                            text = stringResource(R.string.local_models_downloaded),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
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