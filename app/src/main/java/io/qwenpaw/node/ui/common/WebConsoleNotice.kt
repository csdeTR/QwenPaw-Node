package io.qwenpaw.node.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R

/**
 * 本进程内已经看过的 Web 提示页。
 *
 * 按进程而不是按偏好持久化：用户要求 App 关闭重开后重新提示，
 * 因此这里刻意不写 SharedPreferences。
 */
private val seenPages = mutableSetOf<String>()

/**
 * Web 管理页的首次进入提示。
 *
 * 每个页面第一次进入时弹一次；无论用户选择继续还是去 Web 控制台，
 * 之后本进程内都不再弹。页面自己还要放一个 [ConsoleHint] 作为常驻说明。
 */
@Composable
fun WebEntryDialog(pageKey: String, onOpenConsole: (() -> Unit)? = null) {
    var visible by remember(pageKey) { mutableStateOf(pageKey !in seenPages) }
    if (!visible) return
    fun dismiss() {
        seenPages += pageKey
        visible = false
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.web_notice_title)) },
        text = { Text(stringResource(R.string.web_notice_message)) },
        confirmButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.web_notice_continue))
            }
        },
        dismissButton = if (onOpenConsole != null) {
            {
                TextButton(
                    onClick = {
                        dismiss()
                        onOpenConsole()
                    },
                ) { Text(stringResource(R.string.web_notice_open_console)) }
            }
        } else {
            null
        },
    )
}

/** 管理页顶部的常驻说明。地址不可用时只显示文字，不给无效按钮。 */
@Composable
fun ConsoleHint(onOpenConsole: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.web_hint_console),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onOpenConsole != null) {
            TextButton(onClick = onOpenConsole) {
                Text(
                    text = stringResource(R.string.web_notice_open_console),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
