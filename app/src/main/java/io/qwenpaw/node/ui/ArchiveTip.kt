package io.qwenpaw.node.ui

import java.time.LocalDate

/**
 * 归档提示的抑制规则。
 *
 * 用户勾过「今日不再提示」后，当天归档不再弹确认框，跨天自动恢复。
 * 存的是日期字符串（`yyyy-MM-dd`）而不是布尔值，因此不需要额外的定时任务去重置——
 * 日期一变判断自然失效。
 *
 * 抽成纯函数是为了能在 JVM 单测里验证跨天行为，而不必构造 `SharedPreferences`。
 */
internal object ArchiveTip {

    /** [saved] 是被抑制到的日期，为 null 表示从未勾过。 */
    fun shouldShow(saved: String?, today: LocalDate = LocalDate.now()): Boolean =
        saved != today.toString()
}