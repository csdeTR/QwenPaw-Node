package io.qwenpaw.node.ui

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * 服务端时间的解析与展示。
 *
 * 旧实现在会话列表里直接截 UTC 字符串的前 10 位当日期，等于按 UTC 日界
 * 划分「今天／昨天」。东八区早于 08:00 的消息会被算到前一天，这是实际的偏差。
 * 这里统一解析到本地时区再格式化。
 *
 * 断言依赖本地日历，CI runner 的默认时区是 UTC，因此整个类固定按东八区跑。
 * 带 `+08:00` 偏移的输入在 UTC 机器上会落到前一天，不固定时区断言就会失败。
 */
class TimeLabelsTest {

    companion object {
        private var original: TimeZone? = null

        @BeforeClass
        @JvmStatic
        fun pinZone() {
            original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        }

        @AfterClass
        @JvmStatic
        fun restoreZone() {
            original?.let { TimeZone.setDefault(it) }
        }
    }

    /** 本地时区固定为东八区来断言，避免测试机在别的时区时结论漂移。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `parses utc iso into local time`() {
        val parsed = TimeLabels.parse("2026-10-29T02:13:41.029Z")
        assertEquals(2026, parsed!!.year)
        assertEquals(10, parsed.monthValue)
        assertEquals(29, parsed.dayOfMonth)
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(TimeLabels.parse(null))
        assertNull(TimeLabels.parse(""))
        assertNull(TimeLabels.parse("不是时间"))
    }

    /** 少数服务端版本不带 Z 后缀，也要能解析。 */
    @Test
    fun `parses timestamp without z suffix`() {
        val parsed = TimeLabels.parse("2026-10-29T02:13:41")
        assertEquals(2026, parsed!!.year)
    }

    @Test
    fun `time only is hh mm`() {
        val text = TimeLabels.timeOnly("2026-10-29T02:13:41Z")
        assertEquals(5, text!!.length)
        assertEquals(':', text[2])
    }

    /**
     * 关键回归：东八区的「今天 01:00」对应 UTC 前一天 17:00。
     * 按 UTC 日界切字符串会算成「昨天」，按本地日历才是「今天」。
     */
    @Test
    fun `relative day uses local calendar not utc date`() {
        // 构造本地今天 00:30 的时刻，再转成 UTC 字符串。
        val localToday = LocalDate.now(zone).atTime(0, 30)
        val utc = localToday.atZone(zone).toInstant().toString()
        assertEquals("今天", TimeLabels.relativeDay(utc))
    }

    @Test
    fun `yesterday and older dates`() {
        val yesterday = LocalDate.now(zone).minusDays(1).atTime(12, 0)
        assertEquals(
            "昨天",
            TimeLabels.relativeDay(yesterday.atZone(zone).toInstant().toString()),
        )
        val longAgo = LocalDate.now(zone).minusDays(40).atTime(12, 0)
        val label = TimeLabels.relativeDay(longAgo.atZone(zone).toInstant().toString())
        // 往年显示完整日期，今年内显示「M月d日」。
        if (longAgo.year == LocalDate.now(zone).year) {
            assertEquals(true, label.endsWith("日"))
        } else {
            assertEquals(10, label.length)
        }
    }

    @Test
    fun `list stamp shows time for today and date otherwise`() {
        val todayNoon = LocalDate.now(zone).atTime(12, 0)
        assertEquals(
            5,
            TimeLabels.listStamp(todayNoon.atZone(zone).toInstant().toString()).length,
        )
        val earlier = LocalDate.now(zone).minusDays(3).atTime(12, 0)
        assertEquals(
            5,
            TimeLabels.listStamp(earlier.atZone(zone).toInstant().toString()).length,
        )
    }

    @Test
    fun `relative day of missing value is empty`() {
        assertEquals("", TimeLabels.relativeDay(null))
    }

    /**
     * 消息头部第二行用完整时刻，精确到秒。
     *
     * 合成一行且只到分钟时，同一天连着发几条会全显示成同一个时间，
     * 分不出先后，这是用户报上来的问题之一。
     */
    @Test
    fun `full stamp has date and seconds`() {
        val local = LocalDate.of(2021, 9, 21).atTime(10, 43, 20)
        val raw = local.atZone(zone).toInstant().toString()
        assertEquals("2021.09.21 10:43:20", TimeLabels.fullStamp(raw))
    }

    @Test
    fun `full stamp returns null for invalid input`() {
        assertNull(TimeLabels.fullStamp(null))
        assertNull(TimeLabels.fullStamp("不是时间"))
    }

    @Test
    fun `full stamp length is stable`() {
        val local = LocalDate.of(2026, 1, 2).atTime(3, 4, 5)
        val text = TimeLabels.fullStamp(local.atZone(zone).toInstant().toString())!!
        // yyyy.MM.dd HH:mm:ss 共 19 个字符，月日时分秒都要补零。
        assertEquals(19, text.length)
        assertEquals("2026.01.02 03:04:05", text)
    }

    /**
     * 回归：历史接口的 `metadata.timestamp` 带本机时区偏移，
     * 形如 `2026-10-26T00:15:42.910613+08:00`。
     *
     * `Instant.parse` 在 Android 的 java.time 上不认偏移，只认 `Z`，
     * 解析失败会让整个消息头部不渲染（`showHeader = stamp != null`）。
     * 实测就是这样丢掉整块昵称与时间的。
     */
    @Test
    fun `parses timestamp with local offset`() {
        val parsed = TimeLabels.parse("2026-10-26T00:15:42.910613+08:00")
        assertEquals(2026, parsed!!.year)
        assertEquals(10, parsed.monthValue)
        assertEquals(26, parsed.dayOfMonth)
        // 换算到本地时区后小时数可能不同，但必须能解析出完整时刻。
        assertEquals(
            "2026.10.26",
            TimeLabels.fullStamp("2026-10-26T00:15:42.910613+08:00")!!.substring(0, 10),
        )
    }

    /** 带偏移与带 Z 的同一时刻必须解析成同一个 Instant。 */
    @Test
    fun `offset and z forms describe the same instant`() {
        val offset = TimeLabels.parse("2026-10-26T00:15:42+08:00")!!
        val utc = TimeLabels.parse("2026-10-25T16:15:42Z")!!
        assertEquals(offset.toInstant(), utc.toInstant())
    }

    /** 秒级精度同时保留：同一天里连着发几条靠它分辨先后。 */
    @Test
    fun `offset form keeps seconds`() {
        val text = TimeLabels.fullStamp("2026-10-26T00:15:42.910613+08:00")!!
        assertEquals(':', text[13])
        assertEquals(19, text.length)
    }
}