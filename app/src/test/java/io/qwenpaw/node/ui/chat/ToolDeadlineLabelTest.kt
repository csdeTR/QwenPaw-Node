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
        assertEquals("5 秒后中止", toolDeadlineLabel(null, 5.0, 0))
    }

    /**
     * 策略为「保持前台」时后台时限不出现。
     *
     * 到期后工具仍留在前台，显示「N 秒后到达后台时限」会让用户以为它马上要转后台。
     */
    @Test
    fun `offload deadline is hidden unless auto offload is on`() {
        assertNull(toolDeadlineLabel(30.0, null, 0))
        assertEquals("5 秒后中止", toolDeadlineLabel(30.0, 5.0, 0))
        assertEquals("5 秒后中止", toolDeadlineLabel(5.0, 5.0, 0))
    }

    @Test
    fun `both deadlines put the shorter one first`() {
        assertEquals(
            "5 秒后自动转入后台（30 秒后中止）",
            toolDeadlineLabel(5.0, 30.0, 0, autoOffload = true),
        )
        assertEquals(
            "5 秒后中止（30 秒后自动转入后台）",
            toolDeadlineLabel(30.0, 5.0, 0, autoOffload = true),
        )
    }

    @Test
    fun `zero stops reporting seconds`() {
        assertEquals("即将转入后台（即将中止）", toolDeadlineLabel(0.0, 0.0, 0, autoOffload = true))
        assertEquals("即将转入后台（30 秒后中止）", toolDeadlineLabel(0.0, 30.0, 0, autoOffload = true))
    }

    @Test
    fun `snapshot decays with elapsed time`() {
        assertEquals("25 秒后中止", toolDeadlineLabel(null, 30.0, 5000))
        assertEquals("25 秒后自动转入后台", toolDeadlineLabel(30.0, null, 5000, autoOffload = true))
    }

    /**
     * 服务端策略为「自动转入后台」时才能断言会转后台。
     * 默认策略是「保持前台」，那种情况下写「自动转入后台」是在替服务端做决定。
     */
    @Test
    fun `auto offload policy names the transfer explicitly`() {
        assertEquals(
            "30 秒后自动转入后台",
            toolDeadlineLabel(30.0, null, 0, autoOffload = true),
        )
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