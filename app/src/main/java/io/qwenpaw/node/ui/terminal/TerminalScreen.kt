package io.qwenpaw.node.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.terminal.TerminalConnection
import io.qwenpaw.node.data.terminal.TerminalShell
import io.qwenpaw.node.data.terminal.TerminalSource
import android.widget.Toast
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.VTermKey

/** 终端页的动作集合。参数很多，打包后 `MainActivity` 只组装一次。 */
data class TerminalActions(
    val open: () -> Unit,
    val chooseLocal: () -> Unit,
    val chooseSsh: () -> Unit,
    val chooseDirect: () -> Unit,
    val switchSource: () -> Unit,
    val dismissSource: () -> Unit,
    val newTab: () -> Unit,
    val selectTab: (String) -> Unit,
    val closeActive: () -> Unit,
    val onLeave: () -> Unit,
    val respondHostKey: (Boolean) -> Unit,
    val clearHostKey: () -> Unit,
    val dismissNotice: () -> Unit,
    val dismissError: () -> Unit,
    val reconnect: () -> Unit,
    val selectShell: (TerminalShell) -> Unit,
    val setAsDefault: (Boolean) -> Unit,
    val setFontSize: (Int) -> Unit,
    val claimPinchTip: () -> Boolean,
    val renameTab: (String, String) -> Unit,
)

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    actions: TerminalActions,
    onBack: () -> Unit,
) {
    val activeTab = state.activeTab
    val emulator = activeTab?.emulator
    val connection = activeTab?.connection ?: TerminalConnection.Idle
    val sticky = remember { StickyModifierManager() }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val density = LocalDensity.current
    // 键盘动画期间冻结 termlib 的行列重排，稳定后一次提交，避免逐行隐藏/显示。
    val imeSettle = rememberImeSettleState()
    // Scaffold 的 padding 已经包含导航栏；IME 高度也含导航栏，这里减掉避免重复留白。
    val navigationBarsBottom = WindowInsets.navigationBars.getBottom(density)
    var confirmClose by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TerminalTab?>(null) }

    LaunchedEffect(Unit) { actions.open() }
    DisposableEffect(Unit) {
        onDispose { actions.onLeave() }
    }
    LaunchedEffect(state.activeTabId) {
        sticky.clearAll()
        runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TerminalTopBar(
                state = state,
                onBack = onBack,
                onSwitch = actions.switchSource,
                onNew = actions.newTab,
                onClose = {
                    if (connection is TerminalConnection.Exited) {
                        actions.closeActive()
                    } else {
                        confirmClose = true
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    bottom = with(density) {
                        (imeSettle.bottomPx - navigationBarsBottom).coerceAtLeast(0).toDp()
                    },
                ),
        ) {
            if (state.tabs.size > 1) {
                TerminalTabs(
                    tabs = state.tabs,
                    activeTabId = state.activeTabId,
                    onSelect = actions.selectTab,
                    onRename = { renameTarget = it },
                )
            }

            state.error?.let { message ->
                NoticeStrip(
                    text = message,
                    isError = true,
                    onDismiss = actions.dismissError,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = actions.reconnect,
                )
            }
            state.notice?.let { message ->
                NoticeStrip(
                    text = message,
                    isError = false,
                    onDismiss = actions.dismissNotice,
                    actionLabel = null,
                    onAction = {},
                )
            }

            when (val current = connection) {
                is TerminalConnection.Failed -> {
                    // 用资源里的完整句子匹配而不是写死中文：换语言后这条分支仍成立，
                    // 多地址汇总失败时消息里也包含同一句。
                    val hostKeyChanged =
                        current.message.contains(stringResource(R.string.ssh_error_host_key_changed))
                    NoticeStrip(
                        text = current.message,
                        isError = true,
                        onDismiss = null,
                        actionLabel = if (hostKeyChanged) {
                            stringResource(R.string.terminal_action_clear_host_key)
                        } else {
                            stringResource(R.string.terminal_action_reconnect)
                        },
                        onAction = {
                            if (hostKeyChanged) {
                                actions.clearHostKey()
                                actions.reconnect()
                            } else {
                                actions.reconnect()
                            }
                        },
                    )
                }
                is TerminalConnection.Exited -> {
                    val code = current.code
                    NoticeStrip(
                        text = if (code != null) {
                            stringResource(R.string.terminal_exited_with_code, code)
                        } else {
                            stringResource(R.string.terminal_exited)
                        },
                        isError = false,
                        onDismiss = null,
                        actionLabel = stringResource(R.string.terminal_action_reopen),
                        onAction = actions.reconnect,
                    )
                }
                TerminalConnection.Connecting -> Text(
                    text = stringResource(R.string.terminal_connecting),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                else -> Unit
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    state.tabs.isEmpty() -> EmptyTerminal(onChoose = actions.switchSource)

                    activeTab != null && emulator != null -> key(
                        activeTab.id,
                        state.fontSizeSp,
                    ) {
                        Terminal(
                            terminalEmulator = emulator,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (event.changes.size > 1 && actions.claimPinchTip()) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.terminal_pinch_tip),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            initialFontSize = state.fontSizeSp.sp,
                            minFontSize = 9.sp,
                            maxFontSize = 24.sp,
                            keyboardEnabled = true,
                            showSoftKeyboard = true,
                            focusRequester = focusRequester,
                            modifierManager = sticky,
                            onTerminalTap = {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                            onPasteRequest = {
                                clipboard.getText()?.text?.let(emulator::pasteText)
                            },
                            onPageGesture = null,
                            resizeSuspended = imeSettle.suspended,
                        )
                    }

                    activeTab != null && emulator == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> EmptyTerminal(onChoose = actions.switchSource)
                }
            }

            if (emulator != null) {
                KeyBar(
                    modifiers = sticky,
                    emulator = emulator,
                    canChangeFont = state.fontSizeSp in FONT_MIN..FONT_MAX,
                    onFontDecrease = { actions.setFontSize(state.fontSizeSp - 1) },
                    onFontIncrease = { actions.setFontSize(state.fontSizeSp + 1) },
                    onPaste = {
                        clipboard.getText()?.text?.let(emulator::pasteText)
                    },
                )
            }
        }
    }

    if (state.sourceDialog) {
        TerminalSourceDialog(state = state, actions = actions)
    }

    renameTarget?.let { tab ->
        var draft by remember(tab.id) { mutableStateOf(tab.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.terminal_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.terminal_rename_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        actions.renameTab(tab.id, draft)
                        renameTarget = null
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.hostKeyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { actions.respondHostKey(false) },
            title = { Text(stringResource(R.string.terminal_host_key_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.terminal_host_key_message))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${prompt.keyType}  ${prompt.host}:${prompt.port}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        prompt.fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.terminal_host_key_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { actions.respondHostKey(true) }) {
                    Text(stringResource(R.string.terminal_host_key_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.respondHostKey(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.terminal_close_title)) },
            text = {
                Text(
                    if (activeTab?.source == TerminalSource.LOCAL) {
                        stringResource(R.string.terminal_close_local_message)
                    } else {
                        stringResource(R.string.terminal_close_ssh_message)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    actions.closeActive()
                }) { Text(stringResource(R.string.action_close)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private const val FONT_MIN = 9
private const val FONT_MAX = 24

@Composable
private fun EmptyTerminal(onChoose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.terminal_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onChoose) {
            Text(stringResource(R.string.terminal_choose_source))
        }
    }
}

@Composable
private fun TerminalTopBar(
    state: TerminalUiState,
    onBack: () -> Unit,
    onSwitch: () -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(
            icon = QwenIcons.arrowLeft,
            description = stringResource(R.string.action_back),
            onClick = onBack,
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            val defaultTitle = stringResource(R.string.terminal_default_title)
            Text(
                text = state.title.ifBlank { defaultTitle },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = when (state.source) {
                    TerminalSource.LOCAL -> stringResource(R.string.terminal_source_local)
                    TerminalSource.SSH_RELAY -> stringResource(R.string.terminal_source_relay)
                    TerminalSource.SSH_DIRECT -> stringResource(R.string.terminal_source_direct)
                    null -> stringResource(R.string.terminal_source_none)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconCircle(
            icon = QwenIcons.plus,
            description = stringResource(R.string.terminal_new_tab),
            onClick = onNew,
            onLongClick = onSwitch,
            enabled = !state.busy && state.canAdd,
        )
        if (state.activeTab != null) {
            IconCircle(
                icon = QwenIcons.close,
                description = stringResource(R.string.terminal_close_title),
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun TerminalTabs(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onRename: (TerminalTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val defaultTitle = stringResource(R.string.terminal_default_title)
        tabs.forEach { tab ->
            val selected = tab.id == activeTabId
            Text(
                text = tab.title.ifBlank { defaultTitle },
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .combinedClickable(
                        onClick = { onSelect(tab.id) },
                        onLongClick = { onRename(tab) },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun KeyBar(
    modifiers: StickyModifierManager,
    emulator: TerminalEmulator,
    canChangeFont: Boolean,
    onFontDecrease: () -> Unit,
    onFontIncrease: () -> Unit,
    onPaste: () -> Unit,
) {
    fun sendKey(key: Int) {
        emulator.dispatchKey(modifiers.mask, key)
        modifiers.clearAll()
    }

    fun sendControl(code: Int) {
        emulator.dispatchCharacter(StickyModifierManager.CTRL, code)
        modifiers.clearAll()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton("Esc") { sendKey(VTermKey.ESCAPE) }
            KeyButton("Tab") { sendKey(VTermKey.TAB) }
            KeyButton("CTRL", selected = modifiers.ctrl) { modifiers.toggleCtrl() }
            KeyButton("ALT", selected = modifiers.alt) { modifiers.toggleAlt() }
            KeyButton("SHIFT", selected = modifiers.shift) { modifiers.toggleShift() }
            KeyButton("Home") { sendKey(VTermKey.HOME) }
            KeyButton("End") { sendKey(VTermKey.END) }
            KeyButton("PgUp") { sendKey(VTermKey.PAGEUP) }
            KeyButton("PgDn") { sendKey(VTermKey.PAGEDOWN) }
            KeyButton("A-", enabled = canChangeFont, onClick = onFontDecrease)
            KeyButton("A+", enabled = canChangeFont, onClick = onFontIncrease)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton("Ins") { sendKey(VTermKey.INS) }
            KeyButton("Del") { sendKey(VTermKey.DEL) }
            KeyButton("←") { sendKey(VTermKey.LEFT) }
            KeyButton("↑") { sendKey(VTermKey.UP) }
            KeyButton("↓") { sendKey(VTermKey.DOWN) }
            KeyButton("→") { sendKey(VTermKey.RIGHT) }
            KeyButton("↵") { sendKey(VTermKey.ENTER) }
            KeyButton("⌫") { sendKey(VTermKey.BACKSPACE) }
            KeyButton("Ctrl-C") { sendControl('c'.code) }
            KeyButton("Ctrl-D") { sendControl('d'.code) }
            KeyButton("Ctrl-Z") { sendControl('z'.code) }
            KeyButton(stringResource(R.string.terminal_key_paste), onClick = onPaste)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            selected -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun TerminalSourceDialog(
    state: TerminalUiState,
    actions: TerminalActions,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = actions.dismissSource,
        title = { Text(stringResource(R.string.terminal_choose_source)) },
        text = {
            Column {
                if (state.shellOptions.size > 1) {
                    Text(
                        stringResource(R.string.terminal_shell_section_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.shellOptions.forEach { shell ->
                            FilterChip(
                                selected = state.selectedShell == shell,
                                onClick = {
                                    actions.selectShell(shell)
                                    if (shell == TerminalShell.GIT_BASH) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.terminal_git_bash_hint),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                label = { Text(shellLabel(shell)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                SourceRow(
                    title = stringResource(R.string.terminal_source_local),
                    description = stringResource(R.string.terminal_source_local_desc),
                    reason = state.localReason,
                    enabled = state.localAvailable,
                    onClick = actions.chooseLocal,
                )
                Spacer(Modifier.height(10.dp))
                SourceRow(
                    title = stringResource(R.string.terminal_source_relay),
                    description = stringResource(R.string.terminal_source_relay_desc),
                    reason = state.sshReason,
                    enabled = state.sshAvailable,
                    onClick = actions.chooseSsh,
                )
                Spacer(Modifier.height(10.dp))
                SourceRow(
                    title = stringResource(R.string.terminal_source_direct),
                    description = stringResource(R.string.terminal_source_direct_desc),
                    reason = state.directReason,
                    hint = state.directHint,
                    enabled = state.directAvailable,
                    onClick = actions.chooseDirect,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.setAsDefault,
                        onCheckedChange = { actions.setAsDefault(it) },
                    )
                    Text(stringResource(R.string.terminal_set_as_default))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.terminal_credentials_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.terminal_credentials_note_direct),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = actions.dismissSource) {
                Text(
                    if (state.tabs.isNotEmpty()) {
                        stringResource(R.string.action_cancel)
                    } else {
                        stringResource(R.string.action_back)
                    },
                )
            }
        },
    )
}

@Composable
private fun shellLabel(shell: TerminalShell): String = when (shell) {
    TerminalShell.DEFAULT -> stringResource(R.string.terminal_source_local)
    TerminalShell.BASH -> "bash"
    TerminalShell.DASH -> "dash"
    TerminalShell.POWERSHELL -> "PowerShell"
    TerminalShell.CMD -> stringResource(R.string.terminal_shell_cmd)
    TerminalShell.GIT_BASH -> "Bash"
}

@Composable
private fun SourceRow(
    title: String,
    description: String,
    reason: String?,
    hint: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reason != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeStrip(
    text: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        actionLabel?.let {
            TextButton(onClick = onAction) { Text(it) }
        }
        onDismiss?.let {
            TextButton(onClick = it) { Text(stringResource(R.string.action_close)) }
        }
    }
}
