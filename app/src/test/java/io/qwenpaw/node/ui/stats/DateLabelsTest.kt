package io.qwenpaw.node.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 趋势图与按日期区块的短日期。
 *
 * 只做 `LocalDate` 解析，不经 `Instant`：服务端给的是纯日期，
 * 转成时刻再格式化会在 UTC 与东八区之间把日期挪一天。
 */
class DateLabelsTest {

    @Test
    fun `formats as month slash day`() {
        assertEquals("11/14", DateLabels.short("2026-11-14"))
    }

    @Test
    fun `pads single digit month and day`() {
        assertEquals("01/05", DateLabels.short("2026-01-05"))
    }

    /** 无法解析时原样返回：宁可显示原文，也不要留一个空白刻度。 */
    @Test
    fun `invalid input is returned as is`() {
        assertEquals("2026-13-45", DateLabels.short("2026-13-45"))
        assertEquals("", DateLabels.short(""))
        assertEquals("not-a-date", DateLabels.short("not-a-date"))
    }
}
