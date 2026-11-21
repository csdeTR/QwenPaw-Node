package io.qwenpaw.node.ui.channels

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.channel.channelDisplayNameRes
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.settings.Card
import io.qwenpaw.node.ui.settings.SectionHeader

/**
 * 频道列表。
 *
 * 2.3.0 起频道实现是插件：`repair.action` 分安装/启用/修复三态，列表按动作显示对应按钮。
 * 配置与二维码授权都在频道配置页（[ChannelConfigScreen]），列表只负责进入。
 */
@Composable
fun ChannelScreen(
    state: ChannelUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onRefresh: () -> Unit,
    onRepairChannel: (String) -> Unit,
    onOpenConfig: (key: String, name: String) -> Unit,
    onConsumeNotice: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    WebEntryDialog(pageKey = "channels", onOpenConsole = onOpenConsole)
    LaunchedEffect(Unit) { onEnsureLoaded() }
    LaunchedEffect(state.noticeRes) {
        val res = state.noticeRes ?: return@LaunchedEffect
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
        onConsumeNotice()
    }

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
            // 版本过低时整页提示：这些实例上频道接口不存在，列表与安装操作都没有意义。
            if (state.versionUnsupported) {
                VersionBlockedNotice(state.serverVersionRaw)
                return@Column
            }
            ConsoleHint(onOpenConsole = onOpenConsole)
            if (state.versionUnknown) {
                Text(
                    text = stringResource(R.string.channels_version_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
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
                    busy = state.busyChannel == row.key,
                    onRepair = { onRepairChannel(row.key) },
                    onConfig = {
                        onOpenConfig(row.key, channelName(context, row, row.key))
                    },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** 版本低于门槛的整页提示：只说明要求与当前版本，不给任何频道操作。 */
@Composable
private fun VersionBlockedNotice(currentVersion: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                currentVersion.orEmpty(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 优先本地化名称，其次服务端显示名，最后原样显示键名。 */
private fun channelName(context: android.content.Context, row: ChannelRow?, key: String): String {
    channelDisplayNameRes(key)?.let { return context.getString(it) }
    row?.label?.takeIf { it.isNotBlank() }?.let { return it }
    return key
}

@Composable
private fun ChannelRowView(
    row: ChannelRow,
    busy: Boolean,
    onRepair: () -> Unit,
    onConfig: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channelName(LocalContext.current, row, row.key),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (row.needsRepair && !busy) {
                    CompactTextButton(
                        text = repairActionLabel(row),
                        onClick = onRepair,
                    )
                }
                CompactTextButton(
                    text = stringResource(R.string.channels_action_config),
                    onClick = onConfig,
                )
            }
            Text(
                text = statusText(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 卡片内的紧凑文字按钮：默认高度的按钮会让每张卡多占近二十 dp。 */
@Composable
private fun CompactTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun statusText(row: ChannelRow): String {
    val enabled = stringResource(
        if (row.enabled) R.string.channels_status_enabled else R.string.channels_status_disabled,
    )
    // 缺插件与缺运行时的处置都是安装，但显示上要区分：后者不是「没装」。
    val plugin = when {
        row.availability == "missing_runtime" ->
            stringResource(R.string.channels_availability_missing_runtime)

        row.missingPlugin -> stringResource(R.string.channels_status_missing_code)
        row.repairAction == "enable" ->
            stringResource(R.string.channels_status_disabled_plugin)

        row.repairAction != null -> stringResource(R.string.channels_status_validation_pending)
        else -> null
    }
    return listOfNotNull(enabled.takeIf { it.isNotBlank() }, plugin).joinToString("，")
}

/** 缺插件时主操作是安装；已安装但停用或待修复时沿用原动作名。 */
@Composable
private fun repairActionLabel(row: ChannelRow): String = when {
    row.missingPlugin -> stringResource(R.string.channels_install_plugin)
    row.repairAction == "enable" -> stringResource(R.string.channels_action_enable)
    else -> stringResource(R.string.channels_action_repair)
}
