package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 菜单动作与图标的映射完整性。
 *
 * 图标是逐项手配的，新增一个 [MenuAction] 时很容易只改文案忘了图标，
 * 而漏配的表现只是「这一项没有图标」——肉眼扫一遍菜单未必看得出来。
 * 遍历全枚举即可在单测里把这种遗漏钉住。
 */
class MenuIconsTest {

    @Test
    fun `every action has an icon`() {
        val missing = MenuAction.entries.filter { MenuIcons.of(it) == null }
        assertTrue("以下动作缺少图标：$missing", missing.isEmpty())
    }

    /** 置顶与取消置顶共用同一个图标，这是有意的：动作相反，但视觉上属于同一条操作。 */
    @Test
    fun `pin and unpin share an icon`() {
        val pin = MenuIcons.of(MenuAction.PIN)
        val unpin = MenuIcons.of(MenuAction.UNPIN)
        assertTrue(pin != null && unpin != null)
        assertEquals(pin, unpin)
    }
}