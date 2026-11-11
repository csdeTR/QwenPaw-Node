package io.qwenpaw.node.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 服务端时间字符串的解析与展示。
 *
 * 服务端给的是 UTC ISO 8601（`2026-10-29T02:13:41.029Z` 这种）。
 * 旧实现在对话列表里直接截字符串前 10 位当日期，等于按 UTC 日界划分「今天」，
 * 在东八区会比用户直觉晚 8 小时——早上 7 点发的消息会被算成「昨天」。
 * 这里统一解析到本地时区后再格式化。
 */
object TimeLabels {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val monthDayFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val fullStampFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

    /**
     * 解析成带本地时区的时刻。无法解析时返回 null，调用方据此隐藏时间。
     *
     * 实测服务端有两种写法：
     * - 流式事件里的 `Instant.now().toString()`，形如 `...Z`；
     * - 历史接口 `metadata.timestamp` 里带本机时区偏移，
     *   形如 `2026-10-26T00:15:42.910613+08:00`。
     *
     * `Instant.parse` 只认 `Z`（ISO_INSTANT 的偏移支持在 JDK 12 才有，
     * Android 的 `java.time` 没有），所以带偏移的那种必须交给
     * `OffsetDateTime`。漏掉这一步的表现很具体：历史消息头部整块不渲染，
     * 因为它把「时间解析不出来」当成「不需要显示头部」。
     */
    fun parse(raw: String?): ZonedDateTime? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val zone = java.time.ZoneId.systemDefault()
        return runCatching {
            Instant.parse(text).atZone(zone)
        }.recoverCatching {
            java.time.OffsetDateTime.parse(text).atZoneSameInstant(zone)
        }.recoverCatching {
            // 少数服务端版本不带时区后缀，按 UTC 补上。
            Instant.parse(text + "Z").atZone(zone)
        }.getOrNull()
    }

    /** `HH:mm`，本地时区。解析失败返回 null。 */
    fun timeOnly(raw: String?): String? = parse(raw)?.format(timeFormatter)

    /**
     * 完整时刻：`2021.09.21 10:43:20`。
     *
     * 消息头部第二行用它。精确到秒不是为了好看，而是同一天里连着发几条
     * 时只有秒能分辨先后；放在独立一行也不会挤掉昵称。
     */
    fun fullStamp(raw: String?): String? = parse(raw)?.format(fullStampFormatter)

    /**
     * 相对日期：今天 / 昨天 / 今年内显示「M月d日」/ 往年显示「yyyy-MM-dd」。
     *
     * 用本地日历日相减，而不是 24 小时差：用户说的「昨天」是日历概念，
     * 跨夏令时或跨时区时按小时算会偏。
     */
    fun relativeDay(raw: String?): String {
        val time = parse(raw) ?: return ""
        val today = LocalDate.now()
        val date = time.toLocalDate()
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> "今天"
            days == 1L -> "昨天"
            date.year == today.year -> date.format(monthDayFormatter)
            else -> date.toString()
        }
    }

    /** 对话列表用的短日期：今天显示时间，更早显示日期。 */
    fun listStamp(raw: String?): String {
        val time = parse(raw) ?: return ""
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(time.toLocalDate(), today)
        return if (days == 0L) time.format(timeFormatter) else time.format(shortDateFormatter)
    }

    /**
     * 浮点秒时间戳转本地时刻。
     *
     * 收件箱事件的 `created_at` 是服务端用 `time.time()` 写入的秒级浮点数，
     * 不是上面那些 ISO 字符串，因此不能走 [parse]。时区显式作为参数传入，
     * 单测才能钉住东八区的输出而不依赖运行环境的默认值。
     */
    fun fromEpochSeconds(seconds: Double, zone: java.time.ZoneId): ZonedDateTime? =
        runCatching { Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone) }.getOrNull()

    /** 浮点秒时间戳的展示：今天 `HH:mm`，更早 `MM-dd`。解析失败返回空串。 */
    fun epochStamp(seconds: Double, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val time = fromEpochSeconds(seconds, zone) ?: return ""
        return relativeEpochStamp(time, zone)
    }

    /** 完整的小时分钟，供收件箱的详情行使用。 */
    fun epochTime(seconds: Double, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        fromEpochSeconds(seconds, zone)?.format(timeFormatter).orEmpty()

    /**
     * 毫秒时间戳的展示：今天 `HH:mm`，更早 `MM-dd`。
     *
     * 检查点节点的 `timestamp_ms` 是整数毫秒，与收件箱的浮点秒不同，
     * 不能共用 [epochStamp]。时区显式传入，单测才能钉住东八区的输出。
     */
    fun epochMillisStamp(
        millis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String {
        val time = runCatching { Instant.ofEpochMilli(millis).atZone(zone) }.getOrNull() ?: return ""
        return relativeEpochStamp(time, zone)
    }

    /** 毫秒时间戳的完整时刻，供检查点详情行使用。 */
    fun epochMillisFull(
        millis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String = runCatching {
        Instant.ofEpochMilli(millis).atZone(zone).format(fullStampFormatter)
    }.getOrDefault("")

    private fun relativeEpochStamp(time: ZonedDateTime, zone: java.time.ZoneId): String {
        val today = LocalDate.now(zone)
        val days = ChronoUnit.DAYS.between(time.toLocalDate(), today)
        return if (days == 0L) time.format(timeFormatter) else time.format(shortDateFormatter)
    }
}