package io.qwenpaw.node.data.terminal

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 传输层递给界面的事件。顺序即服务端输出顺序。 */
sealed interface TerminalEvent {
    data class Output(val bytes: ByteArray) : TerminalEvent
    data object Reset : TerminalEvent
    data class Exited(val code: Int?) : TerminalEvent
    data class Title(val value: String) : TerminalEvent
}

/** 连接状态。失败只在网络层重试期间出现，收到新输出后会回到 [Connected]。 */
sealed interface TerminalConnection {
    data object Idle : TerminalConnection
    data object Connecting : TerminalConnection
    data object Connected : TerminalConnection
    data class Exited(val code: Int?) : TerminalConnection
    data class Failed(val message: String) : TerminalConnection
}

/**
 * 终端字节的来源。
 *
 * 服务端 PTY 与 SSH 只在「字节从哪来」上不同，渲染与按键完全共用。
 */
interface TerminalTransport {
    val events: SharedFlow<TerminalEvent>
    val state: StateFlow<TerminalConnection>

    /** 开始读输出。调用前界面应先订阅 [events]，否则会丢首屏。 */
    fun start()

    fun write(bytes: ByteArray)

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int)

    /** 停止读输出；不关闭服务端终端。 */
    fun stop()
}
