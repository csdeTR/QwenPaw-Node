package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 倒计时展示文案。 */
class ToolDeadlineLabelTest {

    @Test
    fun `single deadline reads as one sentence`() {
        assertEquals("30 秒后自动转入后台", toolDeadlineLabel(30.0, null, 0))
        assertEquals("5 秒后中止", toolDeadlineLabel(null, 5.0, 0))
    }

    @Test
    fun `both deadlines put the shorter one first`() {
        assertEquals(
            "5 秒后自动转入后台（30 秒后中止）",
            toolDeadlineLabel(5.0, 30.0, 0),
        )
        assertEquals(
            "5 秒后中止（30 秒后自动转入后台）",
            toolDeadlineLabel(30.0, 5.0, 0),
        )
    }

    @Test
    fun `zero stops reporting seconds`() {
        assertEquals("即将转入后台（即将中止）", toolDeadlineLabel(0.0, 0.0, 0))
        assertEquals("即将转入后台（30 秒后中止）", toolDeadlineLabel(0.0, 30.0, 0))
    }

    @Test
    fun `snapshot decays with elapsed time`() {
        assertEquals("25 秒后自动转入后台", toolDeadlineLabel(30.0, null, 5000))
    }

    @Test
    fun `no deadlines means no line`() {
        assertNull(toolDeadlineLabel(null, null, 0))
    }

    @Test
    fun `auto expand waits seven and a half seconds`() {
        assertEquals(7500L, AUTO_EXPAND_MS)
        assertFalse(autoExpandDue(7499))
        assertTrue(autoExpandDue(7500))
        assertTrue(autoExpandDue(9000))
    }
}