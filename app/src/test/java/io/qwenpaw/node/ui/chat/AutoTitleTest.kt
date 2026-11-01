package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动命名的判定逻辑。
 *
 * 这一条对应「新建会话发消息后没有自动命名」。根因不在客户端拼接名称，
 * 而在请求体缺 `meta.console_placeholder_name`：服务端 `console.py` 只有在
 * `chat.meta["console_placeholder_name"] == chat.name` 时才会把会话名换成首条消息，
 * 之后才满足 `chat.name == name` 从而派发标题生成任务。
 */
class AutoTitleTest {

    /** 名字仍等于占位名：服务端还没把标题写回来，需要补一次刷新。 */
    @Test
    fun `placeholder name still present means refresh needed`() {
        assertTrue(needsTitleRefresh("帮我看下这个报错", "帮我看下这个报错"))
    }

    /** 名字已经变了：标题已生成或被用户重命名，不需要再刷。 */
    @Test
    fun `renamed chat does not need refresh`() {
        assertFalse(needsTitleRefresh("Kotlin 协程报错排查", "帮我看下这个报错"))
    }

    /** 占位名未知（非本客户端创建的会话）时不补刷，避免白跑请求。 */
    @Test
    fun `unknown placeholder never refreshes`() {
        assertFalse(needsTitleRefresh("任意名称", ""))
    }

    /** 名字为空说明列表里没查到或请求失败，不能当成「已改名」。 */
    @Test
    fun `blank name does not count as renamed`() {
        assertFalse(needsTitleRefresh("", "新会话"))
    }
}