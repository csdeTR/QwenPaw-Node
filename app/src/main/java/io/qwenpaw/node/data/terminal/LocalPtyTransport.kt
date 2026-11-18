package io.qwenpaw.node.data.terminal

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 服务端本地 PTY。
 *
 * 服务端的 `output` 是 20 秒字符游标长轮询；`reset:true` 表示 256 KB 缓冲区已经回绕，
 * 要清屏后按返回的 cursor 重新开始。页面离开只停轮询，不删除服务端终端。
 */
@OptIn(FlowPreview::class)
class LocalPtyTransport(
    private val api: QwenPawApi,
    private val conn: Connection,
    private val group: String,
    private val terminalId: String,
    private val scope: CoroutineScope,
    private val initialInput: String? = null,
) : TerminalTransport {

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()

    private val _state = MutableStateFlow<TerminalConnection>(TerminalConnection.Idle)
    override val state = _state.asStateFlow()

    private val input = Channel<ByteArray>(Channel.BUFFERED)
    private val resizeRequests = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 8)

    private var pollJob: Job? = null
    private var writeJob: Job? = null
    private var resizeJob: Job? = null
    private var initialPending: String? = initialInput

    override fun start() {
        if (pollJob?.isActive == true) return
        _state.value = TerminalConnection.Connecting
        writeJob = scope.launch(Dispatchers.IO) {
            for (bytes in input) {
                runCatching { sendChunks(bytes) }
            }
        }
        resizeJob = scope.launch(Dispatchers.IO) {
            resizeRequests.debounce(200).collect { (rows, cols) ->
                runCatching {
                    api.terminalResize(conn, group, terminalId, rows, cols)
                }
            }
        }
        pollJob = scope.launch(Dispatchers.IO) { poll() }
    }

    private suspend fun poll() {
        val coroutine = currentCoroutineContext()
        var cursor = 0
        var backoff = 0L
        var announced = false
        while (coroutine.isActive) {
            try {
                val out = api.terminalOutput(conn, group, terminalId, cursor)
                backoff = 0
                // 第一条输出说明 shell 已经起来；此时再发 shell 切换/初始化命令，
                // 避免在 PATH 尚未就绪时执行 command -v。
                initialPending?.let {
                    initialPending = null
                    write((it + "\r").toByteArray(Charsets.UTF_8))
                }
                if (out.reset) {
                    cursor = out.cursor
                    _events.emit(TerminalEvent.Reset)
                }
                if (out.data.isNotEmpty()) {
                    cursor = out.cursor
                    _events.emit(
                        TerminalEvent.Output(out.data.toByteArray(Charsets.UTF_8)),
                    )
                } else {
                    cursor = out.cursor
                }
                if (!announced) {
                    announced = true
                    _state.value = TerminalConnection.Connected
                }
                if (out.exited) {
                    _state.value = TerminalConnection.Exited(out.exitCode)
                    _events.emit(TerminalEvent.Exited(out.exitCode))
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backoff = when {
                    backoff == 0L -> 2_000
                    backoff < 10_000 -> backoff * 2
                    else -> 10_000
                }
                _state.value = TerminalConnection.Failed(ErrorText.of(e))
                delay(backoff)
            }
        }
    }

    /** 服务端单帧上限 16384 字符；留出余量，并避免在代理对中间切开。 */
    private fun sendChunks(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 16_000, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) {
                end -= 1
            }
            if (end <= start) end = start + 1
            api.terminalInput(conn, group, terminalId, text.substring(start, end))
            start = end
        }
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (input.trySend(bytes).isFailure) {
            scope.launch { runCatching { input.send(bytes) } }
        }
    }

    override fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        resizeRequests.tryEmit(rows to cols)
    }

    override fun stop() {
        pollJob?.cancel()
        writeJob?.cancel()
        resizeJob?.cancel()
        input.close()
        _state.value = TerminalConnection.Idle
    }
}
