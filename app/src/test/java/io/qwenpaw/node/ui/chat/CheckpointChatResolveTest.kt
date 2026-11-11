package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatSpecDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 检查点会话标识到对话 id 的换算。
 *
 * 检查点侧的 `session_key` / `session_id` 来自 `history.db`，对话列表的 `id`
 * 是对话 id；两者混用会让只读页打开错误的对话，或干脆打不开。
 */
class CheckpointChatResolveTest {

    @Test
    fun `chat id wins when it matches directly`() {
        val chats = listOf(
            ChatSpecDto(id = "chat-1", sessionId = "sess-a"),
            ChatSpecDto(id = "chat-2", sessionId = "sess-b"),
        )
        assertEquals("chat-1", resolveChatId(chats, "chat-1"))
    }

    @Test
    fun `session id maps to chat id`() {
        val chats = listOf(ChatSpecDto(id = "chat-2", sessionId = "sess-b"))
        assertEquals("chat-2", resolveChatId(chats, "sess-b"))
    }

    /** 两者都不命中时原样返回，`openChat` 会把它当空会话处理。 */
    @Test
    fun `unknown value passes through`() {
        val chats = listOf(ChatSpecDto(id = "chat-1", sessionId = "sess-a"))
        assertEquals("missing", resolveChatId(chats, "missing"))
    }

    @Test
    fun `empty chat list passes through`() {
        assertEquals("sess-x", resolveChatId(emptyList(), "sess-x"))
    }
}
