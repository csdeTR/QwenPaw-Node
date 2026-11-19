package io.qwenpaw.node.data.terminal

import android.util.Base64
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SSH 终端：客户端跑 JSch，TCP 来源可以是插件 WebSocket 中继，也可以是手机直连。
 *
 * 多个地址按 [SshEndpointOrder] 排序；网络类失败尝试下一个，认证失败、主机密钥变化
 * 或用户拒绝指纹立即停止。凭据只在连接期持有，不进 UI state、不写日志。
 */
class SshTransport(
    endpoints: List<SshEndpoint>,
    auth: SshAuth,
    private val hostKeys: SshHostKeys,
    private val scope: CoroutineScope,
    private val workspacePath: String? = null,
    private val shell: TerminalShell = TerminalShell.DEFAULT,
    private val platform: TerminalPlatform = TerminalPlatform.AUTO,
    private val sameHost: Boolean = true,
    private var cols: Int = 80,
    private var rows: Int = 24,
    // Windows 的 ConPTY 对 0×0 像素尺寸不友好；先用 80×24 的常见像素值，
    // 视图完成布局后会通过 resize 发送真实尺寸。
    private var widthPx: Int = 640,
    private var heightPx: Int = 384,
) : TerminalTransport {

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()

    private val _state = MutableStateFlow<TerminalConnection>(TerminalConnection.Idle)
    override val state = _state.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt = _hostKeyPrompt.asStateFlow()

    private val endpoints = endpoints.filter { it.host.isNotBlank() && it.user.isNotBlank() }
    private var auth: SshAuth? = auth

    private val input = Channel<ByteArray>(Channel.BUFFERED)
    private val promptQueue = AtomicReference<ArrayBlockingQueue<Boolean>?>(null)
    private val writeLock = Any()

    private var job: Job? = null
    private var writeJob: Job? = null

    @Volatile private var activeEndpoint: SshEndpoint? = null
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    // JSch 的 Channel.getOutputStream() 每次调用都返回新的缓冲流；必须取一次后复用，
    // 否则 write 与 flush 落在两个不同实例上，字节永远不会发出去。
    @Volatile private var channelOut: OutputStream? = null
    @Volatile private var socket: Socket? = null

    override fun start() {
        if (job?.isActive == true) return
        _state.value = TerminalConnection.Connecting
        writeJob = scope.launch(Dispatchers.IO) {
            for (bytes in input) {
                val out = channelOut ?: continue
                try {
                    synchronized(writeLock) {
                        out.write(bytes)
                        out.flush()
                    }
                } catch (e: Exception) {
                    // 通道已断开时由读循环负责切换状态；这里忽略单个写入失败。
                }
            }
        }
        job = scope.launch(Dispatchers.IO) { connect() }
    }

    private suspend fun connect() {
        val credential = auth ?: return
        if (endpoints.isEmpty()) {
            _state.value = TerminalConnection.Failed(Texts.get(R.string.ssh_error_no_address))
            return
        }
        val failures = mutableListOf<String>()
        for (endpoint in endpoints) {
            activeEndpoint = endpoint
            _state.value = TerminalConnection.Connecting
            try {
                connectOne(endpoint, credential)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = sshError(e, endpoint, socket)
                teardown()
                if (!retryable(e, endpoint)) {
                    _state.value = TerminalConnection.Failed(reason)
                    return
                }
                failures += reason
            }
        }
        _state.value = TerminalConnection.Failed(
            if (failures.size == 1) {
                failures.first()
            } else {
                Texts.get(
                    R.string.ssh_error_all_addresses_failed,
                    failures.joinToString(Texts.get(R.string.ssh_error_list_separator)),
                )
            },
        )
    }

    private suspend fun connectOne(endpoint: SshEndpoint, credential: SshAuth) {
        val coroutine = currentCoroutineContext()
        val jsch = JSch()
        if (credential is SshAuth.Key) {
            jsch.addIdentity(
                "qwenpaw",
                credential.privateKey.toByteArray(Charsets.UTF_8),
                null,
                credential.passphrase?.toByteArray(Charsets.UTF_8),
            )
        }
        val repository = TofuRepository(endpoint)
        val jschSession = jsch.getSession(endpoint.user, endpoint.host, endpoint.port)
        session = jschSession
        jschSession.hostKeyRepository = repository
        jschSession.userInfo = Ui(repository, credential)
        jschSession.setConfig("StrictHostKeyChecking", "ask")
        jschSession.setServerAliveInterval(30_000)
        when (credential) {
            is SshAuth.Password -> {
                jschSession.setPassword(credential.password)
                jschSession.setConfig(
                    "PreferredAuthentications",
                    "password,keyboard-interactive",
                )
            }
            is SshAuth.Key -> {
                // 密钥认证失败时不要退回到空密码尝试，避免无意义地触发服务端锁定策略。
                jschSession.setConfig("PreferredAuthentications", "publickey")
            }
        }

        val endpointSocket = endpoint.provider.open()
        socket = endpointSocket
        jschSession.setSocketFactory(
            object : SocketFactory {
                override fun createSocket(host: String, port: Int): Socket = endpointSocket
                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            },
        )

        jschSession.connect(30_000)
        val shellChannel = jschSession.openChannel("shell") as ChannelShell
        channel = shellChannel
        shellChannel.setPtyType("xterm-256color", cols, rows, widthPx, heightPx)
        shellChannel.connect(15_000)
        val output = shellChannel.outputStream
        channelOut = output
        runCatching { shellChannel.setPtySize(cols, rows, widthPx, heightPx) }
        sendBootstrap(output)
        _state.value = TerminalConnection.Connected
        _events.emit(TerminalEvent.Title(endpoint.label))

        val reader = shellChannel.inputStream
        val buffer = ByteArray(8192)
        while (coroutine.isActive) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count > 0) {
                _events.emit(TerminalEvent.Output(buffer.copyOf(count)))
            }
        }
        val code = runCatching { shellChannel.exitStatus }.getOrNull()?.takeUnless { it < 0 }
        if (coroutine.isActive) {
            _state.value = TerminalConnection.Exited(code)
            _events.emit(TerminalEvent.Exited(code))
        }
    }

    private fun sendBootstrap(out: OutputStream) {
        val commands = listOfNotNull(
            ShellBootstrap.switchCommand(shell, platform),
            ShellBootstrap.workspaceCommand(workspacePath, shell, platform, sameHost),
        )
        if (commands.isEmpty()) return
        runCatching {
            for (command in commands) {
                out.write((command + "\r").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (input.trySend(bytes).isFailure) {
            scope.launch { runCatching { input.send(bytes) } }
        }
    }

    override fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        this.rows = rows
        this.cols = cols
        this.widthPx = widthPx
        this.heightPx = heightPx
        val ch = channel ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { ch.setPtySize(cols, rows, widthPx, heightPx) }
        }
    }

    override fun stop() {
        job?.cancel()
        writeJob?.cancel()
        input.close()
        promptQueue.get()?.offer(false)
        _hostKeyPrompt.value = null
        auth = null
        teardown()
        _state.value = TerminalConnection.Idle
    }

    /** 用户在主机指纹弹窗里作出选择。 */
    fun respondHostKey(accepted: Boolean) {
        promptQueue.get()?.offer(accepted)
    }

    /** 指纹变化后，用户清除旧记录再重新确认。 */
    fun clearHostKey() {
        val endpoint = activeEndpoint ?: endpoints.firstOrNull() ?: return
        hostKeys.forget(endpoint.host, endpoint.port)
    }

    private fun teardown() {
        val currentSession = session
        val currentChannel = channel
        val currentOut = channelOut
        val currentSocket = socket
        channelOut = null
        socket = null
        channel = null
        session = null
        scope.launch(Dispatchers.IO) {
            runCatching { currentOut?.close() }
            runCatching { currentChannel?.disconnect() }
            runCatching { currentSession?.disconnect() }
            runCatching { currentSocket?.close() }
        }
    }

    private fun retryable(e: Throwable, endpoint: SshEndpoint): Boolean {
        if (endpoint.provider.isRelay) {
            val reason = (socket as? WebSocketSocket)?.closeReason
            if (reason != null) return false
        }
        val message = e.message.orEmpty().lowercase()
        return !(
            message.contains("auth fail") ||
                message.contains("auth cancel") ||
                message.contains("hostkey") ||
                message.contains("host key") ||
                message.contains("algorithm negotiation")
            )
    }

    private inner class TofuRepository(
        private val endpoint: SshEndpoint,
    ) : HostKeyRepository {
        private var pending: HostKeyPrompt? = null

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            val fingerprint = fingerprint(key)
            val saved = hostKeys.fingerprint(endpoint.host, endpoint.port)
            return when {
                saved == null -> {
                    pending = HostKeyPrompt(
                        host = endpoint.host,
                        port = endpoint.port,
                        fingerprint = fingerprint,
                        keyType = keyType(key),
                    )
                    HostKeyRepository.NOT_INCLUDED
                }
                saved == fingerprint -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "qwenpaw-node"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

        fun pending(): HostKeyPrompt? = pending
    }

    private inner class Ui(
        private val repository: TofuRepository,
        private val credential: SshAuth,
    ) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String =
            (credential as? SshAuth.Key)?.passphrase.orEmpty()

        override fun getPassword(): String =
            (credential as? SshAuth.Password)?.password.orEmpty()

        override fun promptPassword(message: String?): Boolean =
            credential is SshAuth.Password

        override fun promptPassphrase(message: String?): Boolean =
            credential is SshAuth.Key

        override fun promptYesNo(message: String?): Boolean {
            val pending = repository.pending() ?: return false
            val queue = ArrayBlockingQueue<Boolean>(1)
            promptQueue.set(queue)
            _hostKeyPrompt.value = pending
            val accepted = try {
                queue.poll(5, TimeUnit.MINUTES) ?: false
            } finally {
                _hostKeyPrompt.value = null
                promptQueue.compareAndSet(queue, null)
            }
            if (accepted) {
                hostKeys.remember(pending.host, pending.port, pending.fingerprint)
            }
            return accepted
        }

        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            val password = (credential as? SshAuth.Password)?.password ?: return null
            return Array(prompt?.size ?: 1) { password }
        }
    }

    private fun sshError(e: Throwable, endpoint: SshEndpoint, socket: Socket?): String {
        when ((socket as? WebSocketSocket)?.closeReason) {
            "unauthorized" -> return Texts.get(R.string.ssh_error_relay_unauthorized)
            "not configured" -> return Texts.get(R.string.ssh_error_relay_not_configured)
            "busy" -> return Texts.get(R.string.ssh_error_relay_busy)
            "target unreachable" -> return Texts.get(R.string.ssh_error_target_unreachable)
        }
        val message = e.message.orEmpty()
        val viaBridge = endpoint.provider.isRelay
        return when {
            message.contains("Auth fail", true) ||
                message.contains("auth cancel", true) ||
                message.contains("Auth cancel", true) ->
                if (viaBridge) {
                    Texts.get(R.string.ssh_error_auth_failed)
                } else {
                    Texts.get(R.string.ssh_error_auth_failed_local)
                }
            message.contains("HostKey has been changed", true) ||
                message.contains("HostKey has been rejected", true) ->
                Texts.get(R.string.ssh_error_host_key_changed)
            message.contains("Algorithm negotiation fail", true) ->
                Texts.get(R.string.ssh_error_algorithm)
            message.contains("ed25519", true) ||
                message.contains("EdDSA", true) ||
                message.contains("no such algorithm", true) ->
                Texts.get(R.string.ssh_error_ed25519)
            message.contains("WebSocket", true) ||
                message.contains("Expected HTTP 101", true) ->
                Texts.get(R.string.ssh_error_relay_failed)
            else ->
                Texts.get(
                    R.string.ssh_error_connect,
                    endpoint.label,
                    message.ifBlank { e.javaClass.simpleName },
                )
        }
    }

    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
    }

    private fun keyType(key: ByteArray): String {
        if (key.size < 4) return "unknown"
        val length = ((key[0].toInt() and 0xFF) shl 24) or
            ((key[1].toInt() and 0xFF) shl 16) or
            ((key[2].toInt() and 0xFF) shl 8) or
            (key[3].toInt() and 0xFF)
        if (length <= 0 || length > key.size - 4) return "unknown"
        return String(key, 4, length, Charsets.US_ASCII)
    }
}
