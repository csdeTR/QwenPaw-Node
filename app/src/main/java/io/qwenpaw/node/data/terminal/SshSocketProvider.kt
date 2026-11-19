package io.qwenpaw.node.data.terminal

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** SSH 的 TCP 来源：客户端直连，或经配套插件的 WebSocket 中继。 */
interface SshSocketProvider {
    val label: String
    val isRelay: Boolean
    fun open(): Socket
}

class DirectSocketProvider(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 6_000,
) : SshSocketProvider {

    override val label: String = "$host:$port"
    override val isRelay: Boolean = false

    override fun open(): Socket {
        // Socket 自己的 getPort() 会遮蔽构造参数 port；先取出目标值。
        val targetHost = host
        val targetPort = port
        return Socket().apply {
            connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
            tcpNoDelay = true
            keepAlive = true
        }
    }
}

class RelaySocketProvider(
    private val api: QwenPawApi,
    private val conn: Connection,
    private val host: String,
    private val port: Int,
    /** 该地址已在「受信任的连接」中确认；插件在未开 Web 认证时据此放行。 */
    private val trusted: Boolean = false,
) : SshSocketProvider {

    override val label: String = "Bridge $host:$port"
    override val isRelay: Boolean = true

    override fun open(): Socket = WebSocketSocket(
        api.bridgeSshRequest(conn, trusted),
        wsClient,
        host,
        port,
    )

    private companion object {
        /**
         * WebSocket 客户端。
         *
         * 不设读写超时：SSH 空闲期没有字节，靠 30 秒一次的 WebSocket ping 保活；
         * 也不挂网络日志，避免把带 token 的查询参数写进日志。
         */
        val wsClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

/** 一次 SSH 尝试的目标；[address] 的别名用于展示，不进入日志。 */
class SshEndpoint(
    val address: SshAddress,
    val provider: SshSocketProvider,
) {
    val host: String get() = address.host.trim()
    val port: Int get() = address.port.takeIf { it in 1..65535 } ?: 22
    val user: String get() = address.user.trim()
    val label: String get() = address.alias.ifBlank { "$user@$host:$port" }
    val hostKeyId: String get() = "${host.lowercase()}|$port"
}

/** SSH 凭据；只在 SshTransport 连接期持有，不进入 UI state 或日志。 */
sealed class SshAuth {
    class Key(val privateKey: String, val passphrase: String?) : SshAuth()
    class Password(val password: String) : SshAuth()
}
