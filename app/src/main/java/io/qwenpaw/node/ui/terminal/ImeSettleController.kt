package io.qwenpaw.node.ui.terminal

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一次 IME 过渡稳定后的结果。 */
data class ImeSettleState(
    /** 已稳定的 IME 底部高度（px）。键盘动画期间保持旧值。 */
    val bottomPx: Int,
    /** 过渡尚未结束时为 true，调用方应冻结终端行列重排。 */
    val suspended: Boolean,
)

/**
 * 把 IME 底部高度从「逐帧动画值」收敛成「每次过渡只提交一次」。
 *
 * 直接在组合里读 `WindowInsets.ime` 会让终端每帧重组并重排；这里改为
 * 在 snapshotFlow 里观察，稳定 120ms 后写一次状态。500ms 兜底避免
 * 某些输入法持续抖动时终端永远冻结。
 */
@Composable
fun rememberImeSettleState(
    debounceMs: Long = 120,
    watchdogMs: Long = 500,
): ImeSettleState {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var bottom by remember { mutableIntStateOf(0) }
    var suspended by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(density, imeInsets) {
        var pendingBottom = 0
        var debounceJob: Job? = null
        var watchdogJob: Job? = null
        snapshotFlow { imeInsets.getBottom(density) }
            .collect { raw ->
                if (raw == bottom) return@collect
                pendingBottom = raw
                if (!suspended) {
                    suspended = true
                    watchdogJob = scope.launch {
                        delay(watchdogMs)
                        bottom = pendingBottom
                        suspended = false
                        debounceJob?.cancel()
                    }
                }
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debounceMs)
                    bottom = pendingBottom
                    suspended = false
                    watchdogJob?.cancel()
                }
            }
    }

    return ImeSettleState(bottomPx = bottom, suspended = suspended)
}
