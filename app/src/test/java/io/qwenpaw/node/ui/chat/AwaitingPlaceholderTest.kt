package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在生成」等待行的显示条件。
 *
 * 用户的原话是「用户发送消息后，聊天页面还是空荡荡的，只有上游开始输出 token 的时候，
 * 前端才会出现或许是工具调用，或许是 think 的信息」。等待行就是为这一段空白加的：
 * 它要在用户气泡出现的同一刻出现，并在第一帧真实内容到达时立刻退场。
 */
class AwaitingPlaceholderTest {

    private fun user(id: String = "local-user-1") = ChatMessage(
        id = id,
        role = Role.USER,
        kind = ChatMessage.Kind.TEXT,
        text = "你好",
    )

    private fun streaming(kind: ChatMessage.Kind) = ChatMessage(
        id = "a1",
        role = Role.ASSISTANT,
        kind = kind,
        text = if (kind == ChatMessage.Kind.TEXT) "你" else "",
        streaming = true,
    )

    /** 没有在跑就永远不显示，否则静止的会话会一直挂着「正在生成」。 */
    @Test
    fun `not running hides placeholder`() {
        assertFalse(needsAwaitingPlaceholder(listOf(user()), running = false))
    }

    /** 刚发出、只有用户气泡：这是要显示的那一段。 */
    @Test
    fun `running with only user bubble shows placeholder`() {
        assertTrue(needsAwaitingPlaceholder(listOf(user()), running = true))
    }

    /** 正文首帧到达后让位。 */
    @Test
    fun `first text token hides placeholder`() {
        assertFalse(
            needsAwaitingPlaceholder(
                listOf(user(), streaming(ChatMessage.Kind.TEXT)),
                running = true,
            ),
        )
    }

    /** 思考帧同样算「已经有内容」。 */
    @Test
    fun `reasoning frame hides placeholder`() {
        assertFalse(
            needsAwaitingPlaceholder(
                listOf(user(), streaming(ChatMessage.Kind.REASONING)),
                running = true,
            ),
        )
    }

    /** 工具调用同样算「已经有内容」。 */
    @Test
    fun `tool frame hides placeholder`() {
        assertFalse(
            needsAwaitingPlaceholder(
                listOf(user(), streaming(ChatMessage.Kind.TOOL)),
                running = true,
            ),
        )
    }

    /** 历史里已完成的智能体消息不算「正在输出」，仍要显示等待行。 */
    @Test
    fun `finished history does not hide placeholder`() {
        val finished = ChatMessage(
            id = "a0",
            role = Role.ASSISTANT,
            kind = ChatMessage.Kind.TEXT,
            text = "上一轮的回答",
            streaming = false,
        )
        assertTrue(needsAwaitingPlaceholder(listOf(user("u0"), finished, user()), running = true))
    }
}