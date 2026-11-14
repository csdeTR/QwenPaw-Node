package io.qwenpaw.node.ui.stats

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 趋势图横轴与按日期区块的短日期格式。 */
object DateLabels {

    private val shortFormatter = DateTimeFormatter.ofPattern("MM/dd")

    /**
     * `2026-11-14` → `11/14`。
     *
     * 只做 `LocalDate` 解析，不经 `Instant` 也不读系统时区：服务端给的是纯日期，
     * 转成时刻再格式化会在 UTC 与东八区之间把日期挪一天。
     * 无法解析时原样返回，宁可显示 `2026-13-45` 也不要留一个空白刻度。
     */
    fun short(raw: String): String = runCatching {
        LocalDate.parse(raw).format(shortFormatter)
    }.getOrDefault(raw)
}
