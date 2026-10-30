package io.qwenpaw.node.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 归档提示按自然日抑制。
 *
 * 「今日不再提示」是日期而不是布尔值：跨天要自动恢复，
 * 存布尔值的话就得额外挂一个零点重置的任务。
 */
class ArchiveTipTest {

    private val today = LocalDate.of(2026, 10, 28)

    @Test
    fun `never suppressed shows`() {
        assertTrue(ArchiveTip.shouldShow(null, today))
    }

    @Test
    fun `suppressed today does not show`() {
        assertFalse(ArchiveTip.shouldShow("2026-10-28", today))
    }

    @Test
    fun `suppressed yesterday shows again`() {
        assertTrue(ArchiveTip.shouldShow("2026-10-27", today))
    }

    /** 跨月、跨年都要能正确恢复，避免出现「上个月勾过就再也不弹」。 */
    @Test
    fun `suppressed last year shows again`() {
        assertTrue(ArchiveTip.shouldShow("2025-12-31", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `format mismatch is treated as stale`() {
        assertTrue(ArchiveTip.shouldShow("2026/10/28", today))
        assertTrue(ArchiveTip.shouldShow("", today))
    }
}