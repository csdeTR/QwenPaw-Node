package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.CronScheduleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** 排期表达式的生成、解析与摘要。 */
class CronScheduleTest {

    private val at = Instant.parse("2026-11-03T00:00:00Z")

    @Test
    fun `visual builds five field expressions`() {
        assertEquals(
            "*/5 * * * *",
            CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = 5).toExpression(),
        )
        assertEquals(
            "0 * * * *",
            CronVisual(frequency = CronFrequency.HOURLY, minute = 0).toExpression(),
        )
        assertEquals(
            "30 9 * * *",
            CronVisual(frequency = CronFrequency.DAILY, hour = 9, minute = 30).toExpression(),
        )
        assertEquals(
            "0 9 * * 1,3",
            CronVisual(
                frequency = CronFrequency.WEEKLY,
                hour = 9,
                minute = 0,
                daysOfWeek = setOf(3, 1),
            ).toExpression(),
        )
        assertEquals(
            "0 9 31 * *",
            CronVisual(frequency = CronFrequency.MONTHLY, hour = 9, minute = 0, dayOfMonth = 31)
                .toExpression(),
        )
    }

    @Test
    fun `parse round trips every generated shape`() {
        val visuals = listOf(
            CronVisual(frequency = CronFrequency.MINUTES, everyMinutes = 10),
            CronVisual(frequency = CronFrequency.HOURLY, minute = 15),
            CronVisual(frequency = CronFrequency.DAILY, hour = 9, minute = 0),
            CronVisual(frequency = CronFrequency.WEEKLY, hour = 9, minute = 0, daysOfWeek = setOf(1, 7)),
            CronVisual(frequency = CronFrequency.MONTHLY, hour = 9, minute = 0, dayOfMonth = 15),
        )
        visuals.forEach { visual ->
            val parsed = parseCronExpression(visual.toExpression())
            assertEquals(visual.frequency, parsed?.frequency)
        }
    }

    @Test
    fun `parse rejects shapes the builder cannot produce`() {
        assertNull(parseCronExpression("*/7 * * * *"))
        assertNull(parseCronExpression("0 0 9 * * *"))
        assertNull(parseCronExpression("0 9 * *"))
        assertNull(parseCronExpression("not a cron"))
        assertNull(parseCronExpression("0 25 * * *"))
        assertNull(parseCronExpression("0 9 * * 9"))
    }

    /**
     * 服务端把 `1,3` 归一化成 `mon,wed`，读回自己写的任务时必须能认出来，
     * 否则每次编辑都会退化成「直接编辑」。
     */
    @Test
    fun `parse accepts server normalized weekday names`() {
        val visual = parseCronExpression("0 9 * * mon,wed")
        assertEquals(CronFrequency.WEEKLY, visual?.frequency)
        assertEquals(setOf(1, 3), visual?.daysOfWeek)
        assertEquals("每周一、周三 09:00", cronSummary(CronScheduleDto(cron = "0 9 * * mon,wed")))
    }

    @Test
    fun `summary reads as one chinese sentence`() {
        assertEquals(
            "每天 09:00",
            cronSummary(CronScheduleDto(cron = "0 9 * * *")),
        )
        assertEquals(
            "每 15 分钟",
            cronSummary(CronScheduleDto(cron = "*/15 * * * *")),
        )
        assertEquals(
            "每小时整点",
            cronSummary(CronScheduleDto(cron = "0 * * * *")),
        )
        assertEquals(
            "每周一、周三 09:00",
            cronSummary(CronScheduleDto(cron = "0 9 * * 1,3")),
        )
        assertEquals(
            "每月 15 日 09:00",
            cronSummary(CronScheduleDto(cron = "0 9 15 * *")),
        )
        // 认不出的表达式原样展示，不猜。
        assertEquals(
            "Cron：0 9 * * 1-5",
            cronSummary(CronScheduleDto(cron = "0 9 * * 1-5")),
        )
    }

    @Test
    fun `once summary converts to the task timezone`() {
        val summary = cronSummary(
            CronScheduleDto(type = "once", runAt = "2026-11-05T01:00:00Z"),
        )
        assertEquals("11-05 09:00 执行一次", summary)
    }

    @Test
    fun `timezone check compares offset rather than city name`() {
        assertEquals(true, isUtcPlus8("Asia/Shanghai", at))
        // 东京是 UTC+9，不算同一时区。
        assertEquals(false, isUtcPlus8("Asia/Tokyo", at))
        assertEquals(false, isUtcPlus8("America/New_York", at))
        assertEquals(false, isUtcPlus8("UTC", at))
        assertNull(isUtcPlus8(null, at))
        assertNull(isUtcPlus8("", at))
        assertNull(isUtcPlus8("Not/AZone", at))
    }

    @Test
    fun `draft validation covers the required fields`() {
        val cron = CronScheduleDto(cron = "0 9 * * *")
        assertEquals("请输入任务名称", validateCronDraft("", "text", "x", "", cron, false))
        assertEquals(
            "任务类型为消息内容时，内容不能为空",
            validateCronDraft("任务", "text", " ", "", cron, false),
        )
        assertEquals(
            "任务类型为 Agent 时，请求内容不能为空",
            validateCronDraft("任务", "agent", "", "", cron, false),
        )
        assertEquals(
            "循环任务必须填写 Cron 表达式",
            validateCronDraft("任务", "text", "x", "", CronScheduleDto(cron = ""), false),
        )
        assertEquals(
            "消息内容任务不支持静默投递",
            validateCronDraft("任务", "text", "x", "", cron, true),
        )
        assertNull(validateCronDraft("任务", "text", "x", "", cron, false))
    }

    @Test
    fun `repeat until must be later than run time`() {
        val once = CronScheduleDto(
            type = "once",
            runAt = "2026-11-05T01:00:00Z",
            repeatEndType = "until",
            repeatUntil = "2026-11-04T01:00:00Z",
        )
        assertEquals(
            "截止时间必须晚于执行时间",
            validateCronDraft("任务", "text", "x", "", once, false),
        )
    }
}