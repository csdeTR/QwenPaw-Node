package io.qwenpaw.node.data.terminal

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把一个 OkHttp WebSocket 伪装成 [Socket]，交给 JSch 跑标准 SSH。
 *
 * 插件只做二进制帧中继；SSH 的握手、加密与认证都在客户端完成。这里只负责
 * 「字节 → 帧」与「帧 → 字节」，并用有界队列给读方向做背压。
 *
 * 不记录 URL：反代 Basic 与 token 冲突时 token 会出现在查询参数里。
 */
class WebSocketSocket(
    request: Request,
    client: OkHttpClient,
    private val targetHost: String,
    private val targetPort: Int,
) : Socket() {

    private val incoming = LinkedBlockingQueue<ByteArray>(64)
    private val closed = AtomicBoolean(false)
    private val eof = ByteArray(0)

    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    var closeCode: Int? = null
        private set

    /** 插件在关闭帧里写的短原因：unauthorized / not configured / busy / target unreachable。 */
    @Volatile
    var closeReason: String? = null
        private set

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!incoming.offer(bytes.toByteArray())) {
                // 读端跟不上：关闭而不是无限堆积。SSH 自己的窗口也会因此回退。
                closeQuietly()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            closeReason = reason
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            closeReason = reason
            closed.set(true)
            incoming.offer(eof)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t
            closed.set(true)
            incoming.offer(eof)
        }
    }

    private val webSocket: WebSocket = client.newWebSocket(request, listener)

    private val input = object : InputStream() {
        private var current: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                current?.let { buf ->
                    if (offset < buf.size) {
                        val count = minOf(len, buf.size - offset)
                        System.arraycopy(buf, offset, b, off, count)
                        offset += count
                        return count
                    }
                    current = null
                    offset = 0
                }
                if (closed.get() && incoming.isEmpty()) return -1
                val next = incoming.poll(1, TimeUnit.SECONDS) ?: continue
                if (next === eof) return -1
                current = next
            }
        }
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) {
                throw IOException(failure?.message ?: Texts.get(R.string.ssh_error_relay_closed))
            }
            val data = b.copyOfRange(off, off + len).toByteString()
            var attempts = 0
            while (!webSocket.send(data)) {
                failure?.let {
                    throw IOException(
                        it.message ?: Texts.get(R.string.ssh_error_relay_connection_failed),
                        it,
                    )
                }
                if (closed.get()) throw IOException(Texts.get(R.string.ssh_error_relay_closed))
                if (++attempts > 500) {
                    throw IOException(Texts.get(R.string.ssh_error_relay_send_timeout))
                }
                Thread.sleep(10)
            }
        }

        override fun flush() = Unit

        override fun close() {
            closeQuietly()
        }
    }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun isConnected(): Boolean = !closed.get()

    override fun isClosed(): Boolean = closed.get()

    override fun getInetAddress(): InetAddress =
        runCatching {
            InetAddress.getByAddress(targetHost, byteArrayOf(127, 0, 0, 1))
        }.getOrElse { InetAddress.getLoopbackAddress() }

    override fun getPort(): Int = targetPort

    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getLocalPort(): Int = 0

    override fun getRemoteSocketAddress(): InetSocketAddress =
        InetSocketAddress(getInetAddress(), targetPort)

    override fun getLocalSocketAddress(): InetSocketAddress =
        InetSocketAddress(getLocalAddress(), 0)

    override fun setSoTimeout(timeout: Int) = Unit

    override fun setTcpNoDelay(on: Boolean) = Unit

    override fun setKeepAlive(on: Boolean) = Unit

    override fun setSoLinger(on: Boolean, linger: Int) = Unit

    override fun shutdownInput() {
        closed.set(true)
        incoming.offer(eof)
    }

    override fun shutdownOutput() {
        closeQuietly()
    }

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            incoming.offer(eof)
            runCatching { webSocket.close(1000, null) }
        }
    }
}
