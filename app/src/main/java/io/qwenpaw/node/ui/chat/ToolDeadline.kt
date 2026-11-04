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
 * 操作区下方那一行倒计时文案。
 *
 * 两个时限只取较短的一个作主句，另一个放同一行的括号里；到 0 时不再报秒数，
 * 直接写「即将…」——「0 秒后」读起来像是已经发生了，但服务端还没执行。
 */
internal fun toolDeadlineLabel(
    offloadRemaining: Double?,
    killRemaining: Double?,
    elapsedSinceFetchMs: Long,
): String? {
    val offload = remainingSeconds(offloadRemaining, elapsedSinceFetchMs)
    val kill = remainingSeconds(killRemaining, elapsedSinceFetchMs)
    if (offload == null && kill == null) return null
    val shorter = shorterRemaining(offload, kill)
    val offloadFirst = offload != null && (kill == null || offload <= kill)
    val primary = when {
        offloadFirst -> if (offload == 0.0) "即将转入后台" else "${remainingLabelSeconds(offload)} 秒后自动转入后台"
        else -> if (kill == 0.0) "即将中止" else "${remainingLabelSeconds(kill)} 秒后中止"
    }
    val secondary = when {
        offload == null || kill == null -> null
        offloadFirst -> if (kill == 0.0) "即将中止" else "${remainingLabelSeconds(kill)} 秒后中止"
        else -> if (offload == 0.0) "即将转入后台" else "${remainingLabelSeconds(offload)} 秒后自动转入后台"
    }
    return if (secondary == null || shorter == null) primary else "$primary（$secondary）"
}