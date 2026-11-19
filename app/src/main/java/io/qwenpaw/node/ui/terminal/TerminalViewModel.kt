package io.qwenpaw.node.ui.terminal

import io.qwenpaw.node.R
import io.qwenpaw.node.data.assessSshCredentialGate
import io.qwenpaw.node.data.model.SshConfigDto
import io.qwenpaw.node.data.model.TerminalDto
import io.qwenpaw.node.data.plugin.BridgeStatus
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.terminal.DirectSocketProvider
import io.qwenpaw.node.data.terminal.HostKeyPrompt
import io.qwenpaw.node.data.terminal.LocalPtyTransport
import io.qwenpaw.node.data.terminal.RelaySocketProvider
import io.qwenpaw.node.data.terminal.ShellBootstrap
import io.qwenpaw.node.data.terminal.SshAddress
import io.qwenpaw.node.data.terminal.SshAuth
import io.qwenpaw.node.data.terminal.SshAuthType
import io.qwenpaw.node.data.terminal.SshEndpoint
import io.qwenpaw.node.data.terminal.SshEndpointOrder
import io.qwenpaw.node.data.terminal.SshHostKeys
import io.qwenpaw.node.data.terminal.SshProfile
import io.qwenpaw.node.data.terminal.SshProfileStore
import io.qwenpaw.node.data.terminal.SshTransport
import io.qwenpaw.node.data.terminal.TerminalCreation
import io.qwenpaw.node.data.terminal.TerminalPlatform
import io.qwenpaw.node.data.terminal.TerminalShell
import io.qwenpaw.node.data.terminal.TerminalSource
import io.qwenpaw.node.data.terminal.TerminalStore
import io.qwenpaw.node.data.terminal.TerminalTransport
import io.qwenpaw.node.data.terminal.TerminalConnection
import io.qwenpaw.node.data.terminal.TerminalEvent
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import io.qwenpaw.node.ui.AppState
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class TerminalTab(
    val id: String,
    val source: TerminalSource,
    val title: String,
    val shell: TerminalShell = TerminalShell.DEFAULT,
    val profileId: String? = null,
    val terminalId: String? = null,
    /** 服务端 PTY 所属分组；智能体切换后仍按原分组收尾。 */
    val group: String? = null,
    /** 创建该标签时的连接；实例切换后收尾仍请求原服务器。 */
    val conn: Connection? = null,
    val transport: TerminalTransport? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val emulator: TerminalEmulator? = null,
    val connection: TerminalConnection = TerminalConnection.Idle,
    val jobs: List<Job> = emptyList(),
)

data class TerminalUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val sourceDialog: Boolean = false,
    val tabs: List<TerminalTab> = emptyList(),
    val activeTabId: String? = null,
    /** null 表示可用；非空是置灰原因。 */
    val localReason: String? = null,
    val sshReason: String? = null,
    val directReason: String? = null,
    /** 直连未配置时，在红字后追加的平台/shell 设置位置。 */
    val directHint: String? = null,
    val shellOptions: List<TerminalShell> = listOf(TerminalShell.DEFAULT),
    val selectedShell: TerminalShell = TerminalShell.DEFAULT,
    val setAsDefault: Boolean = false,
    val fontSizeSp: Int = TerminalStore.DEFAULT_FONT_SIZE,
    val error: String? = null,
    val notice: String? = null,
) {
    val activeTab: TerminalTab? get() = tabs.firstOrNull { it.id == activeTabId }
    val source: TerminalSource? get() = activeTab?.source
    val transport: TerminalTransport? get() = activeTab?.transport
    val title: String get() = activeTab?.title.orEmpty()
    val hostKeyPrompt: HostKeyPrompt? get() = activeTab?.hostKeyPrompt
    val localAvailable: Boolean get() = localReason == null
    val sshAvailable: Boolean get() = sshReason == null
    val directAvailable: Boolean get() = directReason == null
    val canAdd: Boolean get() = tabs.size < MAX_TABS
}

private const val MAX_TABS = 8
private val CLEAR_SCREEN = "\u001b[H\u001b[2J\u001b[3J".toByteArray(Charsets.UTF_8)

/**
 * 终端页状态：默认 PTY、Bridge 中继与 SSH 直连共用一个 tab 容器。
 *
 * 安全闸门只在这里和真正请求插件凭据前各算一次；直连凭据在本机，不经过插件闸门，
 * 但仍强制主机密钥 TOFU。界面不自行拼判定。
 */
class TerminalViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val appState: () -> AppState,
    private val instanceId: () -> String?,
    private val store: TerminalStore,
    private val hostKeys: SshHostKeys,
    private val profileStore: SshProfileStore,
    private val vpnActive: () -> Boolean = { false },
    private val scope: CoroutineScope,
    /** 会话数量变化；用于前台服务与常驻通知。 */
    private val onSessionCountChanged: (Int) -> Unit = {},
) {
    private val _state = MutableStateFlow(TerminalUiState())
    val state = _state.asStateFlow()

    private val sshTransports = mutableMapOf<String, SshTransport>()
    private val hostKeyJobs = mutableMapOf<String, Job>()
    private var sshConfig: SshConfigDto? = null
    private var loginRequired = false
    private var workspacePath: String? = null
    private var instancePlatform = TerminalPlatform.POSIX
    private var pinchTipShown = false

    fun open() {
        if (_state.value.busy || _state.value.tabs.isNotEmpty()) return
        val conn = connection()
        val inst = instanceId()
        if (conn == null || inst == null) {
            _state.update {
                it.copy(loading = false, error = Texts.get(R.string.terminal_error_not_connected))
            }
            return
        }
        scope.launch {
            _state.update { it.copy(loading = true, busy = true, error = null, notice = null) }
            val auth = withContext(Dispatchers.IO) {
                runCatching { api.authStatus(conn.baseUrl, conn.basicAuth) }.getOrNull()
            }
            val local = withContext(Dispatchers.IO) {
                runCatching { api.terminalStatus(conn) }.getOrNull()
            }
            loginRequired = auth?.let { it.enabled && it.hasUsers } ?: false
            val bridge = appState().bridgeStatus
            val sshCap = bridge is BridgeStatus.Ready && bridge.info.supports("ssh")
            val gate = assessSshCredentialGate(
                conn.baseUrl,
                loginRequired,
                appState().sshTrustedHosts,
            )
            sshConfig = null
            var sshError: String? = null
            // Bridge 中继不依赖本地 PTY 的鉴权状态；只要插件具备 ssh 能力、
            // 凭据闸门放行，就可以读取脱敏的 SSH 配置。
            if (sshCap && gate.allowed) {
                sshConfig = withContext(Dispatchers.IO) {
                    runCatching { api.bridgeSshConfig(conn) }.getOrElse { e ->
                        sshError = when ((e as? ApiException)?.code) {
                            404 -> Texts.get(R.string.terminal_error_ssh_not_configured)
                            403 -> Texts.get(R.string.terminal_error_ssh_needs_login)
                            else -> ErrorText.of(e)
                        }
                        null
                    }
                }
            }
            val project = withContext(Dispatchers.IO) {
                runCatching { api.projectDirectory(conn) }.getOrNull()
            }
            workspacePath = project?.path?.takeIf { it.isNotBlank() }
                ?: project?.workspaceDir?.takeIf { it.isNotBlank() }
            instancePlatform = ShellBootstrap.resolve(TerminalPlatform.AUTO, workspacePath)
            val localReason = when {
                auth == null -> Texts.get(R.string.terminal_error_auth_unreadable)
                !auth.enabled -> Texts.get(R.string.terminal_error_auth_disabled)
                !auth.hasUsers -> Texts.get(R.string.terminal_error_no_users)
                local == null -> Texts.get(R.string.terminal_error_terminal_status)
                !local.enabled -> local.reason?.let {
                    Texts.get(R.string.terminal_error_missing_dependency, it)
                } ?: Texts.get(R.string.terminal_error_terminal_unavailable)
                else -> null
            }
            val sshReason = when {
                bridge !is BridgeStatus.Ready -> Texts.get(R.string.terminal_reason_bridge_missing)
                !sshCap -> Texts.get(R.string.terminal_reason_bridge_old)
                !gate.allowed -> gate.reason
                sshError != null -> sshError
                sshConfig == null -> Texts.get(R.string.terminal_reason_ssh_not_configured)
                else -> null
            }
            val directReason = directReason(inst)
            val directHint = directHintFor(
                directReason,
                Texts.get(R.string.terminal_reason_direct_not_configured),
                Texts.get(R.string.terminal_reason_direct_platform_hint),
            )
            // 只有显式「设为默认」才自动创建；上次来源不再参与，否则用户
            // 没勾默认也永远不会看到来源选择窗口。
            val creation = store.defaultCreation(inst, conn.agentId)
                ?.takeIf { isAvailable(it.source, localReason, sshReason, directReason) }
            val shellOptions = ShellBootstrap.options(instancePlatform)
            val fontSize = store.fontSize(inst)
            _state.update {
                it.copy(
                    loading = false,
                    busy = false,
                    localReason = localReason,
                    sshReason = sshReason,
                    directReason = directReason,
                    directHint = directHint,
                    shellOptions = shellOptions,
                    selectedShell = creation?.shell ?: TerminalShell.DEFAULT,
                    fontSizeSp = fontSize,
                    sourceDialog = creation == null,
                    error = null,
                    notice = null,
                )
            }
            if (creation != null) createTab(creation, fromDialog = false)
        }
    }

    fun switchSource() {
        val inst = instanceId()
        _state.update {
            val reason = inst?.let { id -> directReason(id) } ?: it.directReason
            it.copy(
                directReason = reason,
                directHint = directHintFor(
                    reason,
                    Texts.get(R.string.terminal_reason_direct_not_configured),
                    Texts.get(R.string.terminal_reason_direct_platform_hint),
                ),
                shellOptions = ShellBootstrap.options(instancePlatform),
                sourceDialog = true,
                setAsDefault = false,
            )
        }
    }

    fun dismissSourceDialog() {
        _state.update { it.copy(sourceDialog = false) }
    }

    fun selectShell(shell: TerminalShell) {
        _state.update { it.copy(selectedShell = shell) }
    }

    fun setAsDefaultChecked(checked: Boolean) {
        _state.update { it.copy(setAsDefault = checked) }
    }

    fun chooseLocal() = createFromDialog(TerminalSource.LOCAL)

    fun chooseSsh() = createFromDialog(TerminalSource.SSH_RELAY)

    fun chooseDirect() = createFromDialog(TerminalSource.SSH_DIRECT)

    private fun createFromDialog(source: TerminalSource) {
        val state = _state.value
        val profile = if (source == TerminalSource.SSH_DIRECT) {
            instanceId()?.let { profileStore.load(it) }
        } else {
            null
        }
        val shell = shellFor(state.selectedShell, source, profile)
        createTab(
            TerminalCreation(source = source, shell = shell),
            fromDialog = true,
            setDefault = state.setAsDefault,
        )
    }

    fun newTab() {
        if (!_state.value.canAdd) {
            _state.update {
                it.copy(
                    notice = Texts.quantity(R.plurals.terminal_limit_tabs, MAX_TABS, MAX_TABS),
                )
            }
            return
        }
        val inst = instanceId()
        val conn = connection()
        val default = if (inst != null && conn != null) {
            store.defaultCreation(inst, conn.agentId)
                ?.takeIf { isAvailable(it.source, _state.value.localReason, _state.value.sshReason, _state.value.directReason) }
        } else {
            null
        }
        if (default != null) {
            createTab(default, fromDialog = false, forceNewLocal = true)
        } else {
            switchSource()
        }
    }

    fun selectTab(id: String) {
        if (_state.value.tabs.none { it.id == id }) return
        _state.update { it.copy(activeTabId = id) }
    }

    fun closeActive() {
        val id = _state.value.activeTabId ?: return
        closeTab(id)
    }

    val hasSessions: Boolean get() = _state.value.tabs.isNotEmpty()

    private fun closeTab(id: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        hostKeyJobs.remove(id)?.cancel()
        tab.jobs.forEach { it.cancel() }
        (sshTransports.remove(id) ?: tab.transport)?.stop()
        runCatching { tab.emulator?.close() }
        closeServerTerminal(tab)
        _state.update { current ->
            val remaining = current.tabs.filterNot { it.id == id }
            val next = when {
                current.activeTabId != id -> current.activeTabId
                else -> remaining.lastOrNull()?.id
            }
            current.copy(
                tabs = remaining,
                activeTabId = next,
                sourceDialog = remaining.isEmpty(),
                notice = if (remaining.isEmpty()) {
                    Texts.get(R.string.terminal_notice_no_tabs)
                } else {
                    current.notice
                },
            )
        }
        onSessionCountChanged(_state.value.tabs.size)
    }

    fun reconnect() {
        val tab = _state.value.activeTab
        if (tab == null) {
            switchSource()
            return
        }
        val creation = TerminalCreation(tab.source, tab.shell, tab.profileId)
        closeTab(tab.id)
        createTab(creation, fromDialog = true)
    }

    /**
     * 页面离开只退订输入焦点；会话本身继续运行，通知栏保持常驻。
     *
     * 真正结束会话的是 [shutdown]（停止按钮、划掉任务、切换服务器实例）。
     */
    fun onLeave() = Unit

    /** 结束全部会话并关闭服务端 PTY；供前台服务与实例切换调用。 */
    fun shutdown() {
        val tabs = _state.value.tabs
        if (tabs.isEmpty() && sshTransports.isEmpty()) {
            onSessionCountChanged(0)
            return
        }
        tabs.forEach { tab ->
            tab.jobs.forEach { job -> job.cancel() }
            (sshTransports.remove(tab.id) ?: tab.transport)?.stop()
            runCatching { tab.emulator?.close() }
            closeServerTerminal(tab)
        }
        sshTransports.clear()
        hostKeyJobs.values.forEach { it.cancel() }
        hostKeyJobs.clear()
        _state.update {
            it.copy(
                tabs = emptyList(),
                activeTabId = null,
                loading = true,
                busy = false,
                sourceDialog = false,
                error = null,
            )
        }
        onSessionCountChanged(0)
    }

    private fun closeServerTerminal(tab: TerminalTab) {
        if (tab.source != TerminalSource.LOCAL || tab.terminalId == null) return
        val conn = tab.conn ?: return
        val group = tab.group ?: run {
            val inst = instanceId() ?: return
            store.group(inst, conn.agentId)
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { api.closeTerminal(conn, group, tab.terminalId) }
            }
        }
    }

    fun respondHostKey(accepted: Boolean) {
        val id = _state.value.activeTabId ?: return
        sshTransports[id]?.respondHostKey(accepted)
    }

    fun clearHostKey() {
        val id = _state.value.activeTabId ?: return
        sshTransports[id]?.clearHostKey()
        _state.update {
            it.copy(notice = Texts.get(R.string.terminal_notice_host_key_cleared))
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun setFontSize(sizeSp: Int) {
        val inst = instanceId() ?: return
        val clamped = sizeSp.coerceIn(TerminalStore.MIN_FONT_SIZE, TerminalStore.MAX_FONT_SIZE)
        store.setFontSize(inst, clamped)
        _state.update { it.copy(fontSizeSp = clamped) }
    }

    /** 首次双指缩放提示；进程内只返回一次 true，重启后重置。 */
    fun claimPinchTip(): Boolean {
        if (pinchTipShown) return false
        pinchTipShown = true
        return true
    }

    /** 重命名标签；本地终端 best-effort 同步服务端标题，SSH 标签仅当前页有效。 */
    fun renameTab(id: String, title: String) {
        val clean = title.trim()
        if (clean.isBlank()) return
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        updateTab(id) { it.copy(title = clean) }
        if (tab.source != TerminalSource.LOCAL || tab.terminalId == null) return
        val conn = tab.conn ?: return
        val group = tab.group ?: run {
            val inst = instanceId() ?: return
            store.group(inst, conn.agentId)
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { api.renameTerminal(conn, group, tab.terminalId, clean) }
            }
        }
    }

    private fun createTab(
        creation: TerminalCreation,
        fromDialog: Boolean,
        setDefault: Boolean = false,
        forceNewLocal: Boolean = false,
    ) {
        if (!_state.value.canAdd) {
            _state.update {
                it.copy(
                    busy = false,
                    error = Texts.quantity(R.plurals.terminal_limit_tabs, MAX_TABS, MAX_TABS),
                    sourceDialog = fromDialog,
                )
            }
            return
        }
        if (!isAvailable(
                creation.source,
                _state.value.localReason,
                _state.value.sshReason,
                _state.value.directReason,
            )
        ) {
            _state.update { it.copy(sourceDialog = true) }
            return
        }
        val conn = connection()
        val inst = instanceId()
        if (conn == null || inst == null) {
            _state.update {
                it.copy(loading = false, error = Texts.get(R.string.terminal_error_not_connected))
            }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, sourceDialog = false, error = null, notice = null) }
            try {
                when (creation.source) {
                    TerminalSource.LOCAL ->
                        addLocalTab(conn, inst, creation, fromDialog || forceNewLocal)
                    TerminalSource.SSH_RELAY -> addRelayTab(conn, inst, creation)
                    TerminalSource.SSH_DIRECT -> addDirectTab(conn, inst, creation)
                }
                if (setDefault) store.setDefaultCreation(inst, conn.agentId, creation)
                store.setSource(inst, conn.agentId, creation.source)
                _state.update { it.copy(setAsDefault = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = ErrorText.of(e),
                        sourceDialog = true,
                    )
                }
            }
        }
    }

    private suspend fun addLocalTab(
        conn: Connection,
        inst: String,
        creation: TerminalCreation,
        forceNew: Boolean,
    ) {
        val group = store.group(inst, conn.agentId)
        var list = withContext(Dispatchers.IO) { api.terminals(conn, group) }
        val target: TerminalDto
        if (forceNew || list.isEmpty()) {
            target = withContext(Dispatchers.IO) { api.createTerminal(conn, group) }
            list = list + target
        } else {
            val last = store.lastTerminal(group)
            target = list.firstOrNull { it.id == last } ?: list.first()
        }
        if (!forceNew && _state.value.tabs.any { it.terminalId == target.id }) {
            _state.update {
                it.copy(activeTabId = it.tabs.first { t -> t.terminalId == target.id }.id, busy = false)
            }
            return
        }
        val shell = shellFor(creation.shell, TerminalSource.LOCAL, null)
        val transport = LocalPtyTransport(
            api = api,
            conn = conn,
            group = group,
            terminalId = target.id,
            scope = scope,
            initialInput = ShellBootstrap.switchCommand(shell, instancePlatform),
        )
        val defaultTitle = Texts.get(R.string.terminal_source_local)
        val title = if (shell == TerminalShell.DEFAULT) {
            target.title.ifBlank { defaultTitle }
        } else {
            "${target.title.ifBlank { defaultTitle }} · ${ShellBootstrap.label(shell)}"
        }
        addTab(
            TerminalTab(
                id = UUID.randomUUID().toString(),
                source = TerminalSource.LOCAL,
                title = title,
                shell = shell,
                terminalId = target.id,
                group = group,
                conn = conn,
                transport = transport,
            ),
        )
        store.setLastTerminal(group, target.id)
    }

    private suspend fun addRelayTab(
        conn: Connection,
        inst: String,
        creation: TerminalCreation,
    ) {
        val config = sshConfig ?: error(Texts.get(R.string.terminal_reason_ssh_not_configured))
        val gate = assessSshCredentialGate(
            conn.baseUrl,
            loginRequired,
            appState().sshTrustedHosts,
        )
        if (!gate.allowed) {
            _state.update { it.copy(sshReason = gate.reason, sourceDialog = true) }
            return
        }
        val credential = withContext(Dispatchers.IO) {
            api.bridgeSshCredential(conn, gate.trusted)
        }
        val auth = when (config.auth) {
            "password" -> credential.password
                ?.takeIf { it.isNotBlank() }
                ?.let { SshAuth.Password(it) }
            else -> credential.privateKey
                ?.takeIf { it.isNotBlank() }
                ?.let { SshAuth.Key(it, credential.passphrase) }
        } ?: error(Texts.get(R.string.terminal_error_no_ssh_credential))
        val endpoint = SshEndpoint(
            address = SshAddress(
                id = "bridge",
                alias = Texts.get(R.string.terminal_source_relay),
                host = config.host,
                port = config.port,
                user = config.user,
                default = true,
            ),
            provider = RelaySocketProvider(api, conn, config.host, config.port, gate.trusted),
        )
        val shell = shellFor(creation.shell, TerminalSource.SSH_RELAY, null)
        val transport = SshTransport(
            endpoints = listOf(endpoint),
            auth = auth,
            hostKeys = hostKeys,
            scope = scope,
            workspacePath = workspacePath,
            shell = shell,
            platform = instancePlatform,
            sameHost = true,
        )
        addSshTab(
            TerminalTab(
                id = UUID.randomUUID().toString(),
                source = TerminalSource.SSH_RELAY,
                title = Texts.get(R.string.terminal_source_relay),
                shell = shell,
                transport = transport,
            ),
            transport,
        )
    }

    private suspend fun addDirectTab(
        conn: Connection,
        inst: String,
        creation: TerminalCreation,
    ) {
        val profile = profileStore.load(inst)
            ?: error(Texts.get(R.string.terminal_reason_direct_not_configured))
        val secret = profileStore.secret(inst)
        val auth = when (profile.auth) {
            SshAuthType.KEY -> secret.privateKey()
                ?.takeIf { it.isNotBlank() }
                ?.let { SshAuth.Key(it, secret.passphrase()) }
            SshAuthType.PASSWORD -> secret.password()
                ?.takeIf { it.isNotBlank() }
                ?.let { SshAuth.Password(it) }
        } ?: error(Texts.get(R.string.terminal_error_direct_no_credential))
        val endpoints = SshEndpointOrder.order(profile.addresses, vpnActive()).map {
            SshEndpoint(
                address = it,
                provider = DirectSocketProvider(it.host.trim(), it.port),
            )
        }
        if (endpoints.isEmpty()) error(Texts.get(R.string.terminal_error_direct_no_address))
        val platform = ShellBootstrap.resolve(profile.platform, workspacePath)
        val selected = shellFor(creation.shell, TerminalSource.SSH_DIRECT, profile)
        val shell = if (selected == TerminalShell.DEFAULT &&
            profile.shell in ShellBootstrap.options(platform)
        ) {
            profile.shell
        } else {
            selected
        }
        val transport = SshTransport(
            endpoints = endpoints,
            auth = auth,
            hostKeys = hostKeys,
            scope = scope,
            workspacePath = workspacePath,
            shell = shell,
            platform = platform,
            sameHost = profile.sameHost,
        )
        addSshTab(
            TerminalTab(
                id = UUID.randomUUID().toString(),
                source = TerminalSource.SSH_DIRECT,
                title = Texts.get(R.string.terminal_source_direct),
                shell = shell,
                transport = transport,
            ),
            transport,
        )
    }

    private fun addSshTab(tab: TerminalTab, transport: SshTransport) {
        sshTransports[tab.id] = transport
        hostKeyJobs[tab.id] = scope.launch {
            transport.hostKeyPrompt.collect { prompt ->
                updateTab(tab.id) { it.copy(hostKeyPrompt = prompt) }
            }
        }
        addTab(tab)
    }

    private fun addTab(tab: TerminalTab) {
        val transport = tab.transport ?: return
        val emulator = TerminalEmulatorFactory.create(
            onKeyboardInput = { bytes -> transport.write(bytes) },
            onResize = { dims ->
                transport.resize(dims.rows, dims.columns, dims.widthPixels, dims.heightPixels)
            },
            onClipboardCopy = null,
        )
        val outputJob = scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TerminalEvent.Output -> emulator.writeInput(event.bytes)
                    TerminalEvent.Reset -> emulator.writeInput(CLEAR_SCREEN)
                    is TerminalEvent.Title -> updateTab(tab.id) { it.copy(title = event.value) }
                    is TerminalEvent.Exited -> Unit
                }
            }
        }
        val stateJob = scope.launch {
            transport.state.collect { connection ->
                updateTab(tab.id) { it.copy(connection = connection) }
            }
        }
        val complete = tab.copy(
            emulator = emulator,
            jobs = listOf(outputJob, stateJob),
        )
        _state.update {
            it.copy(
                tabs = it.tabs + complete,
                activeTabId = complete.id,
                busy = false,
                sourceDialog = false,
                error = null,
                loading = false,
            )
        }
        onSessionCountChanged(_state.value.tabs.size)
        transport.start()
    }

    private fun updateTab(id: String, transform: (TerminalTab) -> TerminalTab) {
        _state.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun shellFor(
        shell: TerminalShell,
        source: TerminalSource,
        profile: SshProfile?,
    ): TerminalShell {
        val platform = when (source) {
            TerminalSource.LOCAL, TerminalSource.SSH_RELAY -> instancePlatform
            TerminalSource.SSH_DIRECT ->
                ShellBootstrap.resolve(profile?.platform ?: TerminalPlatform.AUTO, workspacePath)
        }
        return if (shell in ShellBootstrap.options(platform)) shell else TerminalShell.DEFAULT
    }

    private fun isAvailable(
        source: TerminalSource,
        localReason: String?,
        sshReason: String?,
        directReason: String?,
    ): Boolean = when (source) {
        TerminalSource.LOCAL -> localReason == null
        TerminalSource.SSH_RELAY -> sshReason == null
        TerminalSource.SSH_DIRECT -> directReason == null
    }

    private fun directReason(instanceId: String): String? {
        // 平台/shell 的提示只在「尚未配置」时出现；其他原因（无凭据、安全存储不可用）
        // 指向的是另一处，不叠加这条提示。
        if (!profileStore.available) {
            return Texts.get(R.string.terminal_reason_secure_storage)
        }
        val profile = profileStore.load(instanceId)
            ?: return Texts.get(R.string.terminal_reason_direct_not_configured)
        if (profile.addresses.none { it.host.isNotBlank() && it.user.isNotBlank() }) {
            return Texts.get(R.string.terminal_reason_direct_not_configured)
        }
        if (!profileStore.hasCredentials(instanceId, profile.auth)) {
            return when (profile.auth) {
                SshAuthType.KEY -> Texts.get(R.string.terminal_reason_direct_no_key)
                SshAuthType.PASSWORD -> Texts.get(R.string.terminal_reason_direct_no_password)
            }
        }
        return null
    }
}

/** 直连未配置时才提示平台/shell 去哪里设置。 */
internal fun directHintFor(
    reason: String?,
    notConfigured: String,
    platformHint: String,
): String? = if (reason == notConfigured) platformHint else null
