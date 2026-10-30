package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史前缀与本轮流式内容的拼接。
 *
 * 这组断言对应两个实际观感问题：
 * 1. 发消息后聊天页「空荡荡」——历史被 `accumulator.snapshot()` 整屏冲掉；
 * 2. 回复完毕才看得到旧消息——直到 `reconcileWithServer` 用服务端历史覆盖回来。
 * 两者是同一个根因，判据都是「发送之后历史还在不在」。
 */
class MergeHistoryTest {

    private fun user(id: String, text: String) = ChatMessage(
        id = id,
        role = Role.USER,
        kind = ChatMessage.Kind.TEXT,
        text = text,
    )

    private fun assistant(id: String, text: String, streaming: Boolean = false) = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TEXT,
        text = text,
        streaming = streaming,
    )

    /** 首轮：没有历史时结果就是本轮内容，不能凭空多出条目。 */
    @Test
    fun `empty history returns live only`() {
        val live = listOf(user("local-user-1", "你好"))
        assertEquals(live, mergeHistory(emptyList(), live))
    }

    /** 本轮还没产生任何内容时（例如刚打开会话），结果是历史本身。 */
    @Test
    fun `empty live returns history only`() {
        val history = listOf(user("u1", "上一条"), assistant("a1", "上一条回复"))
        assertEquals(history, mergeHistory(history, emptyList()))
    }

    /** 关键用例：发送后历史必须仍在，且排在本轮内容之前。 */
    @Test
    fun `history stays in front of live content`() {
        val history = listOf(user("u1", "上一条"), assistant("a1", "上一条回复"))
        val live = listOf(user("local-user-1", "这一条"))
        val merged = mergeHistory(history, live)

        assertEquals(3, merged.size)
        assertEquals("u1", merged[0].id)
        assertEquals("a1", merged[1].id)
        assertEquals("local-user-1", merged[2].id)
    }

    /** 流式内容陆续追加时，历史不能因此缩短。 */
    @Test
    fun `streaming growth never drops history`() {
        val history = listOf(user("u1", "上一条"))
        var live = listOf(user("local-user-1", "这一条"))
        val afterFirstToken = mergeHistory(history, live + assistant("a2", "你", streaming = true))
        live = live + assistant("a2", "你好", streaming = true)

        assertEquals(3, afterFirstToken.size)
        assertEquals(3, mergeHistory(history, live).size)
        assertEquals("u1", mergeHistory(history, live)[0].id)
    }
}