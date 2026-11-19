package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R

/**
 * 后台策略：工具超时后默认怎么处理。
 *
 * 服务端只有一个字段 `default_action`，取值 `keep_foreground`（继续跑完）与
 * `offload`（转入后台）。两选一用开关行表达，不写两个按钮——开关的心智更清楚，
 * 也与其他设置页的控件一致。
 */
@Composable
internal fun OffloadPolicySection(
    action: String,
    loading: Boolean,
    error: String?,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        SectionHeader(stringResource(R.string.offload_header_timeout))
        Card {
            SwitchRow(
                title = stringResource(R.string.offload_switch_title),
                subtitle = stringResource(R.string.offload_switch_subtitle),
                checked = action == OFFLOAD,
                enabled = !loading,
                onCheckedChange = { enabled ->
                    onAction(if (enabled) OFFLOAD else KEEP_FOREGROUND)
                },
            )
        }
        SectionHeader(stringResource(R.string.offload_header_notes))
        Text(
            text = stringResource(R.string.offload_notes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal const val OFFLOAD = "offload"
internal const val KEEP_FOREGROUND = "keep_foreground"