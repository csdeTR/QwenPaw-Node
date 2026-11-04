package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.CRON_TIMEZONE
import io.qwenpaw.node.data.model.CronScheduleDto
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 可视化排期的频率类型。 */
internal enum class CronFrequency { MINUTES, HOURLY, DAILY, WEEKLY, MONTHLY }

/**
 * 排期表单的字段集合。
 *
 * 星期用 ISO 编号：1 = 周一 … 7 = 周日，与服务端 `dow` 的数字写法一致。
 */
internal data class CronVisual(
    val frequency: CronFrequency = CronFrequency.DAILY,
    val everyMinutes: Int = 5,
    val hour: Int = 9,
    val minute: Int = 0,
    val daysOfWeek: Set<Int> = setOf(1),
    val dayOfMonth: Int = 1,
    /** 只在解析外部表达式时出现；表单不提供月份选择，`null` 表示每月。 */
    val month: Int? = null,
)

/** 可视化能产出的分钟间隔。其它步长只在「直接编辑表达式」里使用。 */
internal val CRON_MINUTE_STEPS = setOf(1, 2, 3, 5, 10, 15, 20, 30)

private val WEEKDAY_NAMES = mapOf(
    1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四",
    5 to "周五", 6 to "周六", 7 to "周日",
)

/**
 * 服务端把第 5 段归一化成三字母缩写（`app/crons/models.py::_crontab_dow_to_name`），
 * 所以客户端读回自己写的周任务时见到的是 `mon` 而不是 `1`，解析必须两种都认。
 */
private val DOW_ABBREVIATIONS = mapOf(
    "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4,
    "fri" to 5, "sat" to 6, "sun" to 7,
)

/** 快捷模式的预设，顺序按使用频率排。 */
internal val CRON_PRESETS: List<Pair<String, CronVisual>> = listOf(
    "每 5 分钟" to CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = 5),
    "每 10 分钟" to CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = 10),
    "每 15 分钟" to CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = 15),
    "每 30 分钟" to CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = 30),
    "每小时整点" to CronVisual(frequency = CronFrequency.HOURLY, minute = 0),
    "每天 09:00" to CronVisual(frequency = CronFrequency.DAILY, hour = 9, minute = 0),
    "每周一 09:00" to CronVisual(frequency = CronFrequency.WEEKLY, hour = 9, minute = 0),
    "每月 1 日 09:00" to CronVisual(frequency = CronFrequency.MONTHLY, hour = 9, minute = 0),
)

internal fun CronVisual.toExpression(): String = when (frequency) {
    CronFrequency.MINUTES -> "*/$everyMinutes * * * *"
    CronFrequency.HOURLY -> "$minute * * * *"
    CronFrequency.DAILY -> "$minute $hour * * *"
    CronFrequency.WEEKLY ->
        "$minute $hour * * ${daysOfWeek.ifEmpty { setOf(1) }.sorted().joinToString(",")}"
    CronFrequency.MONTHLY -> "$minute $hour $dayOfMonth ${month ?: "*"} *"
}

/**
 * 表达式的逆运算，只认本表单能生成的五种形状。
 *
 * 认不出返回 null：界面据此保留原文并切到「直接编辑」，不做猜测性重写。
 * 这样即使服务端接受 3 段或 4 段写法，客户端也不会把用户写的东西改坏。
 */
internal fun parseCronExpression(text: String): CronVisual? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size != 5) return null
    val (minutePart, hourPart, domPart, monthPart, dowPart) = parts
    val month: Int? = if (monthPart == "*") {
        null
    } else {
        monthPart.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    }

    val minute = minutePart.toIntOrNull()
    if (minutePart.startsWith("*/")) {
        val step = minutePart.removePrefix("*/").toIntOrNull() ?: return null
        if (step !in CRON_MINUTE_STEPS) return null
        return if (month == null && hourPart == "*" && domPart == "*" && dowPart == "*") {
            CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = step)
        } else {
            null
        }
    }
    if (minute == null || minute !in 0..59) return null

    if (hourPart == "*") {
        return if (month == null && domPart == "*" && dowPart == "*") {
            CronVisual(frequency = CronFrequency.HOURLY, minute = minute)
        } else {
            null
        }
    }

    val hour = hourPart.toIntOrNull() ?: return null
    if (hour !in 0..23) return null

    if (domPart == "*" && dowPart == "*") {
        return if (month == null) {
            CronVisual(frequency = CronFrequency.DAILY, hour = hour, minute = minute)
        } else {
            null
        }
    }
    if (domPart == "*") {
        if (month != null) return null
        val days = dowPart.split(",").mapNotNull { token ->
            token.toIntOrNull() ?: DOW_ABBREVIATIONS[token.lowercase()]
        }.toSet()
        return if (days.isNotEmpty() && days.all { it in 1..7 }) {
            CronVisual(
                frequency = CronFrequency.WEEKLY,
                hour = hour,
                minute = minute,
                daysOfWeek = days,
            )
        } else {
            null
        }
    }
    val dom = domPart.toIntOrNull() ?: return null
    return if (dowPart == "*" && dom in 1..31) {
        CronVisual(
            frequency = CronFrequency.MONTHLY,
            hour = hour,
            minute = minute,
            dayOfMonth = dom,
            month = month,
        )
    } else {
        null
    }
}

/** 一行中文摘要，列表与双时区对照都用它。 */
internal fun cronSummary(schedule: CronScheduleDto): String {
    if (schedule.type == "once") {
        val at = schedule.runAt?.let { iso ->
            runCatching { ZoneId.of(schedule.timezone) }.getOrNull()?.let { formatIsoInZone(iso, it) }
        }
        return if (at == null) "一次性任务" else "$at 执行一次"
    }
    val expression = schedule.cron.orEmpty()
    val visual = parseCronExpression(expression)
        ?: return if (expression.isBlank()) "未设置执行时间" else "Cron：$expression"
    return when (visual.frequency) {
        CronFrequency.MINUTES -> "每 ${visual.everyMinutes} 分钟"
        CronFrequency.HOURLY ->
            if (visual.minute == 0) "每小时整点" else "每小时的第 ${visual.minute} 分钟"
        CronFrequency.DAILY -> "每天 ${hhmm(visual.hour, visual.minute)}"
        CronFrequency.WEEKLY -> {
            val days = visual.daysOfWeek.sorted().joinToString("、") { WEEKDAY_NAMES[it] ?: "周一" }
            "每$days ${hhmm(visual.hour, visual.minute)}"
        }
        CronFrequency.MONTHLY ->
            if (visual.month != null) {
                "每年 ${visual.month} 月 ${visual.dayOfMonth} 日 ${hhmm(visual.hour, visual.minute)}"
            } else {
                "每月 ${visual.dayOfMonth} 日 ${hhmm(visual.hour, visual.minute)}"
            }
    }
}

/**
 * 服务端时区是否与 CST 同偏移（UTC+8）。
 *
 * 返回 null 表示无法判断：没取到、空串或时区名非法。调用方据此不显示对照。
 * 按**该时刻的偏移量**判断而不是城市名单：`Asia/Tokyo` 实际是 UTC+9，会算作不一致。
 */
internal fun isUtcPlus8(zoneName: String?, at: Instant = Instant.now()): Boolean? {
    val name = zoneName?.trim().orEmpty()
    if (name.isEmpty()) return null
    val zone = runCatching { ZoneId.of(name) }.getOrNull() ?: return null
    return zone.rules.getOffset(at).totalSeconds == 8 * 3600
}

/**
 * 按表达式推算接下来的触发时刻。
 *
 * [zone] 是表达式被解释的时区（任务的 `schedule.timezone`），默认北京时间。
 * 返回 `Instant` 而不是本地时间：换算到服务端时区必须经过真实时间点。
 * 只支持 [parseCronExpression] 能认出的形状，认不出返回空列表。
 */
internal fun nextRuns(
    expression: String,
    from: Instant,
    count: Int = 2,
    zone: ZoneId = ZoneId.of(CRON_TIMEZONE),
): List<Instant> {
    if (count <= 0) return emptyList()
    val visual = parseCronExpression(expression) ?: return emptyList()
    var cursor = from.atZone(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    val result = ArrayList<Instant>(count)
    while (result.size < count) {
        val next = nextAfter(cursor, visual, zone) ?: break
        result += next.toInstant()
        cursor = next.plusMinutes(1)
    }
    return result
}

private fun nextAfter(cursor: ZonedDateTime, visual: CronVisual, zone: ZoneId): ZonedDateTime? =
    when (visual.frequency) {
        CronFrequency.MINUTES -> {
            var c = cursor
            var guard = 0
            while (c.minute % visual.everyMinutes != 0 && guard < 60) {
                c = c.plusMinutes(1)
                guard++
            }
            if (c.minute % visual.everyMinutes == 0) c else null
        }

        CronFrequency.HOURLY -> {
            var c = cursor.withMinute(visual.minute).withSecond(0).withNano(0)
            if (!c.isAfter(cursor)) {
                c = c.plusHours(1).withMinute(visual.minute)
            }
            c
        }

        CronFrequency.DAILY -> {
            val time = LocalTime.of(visual.hour, visual.minute)
            var date = cursor.toLocalDate()
            var c = date.atTime(time).atZone(zone)
            if (!c.isAfter(cursor)) c = date.plusDays(1).atTime(time).atZone(zone)
            c
        }

        CronFrequency.WEEKLY -> {
            val time = LocalTime.of(visual.hour, visual.minute)
            val days = visual.daysOfWeek.ifEmpty { setOf(1) }
            var date = cursor.toLocalDate()
            var found: ZonedDateTime? = null
            for (step in 0..7) {
                val c = date.atTime(time).atZone(zone)
                if (date.dayOfWeek.value in days && c.isAfter(cursor)) {
                    found = c
                    break
                }
                date = date.plusDays(1)
            }
            found
        }

        CronFrequency.MONTHLY -> {
            val time = LocalTime.of(visual.hour, visual.minute)
            var ym: YearMonth = YearMonth.from(cursor)
            var found: ZonedDateTime? = null
            for (step in 0..48) {
                val monthMatches = visual.month == null || ym.monthValue == visual.month
                if (monthMatches && visual.dayOfMonth <= ym.lengthOfMonth()) {
                    val c = ym.atDay(visual.dayOfMonth).atTime(time).atZone(zone)
                    if (c.isAfter(cursor)) {
                        found = c
                        break
                    }
                }
                ym = ym.plusMonths(1)
            }
            found
        }
    }

/** `MM-dd HH:mm（UTC±X）`，偏移量按该时刻现算，夏令时地区不同月份可能不同。 */
internal fun formatInZone(instant: Instant, zone: ZoneId): String {
    val zoned = instant.atZone(zone)
    return "${MMDD_HHMM.format(zoned)}（${offsetText(zoned.offset)}）"
}

/** `MM-dd HH:mm`，不带偏移，列表里的紧凑写法。 */
internal fun formatCompact(instant: Instant, zone: ZoneId): String =
    MMDD_HHMM.format(instant.atZone(zone))

internal fun formatIsoInZone(iso: String, zone: ZoneId): String? =
    parseFlexible(iso)?.atZoneSameInstant(zone)?.let { MMDD_HHMM.format(it) }

/**
 * 解析服务端或客户端写出的时间串。
 *
 * 服务端对不带偏移的 `datetime` 原样输出（如 `2026-11-05T09:00:00`），
 * 带偏移的则保留偏移；两种都要能读，否则时间会静默消失。
 */
internal fun parseFlexible(iso: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime.parse(iso).atZone(ZoneId.of(CRON_TIMEZONE)).toOffsetDateTime()
        }.getOrNull()

private fun offsetText(offset: java.time.ZoneOffset): String {
    val total = offset.totalSeconds
    val sign = if (total < 0) "-" else "+"
    val abs = kotlin.math.abs(total)
    val hours = abs / 3600
    val minutes = (abs % 3600) / 60
    return if (minutes == 0) "UTC$sign$hours" else "UTC$sign$hours:${"%02d".format(minutes)}"
}

private fun hhmm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private val MMDD_HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 保存前校验，文案与官方 `cronJobs.validation` 对齐。
 *
 * cron 表达式只拦空值：服务端接受 3/4/5 三种段数并会归一化，
 * 本地按校紧的规则拦会把合法写法挡在外面，语法错误交给服务端 422。
 */
internal fun validateCronDraft(
    name: String,
    taskType: String,
    text: String,
    request: String,
    schedule: CronScheduleDto,
    silent: Boolean,
): String? {
    if (name.isBlank()) return "请输入任务名称"
    if (taskType == "text" && text.isBlank()) return "任务类型为消息内容时，内容不能为空"
    if (taskType == "agent" && request.isBlank()) return "任务类型为 Agent 时，请求内容不能为空"
    if (taskType == "text" && silent) return "消息内容任务不支持静默投递"
    if (schedule.type == "cron") {
        if (schedule.cron.isNullOrBlank()) return "循环任务必须填写 Cron 表达式"
    } else {
        if (schedule.runAt.isNullOrBlank()) return "一次性任务必须选择执行时间"
        when (schedule.repeatEndType) {
            "until" -> {
                if (schedule.repeatUntil.isNullOrBlank()) return "选择「终止于」时必须填写截止时间"
                val start = schedule.runAt?.let { parseFlexible(it) }
                val until = schedule.repeatUntil?.let { parseFlexible(it) }
                if (start != null && until != null && !until.isAfter(start)) {
                    return "截止时间必须晚于执行时间"
                }
            }
            "count" -> if ((schedule.repeatCount ?: 0) < 1) return "请输入执行次数"
        }
    }
    return null
}