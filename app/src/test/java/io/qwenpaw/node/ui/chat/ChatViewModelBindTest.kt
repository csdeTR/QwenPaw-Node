package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `bind()` 是否清空当前会话。
 *
 * 这一条直接对应「往某个会话发消息却新建了一个会话」：此前 `bind()` 只看
 * `currentChatId != null` 就无条件清空，而同一实例的「顶栏重连 / 网络恢复 /
 * 改地址」也走这条路径，于是重连一次会话 id 就没了，下一次 `send()` 走新建。
 */
class ChatViewModelBindTest {

    @Test
    fun `same instance keeps current chat`() {
        assertFalse(shouldClearOnBind("inst-1", "inst-1"))
    }

    /** 换到另一台服务器必须清空：旧会话 id 在新服务器上不存在。 */
    @Test
    fun `different instance clears current chat`() {
        assertTrue(shouldClearOnBind("inst-1", "inst-2"))
    }

    /** 冷启动首次绑定不清空——那时本来就没有会话可清。 */
    @Test
    fun `first bind does not clear`() {
        assertFalse(shouldClearOnBind("", "inst-1"))
    }

    /** 实例 id 未知时（空串）再绑一次同样不清空，避免把用户正在看的会话抹掉。 */
    @Test
    fun `rebinding with blank id does not clear`() {
        assertFalse(shouldClearOnBind("inst-1", ""))
        assertFalse(shouldClearOnBind("", ""))
    }
}