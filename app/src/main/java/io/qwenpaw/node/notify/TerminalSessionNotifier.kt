package io.qwenpaw.node.notify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 终端会话与前台服务之间的桥。
 *
 * 会话数量由 [io.qwenpaw.node.ui.terminal.TerminalViewModel] 上报；通知栏的
 * 「停止终端」与「划掉任务」都通过 [requestStopAll] 回调到同一个入口，
 * 保证界面状态、SSH 连接与服务端 PTY 一起收尾。
 */
object TerminalSessionNotifier {

    // replay=1：冷启动时通知先于界面订阅到达也能收到；消费后清空，避免旋转重放。
    private val _openRequests = MutableSharedFlow<Unit>(replay = 1)

    /** 点击通知的打开请求；界面消费后调用 [consumeOpen]。 */
    val openRequests: SharedFlow<Unit> = _openRequests.asSharedFlow()

    @Volatile
    var stopAll: (() -> Unit)? = null

    fun update(context: Context, count: Int) {
        if (count <= 0) {
            runCatching {
                context.stopService(Intent(context, TerminalSessionService::class.java))
            }
        } else {
            ContextCompat.startForegroundService(
                context,
                TerminalSessionService.intent(context, count),
            )
        }
    }

    fun requestOpen() {
        _openRequests.tryEmit(Unit)
    }

    fun consumeOpen() {
        _openRequests.resetReplayCache()
    }

    fun requestStopAll() {
        stopAll?.invoke()
    }
}
