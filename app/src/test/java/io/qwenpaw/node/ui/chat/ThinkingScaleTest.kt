package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ThinkingControlDto
import io.qwenpaw.node.data.model.ThinkingValueDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度滑块取值。
 *
 * 期望值全部按 qwenpaw 官方 Web 端的 `ThinkingControl` 规则独立算出，
 * 不照抄客户端实现里的常量。
 */
class ThinkingScaleTest {

    private fun effort(vararg levels: String, off: Boolean = false) = ThinkingControlDto(
        kind = "effort",
        efforts = levels.toList(),
        supportsOff = off,
    )

    private fun budget(min: Long, max: Long, off: Boolean = false) = ThinkingControlDto(
        kind = "budget",
        efforts = emptyList(),
        supportsOff = off,
        budgetMin = min,
        budgetMax = max,
    )

    @Test
    fun `effort stops follow the server list`() {
        val scale = ThinkingScale.of(effort("low", "high", "max"))!!
        assertEquals(listOf("low", "high", "max"), scale.stops)
        assertEquals(1, scale.indexOf("high"))
        assertTrue(scale.adjustable)
    }

    @Test
    fun `supports off puts one extra stop on the left`() {
        val scale = ThinkingScale.of(effort("low", "high", off = true))!!
        assertEquals(listOf("off", "low", "high"), scale.stops)
        assertEquals(0, scale.indexOf("off"))
    }

    @Test
    fun `single effort stop is not adjustable`() {
        assertNull(ThinkingScale.of(effort("high")))
    }

    @Test
    fun `unknown kind is not adjustable`() {
        assertNull(ThinkingScale.of(ThinkingControlDto(kind = "unsupported")))
        assertNull(ThinkingScale.of(null))
    }

    @Test
    fun `budget slider range is the server budget range`() {
        val scale = ThinkingScale.of(budget(1, 81920))!!
        assertEquals(1L, scale.sliderMin)
        assertEquals(81920L, scale.budgetMax)
        assertTrue(scale.adjustable)
    }

    /**
     * 关闭带宽度是区间的 12%，与官方一致。
     * `budget_min = 10000`、`budget_max = 50000` 时，宽度 4800，关闭带左端 5200，
     * 判为关闭的中点是 7600。
     */
    @Test
    fun `off band takes twelve percent of the range`() {
        val scale = ThinkingScale.of(budget(10000, 50000, off = true))!!
        assertEquals(5200L, scale.offFloor)
        assertEquals(5200L, scale.sliderMin)
        assertEquals(7600.0, scale.offThreshold, 0.001)
    }

    @Test
    fun `off band never goes below zero`() {
        val scale = ThinkingScale.of(budget(1, 81920, off = true))!!
        assertEquals(0L, scale.offFloor)
        assertEquals(0L, scale.sliderMin)
    }

    @Test
    fun `budget labels split the range at a quarter six tenths and 85 percent`() {
        val scale = ThinkingScale.of(budget(0, 1000))!!
        assertEquals("轻量思考", scale.budgetLabel(249))
        assertEquals("均衡思考", scale.budgetLabel(250))
        assertEquals("均衡思考", scale.budgetLabel(599))
        assertEquals("深入思考", scale.budgetLabel(600))
        assertEquals("深入思考", scale.budgetLabel(849))
        assertEquals("充分思考", scale.budgetLabel(850))
    }

    @Test
    fun `current value labels inherit off and budget`() {
        val scale = ThinkingScale.of(budget(0, 1000))!!
        assertEquals("跟随默认", scale.label(null))
        assertEquals("跟随默认", scale.label(ThinkingValueDto(level = "inherit")))
        assertEquals("关闭思考", scale.label(ThinkingValueDto(level = "off")))
        assertEquals("充分思考", scale.label(ThinkingValueDto(level = "budget", budgetTokens = 900)))
    }

    @Test
    fun `start tokens clamp to the slider range`() {
        val scale = ThinkingScale.of(budget(1000, 20000))!!
        assertEquals(10500L, scale.startTokens(null, null))
        assertEquals(12345L, scale.startTokens(ThinkingValueDto(level = "budget", budgetTokens = 12345), null))
        assertEquals(20000L, scale.startTokens(ThinkingValueDto(level = "budget", budgetTokens = 999999), null))
    }

    /**
     * 跟随默认时滑块要停在服务端算出的有效预算上，不能停在 `budget_min`。
     *
     * 实测 Qwen3.8-Flash：`budget_min = 1`、`budget_max = 262144`、
     * `effective.budget_tokens = 131072`。按 `budget_min` 起始会显示「1 tokens」。
     */
    @Test
    fun `inherit starts from effective budget instead of minimum`() {
        val scale = ThinkingScale.of(budget(1, 262144, off = true))!!
        val inherit = ThinkingValueDto(level = "inherit")
        val effective = ThinkingValueDto(level = "budget", budgetTokens = 131072)
        assertEquals(131072L, scale.startTokens(inherit, effective))
        // 显式设过值时以显式值为准。
        assertEquals(
            4096L,
            scale.startTokens(ThinkingValueDto(level = "budget", budgetTokens = 4096), effective),
        )
    }

    @Test
    fun `effort labels use the official wording`() {
        assertEquals("跟随默认", thinkingLevelLabel(null))
        assertEquals("跟随默认", thinkingLevelLabel("inherit"))
        assertEquals("关闭思考", thinkingLevelLabel("off"))
        assertEquals("极低", thinkingLevelLabel("minimal"))
        assertEquals("最高", thinkingLevelLabel("max"))
    }
}
