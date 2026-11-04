package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 工具调用倒计时的纯计算。 */
class ToolDeadlineTest {

    @Test
    fun `remaining shrinks with elapsed time`() {
        assertEquals(30.0, remainingSeconds(30.0, 0)!!, 0.001)
        assertEquals(25.0, remainingSeconds(30.0, 5000)!!, 0.001)
        assertEquals(0.0, remainingSeconds(5.0, 6000)!!, 0.001)
    }

    @Test
    fun `missing snapshot stays missing`() {
        assertNull(remainingSeconds(null, 1000))
    }

    @Test
    fun `label rounds up so a fresh snapshot does not show zero`() {
        assertEquals(1, remainingLabelSeconds(0.4))
        assertEquals(0, remainingLabelSeconds(0.0))
        assertEquals(3, remainingLabelSeconds(2.01))
        assertNull(remainingLabelSeconds(null))
    }

    @Test
    fun `shorter of the two deadlines wins`() {
        assertEquals(5.0, shorterRemaining(5.0, 30.0)!!, 0.001)
        assertEquals(5.0, shorterRemaining(30.0, 5.0)!!, 0.001)
        assertEquals(7.0, shorterRemaining(null, 7.0)!!, 0.001)
        assertEquals(7.0, shorterRemaining(7.0, null)!!, 0.001)
        assertNull(shorterRemaining(null, null))
    }
}