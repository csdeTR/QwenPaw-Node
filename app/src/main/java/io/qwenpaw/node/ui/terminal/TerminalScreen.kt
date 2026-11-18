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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                .imePadding(),
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
                    actionLabel = "重试",
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
                    val hostKeyChanged = current.message.contains("主机密钥")
                    NoticeStrip(
                        text = current.message,
                        isError = true,
                        onDismiss = null,
                        actionLabel = if (hostKeyChanged) "清除主机密钥" else "重连",
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
                is TerminalConnection.Exited -> NoticeStrip(
                    text = "终端已退出${current.code?.let { "（退出码 $it）" } ?: ""}",
                    isError = false,
                    onDismiss = null,
                    actionLabel = "重新打开",
                    onAction = actions.reconnect,
                )
                TerminalConnection.Connecting -> Text(
                    text = "正在连接…",
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
                                                    "双指缩放是临时放大；调整字号请用 A+ / A-",
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
                            resizeSuspended = false,
                        )
                    }

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
            title = { Text("重命名标签") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("标签名称") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        actions.renameTab(tab.id, draft)
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    state.hostKeyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { actions.respondHostKey(false) },
            title = { Text("确认主机密钥") },
            text = {
                Column {
                    Text("首次连接需要确认服务器身份。请与服务器管理员核对下面的指纹。")
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
                        "指纹变化或与管理员给出的不一致时，请取消连接。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { actions.respondHostKey(true) }) {
                    Text("信任并连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.respondHostKey(false) }) {
                    Text("取消")
                }
            },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("关闭终端") },
            text = {
                Text(
                    if (activeTab?.source == TerminalSource.LOCAL) {
                        "关闭后会终止服务端终端及其子进程，正在运行的命令会中断。"
                    } else {
                        "关闭后会断开当前 SSH 会话。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    actions.closeActive()
                }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text("取消") }
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
            "还没有打开终端",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onChoose) { Text("选择终端方式") }
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
        IconCircle(icon = QwenIcons.arrowLeft, description = "返回", onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "终端" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = when (state.source) {
                    TerminalSource.LOCAL -> "默认"
                    TerminalSource.SSH_RELAY -> "Bridge 中继"
                    TerminalSource.SSH_DIRECT -> "SSH 直连"
                    null -> "未选择方式"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconCircle(
            icon = QwenIcons.plus,
            description = "新建终端",
            onClick = onNew,
            onLongClick = onSwitch,
            enabled = !state.busy && state.canAdd,
        )
        if (state.activeTab != null) {
            IconCircle(icon = QwenIcons.close, description = "关闭终端", onClick = onClose)
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
        tabs.forEach { tab ->
            val selected = tab.id == activeTabId
            Text(
                text = tab.title.ifBlank { "终端" },
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
            KeyButton("粘贴", onClick = onPaste)
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
        title = { Text("选择终端方式") },
        text = {
            Column {
                if (state.shellOptions.size > 1) {
                    Text(
                        "默认 / Bridge 中继的登录 shell",
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
                                            "请先确认目标 Windows 已安装 Bash（Git Bash 或 WSL）；未安装时命令会失败。",
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
                    title = "默认",
                    description = "使用 /api/terminals 端点，方式与 Web 控制台一致，需要开启「Web 登录认证」。",
                    reason = state.localReason,
                    enabled = state.localAvailable,
                    onClick = actions.chooseLocal,
                )
                Spacer(Modifier.height(10.dp))
                SourceRow(
                    title = "Bridge 中继",
                    description = "经配套插件 QwenPaw Node Bridge 转发；适合手机只能访问 QwenPaw Web 端口的场景。",
                    reason = state.sshReason,
                    enabled = state.sshAvailable,
                    onClick = actions.chooseSsh,
                )
                Spacer(Modifier.height(10.dp))
                SourceRow(
                    title = "SSH 直连",
                    description = "从本机直接连接目标 SSH；平台与 shell 在 设置 → 服务器 → 编辑实例 → 高级选项 → SSH 直连 中配置。",
                    reason = state.directReason,
                    enabled = state.directAvailable,
                    onClick = actions.chooseDirect,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.setAsDefault,
                        onCheckedChange = { actions.setAsDefault(it) },
                    )
                    Text("设为默认")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "SSH 凭据只在本机内存中使用。Bridge 中继的公网明文地址需先在" +
                        "「受信任的连接」确认该地址仅你可访问；关闭安全提示不会解除这条限制。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = actions.dismissSource) {
                Text(if (state.tabs.isNotEmpty()) "取消" else "返回")
            }
        },
    )
}

private fun shellLabel(shell: TerminalShell): String = when (shell) {
    TerminalShell.DEFAULT -> "默认"
    TerminalShell.BASH -> "bash"
    TerminalShell.DASH -> "dash"
    TerminalShell.POWERSHELL -> "PowerShell"
    TerminalShell.CMD -> "命令提示符"
    TerminalShell.GIT_BASH -> "Bash"
}

@Composable
private fun SourceRow(
    title: String,
    description: String,
    reason: String?,
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
            TextButton(onClick = it) { Text("关闭") }
        }
    }
}
