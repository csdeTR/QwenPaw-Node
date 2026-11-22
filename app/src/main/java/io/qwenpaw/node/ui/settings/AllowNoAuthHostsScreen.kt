package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.common.pageContentInsets
import io.qwenpaw.node.ui.theme.LocalCaptionColor

/**
 * 免鉴权地址名单。
 *
 * 名单是一个整体：增删都在本地完成，点保存时一次性提交全量列表，因此保存前
 * 必须让用户看清这份名单里有哪些地址——保存后这些地址访问服务端时就不再要求
 * 登录。非回环地址单独标出，因为它们把免鉴权暴露到了局域网甚至公网。
 *
 * 服务端未开启登录时名单不生效，页面只做说明，不隐藏编辑入口：用户可能正在
 * 准备开启登录。
 */
@Composable
fun AllowNoAuthHostsScreen(
    state: AllowNoAuthHostsUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRequestSave: () -> Unit,
    onConfirmSave: () -> Unit,
    onDismissSave: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { onEnsureLoaded() }
    WebEntryDialog(pageKey = "allow_no_auth_hosts", onOpenConsole = onOpenConsole)

    if (state.confirming) {
        SaveConfirmDialog(state = state, onConfirm = onConfirmSave, onDismiss = onDismissSave)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = pageContentInsets(),
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.allow_hosts_title),
                onBack = onBack,
                // 名单没读到时不给保存入口：空名单提交会把服务端配置覆盖掉。
                action = if (state.loaded) stringResource(R.string.action_save) else null,
                busy = state.saving,
                onAction = onRequestSave,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 常驻说明不随内容滚动，滚到底部后仍能看出此页更适合在 Web 控制台完成。
            ConsoleHint(onOpenConsole)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                if (!state.loaded) {
                    UnloadedBody(
                        loading = state.loading,
                        error = state.error,
                        onRetry = onEnsureLoaded,
                    )
                } else {
                    state.error?.let { message ->
                        MessageBanner(text = message, danger = true, onDismiss = onDismissNotice)
                    }
                    state.notice?.let { message ->
                        MessageBanner(text = message, danger = false, onDismiss = onDismissNotice)
                    }
                    if (state.authEnabled == false) {
                        Text(
                            text = stringResource(R.string.allow_hosts_auth_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }

                    SectionHeader(stringResource(R.string.allow_hosts_section_add))
                    AddCard(
                        draft = state.draftInput,
                        enabled = !state.saving,
                        onDraftChange = onDraftChange,
                        onAdd = onAdd,
                    )

                    SectionHeader(
                        title = stringResource(R.string.allow_hosts_section_list),
                        trailing = if (state.hosts.isEmpty()) null else state.hosts.size.toString(),
                    )
                    HostsCard(
                        hosts = state.hosts,
                        enabled = !state.saving,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnloadedBody(loading: Boolean, error: String?, onRetry: () -> Unit) {
    if (loading) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        Text(
            text = stringResource(R.string.allow_hosts_loading),
            style = MaterialTheme.typography.labelSmall,
            color = LocalCaptionColor.current,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    } else {
        Text(
            text = error ?: stringResource(R.string.allow_hosts_not_loaded),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun AddCard(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val canAdd = enabled && draft.isNotBlank()
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.allow_hosts_add_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAdd, enabled = canAdd) {
                    Text(stringResource(R.string.action_add))
                }
            }
            Text(
                text = stringResource(R.string.allow_hosts_add_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
            )
        }
    }
}

@Composable
private fun HostsCard(hosts: List<String>, enabled: Boolean, onRemove: (String) -> Unit) {
    Card {
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(R.string.allow_hosts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
        } else {
            hosts.forEachIndexed { index, host ->
                if (index > 0) Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = host,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = stringResource(
                                if (AllowNoAuthHostsEdit.isLoopback(host)) {
                                    R.string.allow_hosts_badge_loopback
                                } else {
                                    R.string.allow_hosts_badge_remote
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalCaptionColor.current,
                        )
                    }
                    TextButton(onClick = { onRemove(host) }, enabled = enabled) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            }
        }
    }
}

/**
 * 保存前的确认：逐条列出保存后的名单。
 *
 * 非回环地址在本行内标明暴露范围，省得用户再翻一页去判断；若本次删掉了
 * `127.0.0.1` 或 `::1`，再补一行说明本机回环也会失去免鉴权。
 */
@Composable
private fun SaveConfirmDialog(
    state: AllowNoAuthHostsUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lostLoopbacks = state.removedIpLoopbacks
    val lostText = if (lostLoopbacks.isEmpty()) {
        null
    } else {
        stringResource(
            R.string.allow_hosts_confirm_loopback_lost,
            lostLoopbacks.joinToString("、"),
        )
    }
    val impacts = buildList {
        state.hosts.forEach { host ->
            if (AllowNoAuthHostsEdit.isLoopback(host)) {
                add(stringResource(R.string.allow_hosts_impact_loopback, host))
            } else {
                add(stringResource(R.string.allow_hosts_impact_remote, host))
            }
        }
        lostText?.let { add(it) }
    }
    DestructiveActionDialog(
        title = stringResource(R.string.allow_hosts_confirm_title),
        message = stringResource(
            if (state.hosts.isEmpty()) {
                R.string.allow_hosts_confirm_empty
            } else {
                R.string.allow_hosts_confirm_message
            },
        ),
        impacts = impacts,
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MessageBanner(text: String, danger: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
