package io.qwenpaw.node.ui.channels

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.channel.channelDisplayNameRes
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader

/**
 * 频道列表与扫码授权。
 *
 * 2.3.0 起频道实现是插件：可扫码的频道由服务端 `qrcode_available` 声明，
 * 未安装的频道可直接从这里安装插件；其余频道说明原因并指向 0.7.4 的配置表单。
 */
@Composable
fun ChannelScreen(
    state: ChannelUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onRefresh: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onInstallChannel: (String) -> Unit,
    onRetry: () -> Unit,
    onCloseAuth: () -> Unit,
    onStopPolling: () -> Unit,
    onConsumeEnabled: () -> Unit,
    onConsumeNotice: () -> Unit,
) {
    val context = LocalContext.current
    var dialogRow by remember { mutableStateOf<ChannelRow?>(null) }

    LaunchedEffect(Unit) { onEnsureLoaded() }
    DisposableEffect(Unit) { onDispose { onStopPolling() } }

    LaunchedEffect(state.enabledChannel) {
        val key = state.enabledChannel ?: return@LaunchedEffect
        val name = channelName(context, state.channels.firstOrNull { it.key == key }, key)
        Toast.makeText(
            context,
            context.getString(R.string.channel_qr_enabled, name),
            Toast.LENGTH_SHORT,
        ).show()
        onConsumeEnabled()
    }
    LaunchedEffect(state.noticeRes) {
        val res = state.noticeRes ?: return@LaunchedEffect
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
        onConsumeNotice()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AdminTopBar(
                    title = stringResource(R.string.channels_title),
                    onBack = onBack,
                    action = stringResource(R.string.channels_refresh),
                    busy = state.loading,
                    onAction = onRefresh,
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

                if (state.loaded && state.channels.isEmpty()) {
                    SectionHeader(stringResource(R.string.channels_title))
                    Card {
                        Text(
                            text = stringResource(R.string.channels_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    return@Column
                }

                SectionHeader(stringResource(R.string.channels_title))
                state.channels.forEach { row ->
                    ChannelRowView(
                        row = row,
                        installing = state.installing == row.key,
                        onOpen = { onOpenChannel(row.key) },
                        onExplain = { dialogRow = row },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        state.auth?.let { auth ->
            ChannelQrPanel(
                auth = auth,
                name = channelName(context, state.channels.firstOrNull { it.key == auth.channel }, auth.channel),
                onRetry = onRetry,
                onClose = onCloseAuth,
            )
        }
    }

    dialogRow?.let { row ->
        ChannelDialog(
            row = row,
            name = channelName(context, row, row.key),
            installing = state.installing == row.key,
            onInstall = {
                dialogRow = null
                onInstallChannel(row.key)
            },
            onDismiss = { dialogRow = null },
        )
    }
}

/** 优先用服务端显示名（插件频道也有），其次内置翻译，最后原样显示键名。 */
private fun channelName(context: android.content.Context, row: ChannelRow?, key: String): String {
    row?.label?.takeIf { it.isNotBlank() }?.let { return it }
    val res = channelDisplayNameRes(key) ?: return key
    return context.getString(res)
}

@Composable
private fun ChannelRowView(
    row: ChannelRow,
    installing: Boolean,
    onOpen: () -> Unit,
    onExplain: () -> Unit,
) {
    Card {
        val context = LocalContext.current
        val enabledText = stringResource(
            if (row.enabled) R.string.channels_status_enabled else R.string.channels_status_disabled,
        )
        val qrText = when (row.availability) {
            "missing_code" -> stringResource(R.string.channels_plugin_missing)
            "failed" -> stringResource(R.string.channels_unavailable)
            else -> stringResource(R.string.channels_no_qr)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channelName(context, row, row.key),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (row.qrAvailable) enabledText else "$enabledText · $qrText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                installing -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                row.qrAvailable -> TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.channel_qr_title))
                }

                row.installable -> TextButton(onClick = onExplain) {
                    Text(stringResource(R.string.channels_install))
                }

                else -> TextButton(onClick = onExplain) {
                    Text(stringResource(R.string.channels_no_qr))
                }
            }
        }
    }
}

/** 未安装 / 不可用 / 不支持扫码三种说明。 */
@Composable
private fun ChannelDialog(
    row: ChannelRow,
    name: String,
    installing: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val installable = row.installable
    val failed = row.availability == "failed"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when {
                        installable -> R.string.channels_install_title
                        failed -> R.string.channels_unavailable_title
                        else -> R.string.channel_qr_unsupported_title
                    },
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    when {
                        installable -> R.string.channels_install_message
                        failed -> R.string.channels_unavailable_message
                        else -> R.string.channel_qr_unsupported_message
                    },
                    if (installable || !failed) name else (row.diagnostic ?: stringResource(R.string.channels_unavailable_unknown)),
                ),
            )
        },
        confirmButton = {
            if (installable) {
                TextButton(enabled = !installing, onClick = onInstall) {
                    Text(stringResource(R.string.channels_install))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.channel_qr_dismiss)) }
            }
        },
        dismissButton = {
            if (installable) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * 二维码面板。
 *
 * 二维码是服务端生成的不带前缀的 base64 PNG，这里直接解码，不走网络。
 * 关闭时 ViewModel 会回收服务端会话。
 */
@Composable
private fun ChannelQrPanel(
    auth: ChannelAuthState,
    name: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val bitmap = remember(auth.qrBase64) {
        runCatching {
            val bytes = Base64.decode(auth.qrBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${stringResource(R.string.channel_qr_title)} · $name",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))

            when {
                auth.phase == ChannelAuthPhase.LOADING -> {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.channel_qr_getting),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }

                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(260.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )

                auth.phase == ChannelAuthPhase.WAITING -> Text(
                    text = stringResource(R.string.channel_qr_load_failed, ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = when (auth.phase) {
                    ChannelAuthPhase.LOADING -> ""
                    ChannelAuthPhase.WAITING -> when (auth.rawStatus) {
                        "scanned" -> stringResource(R.string.channel_qr_scanned)
                        "waiting", "" -> stringResource(R.string.channel_qr_waiting)
                        else -> stringResource(R.string.channel_qr_raw_status, auth.rawStatus)
                    }

                    ChannelAuthPhase.SAVING -> stringResource(R.string.channel_qr_saving)
                    ChannelAuthPhase.EXPIRED -> stringResource(R.string.channel_qr_expired)
                    ChannelAuthPhase.FAILED -> auth.failure
                        ?: stringResource(R.string.channel_qr_failed_unknown)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (auth.phase == ChannelAuthPhase.EXPIRED || auth.phase == ChannelAuthPhase.FAILED) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.channel_qr_refresh), color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.channel_qr_close), color = Color.White)
                }
            }
        }
    }
}