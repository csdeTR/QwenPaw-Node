package io.qwenpaw.node.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.validateBrowserUrl

/**
 * 离开应用打开浏览器前的二次确认。
 *
 * 服务端与市场下发的链接都不可信，先展示完整地址与域名，用户确认后才交给系统。
 * 已有确认语义的弹窗（更新、插件更新、Harness 登录提示）直接调用 [openBrowser]，
 * 不再叠加一层。
 */
@Composable
fun BrowserConfirmDialog(
    url: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (url == null) return
    val check = remember(url) { validateBrowserUrl(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_confirm_title)) },
        text = {
            Column {
                if (check.ok) {
                    Text(stringResource(R.string.browser_confirm_message))
                    Spacer(androidx.compose.ui.Modifier.height(8.dp))
                    Text(
                        text = check.url.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                    )
                    check.host?.let { host ->
                        Spacer(androidx.compose.ui.Modifier.height(6.dp))
                        Text(stringResource(R.string.browser_confirm_host, host))
                    }
                } else {
                    Text(stringResource(R.string.browser_confirm_invalid))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = check.ok,
                onClick = { check.url?.let(onConfirm) },
            ) { Text(stringResource(R.string.browser_confirm_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 返回一个「先确认再打开」的动作，供普通页面链接复用。 */
@Composable
fun rememberBrowserConfirm(): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    BrowserConfirmDialog(
        url = pending,
        onDismiss = { pending = null },
        onConfirm = { url ->
            pending = null
            openBrowser(context, url)
        },
    )
    return remember {
        { url: String -> pending = url }
    }
}

/** 已确认过的入口直接用它；非法地址不会启动任何 Activity。 */
fun openBrowser(context: Context, url: String): Boolean {
    val check = validateBrowserUrl(url)
    val target = check.url ?: return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }.isSuccess
}
