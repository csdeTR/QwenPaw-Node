package io.qwenpaw.node.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 下一次触发的推算与双时区格式化。 */
class NextRunsTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")
    private val from = Instant.parse("2026-11-03T00:00:00Z") // 北京时间 11-03 08:00

    @Test
    fun `every five minutes starts at the next multiple`() {
        val runs = nextRuns("*/5 * * * *", from)
        assertEquals(2, runs.size)
        assertEquals("08:05", local(runs[0], shanghai))
        assertEquals("08:10", local(runs[1], shanghai))
    }

    @Test
    fun `hourly skips the current hour when the minute has passed`() {
        val runs = nextRuns("0 * * * *", from)
        assertEquals("09:00", local(runs[0], shanghai))
    }

    @Test
    fun `daily uses the configured hour and minute`() {
        val runs = nextRuns("0 9 * * *", from)
        assertEquals("11-03 09:00", localDate(runs[0], shanghai))
    }

    @Test
    fun `weekly lands on the next requested weekday`() {
        // 起点是北京时间 11-03（周二）08:00，接下来依次是周三、下周一。
        val runs = nextRuns("0 9 * * 1,3", from)
        assertEquals(2, runs.size)
        assertEquals(DayOfWeek.WEDNESDAY, runs[0].atZone(shanghai).dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, runs[1].atZone(shanghai).dayOfWeek)
        assertTrue(runs[0].isBefore(runs[1]))
    }

    @Test
    fun `monthly skips months without the requested day`() {
        val runs = nextRuns("0 9 31 * *", from, count = 2)
        assertEquals("12-31 09:00", localDate(runs[0], shanghai))
        assertEquals("01-31 09:00", localDate(runs[1], shanghai))
    }

    @Test
    fun `february twenty ninth only appears in leap years`() {
        val runs = nextRuns("0 9 29 2 *", from, count = 1)
        assertEquals(LocalDate.of(2028, 2, 29), runs[0].atZone(shanghai).toLocalDate())
    }

    @Test
    fun `unknown expressions produce nothing rather than a guess`() {
        assertTrue(nextRuns("0 9 * * 1-5", from).isEmpty())
        assertTrue(nextRuns("bad", from).isEmpty())
    }

    @Test
    fun `same instant formats differently in both zones`() {
        // 2026 年美国夏令时在 11-01 结束，11-05 已是 UTC-5。
        val instant = Instant.parse("2026-11-05T01:00:00Z")
        assertEquals("11-05 09:00（UTC+8）", formatInZone(instant, shanghai))
        assertEquals("11-04 20:00（UTC-5）", formatInZone(instant, newYork))
    }

    @Test
    fun `summer offset reflects daylight saving`() {
        val summer = Instant.parse("2026-07-15T13:00:00Z")
        assertEquals("07-15 09:00（UTC-4）", formatInZone(summer, newYork))
    }

    @Test
    fun `offset follows daylight saving`() {
        val winter = Instant.parse("2026-01-15T14:00:00Z")
        assertEquals("01-15 09:00（UTC-5）", formatInZone(winter, newYork))
    }

    @Test
    fun `naive timestamps are read as beijing time`() {
        val parsed = parseFlexible("2026-11-05T09:00:00")
        assertEquals("2026-11-05T09:00+08:00", parsed?.toString())
    }

    private fun local(instant: Instant, zone: ZoneId): String =
        instant.atZone(zone).toLocalTime().toString().take(5)

    private fun localDate(instant: Instant, zone: ZoneId): String {
        val zoned = instant.atZone(zone)
        val date: LocalDate = zoned.toLocalDate()
        return "%02d-%02d %02d:%02d".format(
            date.monthValue, date.dayOfMonth, zoned.hour, zoned.minute,
        )
    }
}