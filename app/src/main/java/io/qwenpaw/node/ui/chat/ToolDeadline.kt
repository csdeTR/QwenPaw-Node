package io.qwenpaw.node.ui.chat

import kotlin.math.ceil

/**
 * 工具调用的倒计时。
 *
 * 服务端 `offload_remaining` / `kill_remaining` 是取回那一刻的快照，
 * 不会随请求刷新；界面必须自己按「已过时间」递减，否则数字会一直停着。
 */
internal fun remainingSeconds(snapshot: Double?, elapsedSinceFetchMs: Long): Double? {
    if (snapshot == null) return null
    return (snapshot - elapsedSinceFetchMs / 1000.0).coerceAtLeast(0.0)
}

/** 显示用的整秒。向上取整：取回后 0.4 秒仍应显示 1 而不是 0。 */
internal fun remainingLabelSeconds(remaining: Double?): Int? =
    remaining?.let { ceil(it).toInt() }

/** 两个倒计时里取较短的一个，两个都为 null 时返回 null。 */
internal fun shorterRemaining(first: Double?, second: Double?): Double? =
    when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

/**
 * 工具卡片自动展开的等待时间。
 *
 * 与官方网页一致：命令超过这个时长仍未结束，就把卡片展开，让用户看到
 * 输出与操作项，而不是继续盯着一个「运行中」。
 */
internal const val AUTO_EXPAND_MS = 7500L

/** 已运行时长是否到了自动展开的阈值。 */
internal fun autoExpandDue(elapsedMs: Long): Boolean = elapsedMs >= AUTO_EXPAND_MS

/**
 * 当前实例的后台策略是否为「自动转入后台」。
 *
 * 卡片上的倒计时文案依赖它：服务端默认是「保持前台」，那种情况下到期不会自动转后台，
 * 写成「N 秒后自动转入后台」会让人以为工具会被服务端接手。
 * 由对话页在组合作用域里提供，避免把策略逐层传进每个工具卡片。
 */
internal val LocalAutoOffload = androidx.compose.runtime.compositionLocalOf { false }

/**
 * 操作区下方那一行倒计时文案。
 *
 * 两个时限只取较短的一个作主句，另一个放同一行的括号里；到 0 时不再报秒数，
 * 直接写「即将…」——「0 秒后」读起来像是已经发生了，但服务端还没执行。
 *
 * **[autoOffload] 为 false 时只显示执行超时，后台时限整段不出现。**
 * 策略不是「自动转入后台」时，后台时限到期后工具仍留在前台，
 * 提前报「N 秒后到达后台时限」只会让人以为它马上要被转走。
 */
internal fun toolDeadlineLabel(
    offloadRemaining: Double?,
    killRemaining: Double?,
    elapsedSinceFetchMs: Long,
    autoOffload: Boolean = false,
): String? {
    val offload = if (autoOffload) remainingSeconds(offloadRemaining, elapsedSinceFetchMs) else null
    val kill = remainingSeconds(killRemaining, elapsedSinceFetchMs)
    if (offload == null && kill == null) return null
    val shorter = shorterRemaining(offload, kill)
    val offloadFirst = offload != null && (kill == null || offload <= kill)
    val offloadText = when {
        offload == null -> null
        offload == 0.0 -> "即将转入后台"
        else -> "${remainingLabelSeconds(offload)} 秒后自动转入后台"
    }
    val killText = when {
        kill == null -> null
        kill == 0.0 -> "即将中止"
        else -> "${remainingLabelSeconds(kill)} 秒后中止"
    }
    val primary = (if (offloadFirst) offloadText else killText) ?: return null
    val secondary = if (offloadFirst) killText else offloadText
    return if (secondary == null || shorter == null) primary else "$primary（$secondary）"
}