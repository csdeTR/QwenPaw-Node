package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.CronDispatchTargetItemDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 投递目标选择器的文案。
 *
 * 服务端的 `dispatch-targets` 只带 `session_id`，名称要从会话列表另外取；
 * 取不到名称时必须退回 session_id，而不是显示一片空白项。
 */
class CronTargetLabelTest {

    private val item = CronDispatchTargetItemDto(
        channel = "console",
        userId = "default",
        sessionId = "1793496108871-abc",
    )

    @Test
    fun `name is used when available`() {
        val names = mapOf("1793496108871-abc" to "开启新的旅程")
        assertEquals("开启新的旅程", item.displayName(names))
        assertEquals("开启新的旅程 · 1793496108871-abc", targetLabel(item, names))
    }

    @Test
    fun `session id stands in when the name is missing`() {
        assertEquals("1793496108871-abc", item.displayName(emptyMap()))
        assertEquals("1793496108871-abc", targetLabel(item, emptyMap()))
    }

    /** 空名称与缺失等价，不能让已选行显示成「 · 1793496…」。 */
    @Test
    fun `blank name falls back to session id`() {
        val names = mapOf("1793496108871-abc" to "  ")
        assertEquals("1793496108871-abc", item.displayName(names))
        assertEquals("1793496108871-abc", targetLabel(item, names))
    }
}