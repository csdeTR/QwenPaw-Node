package io.qwenpaw.node.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 增量折叠测试：单条消息、思考、工具配对、乱序容错。 */
class StreamAccumulatorTest {

    @Test
    fun `merges deltas into one message`() {
        val acc = StreamAccumulator()
        acc.onMessageBlock(ChatEvent.MessageBlock("m1", "message", "in_progress", 1))
        acc.onDelta(ChatEvent.TextDelta("m1", 0, "你好"))
        acc.onDelta(ChatEvent.TextDelta("m1", 0, "，世界"))

        val messages = acc.snapshot()
        assertEquals(1, messages.size)
        assertEquals("你好，世界", messages[0].text)
        assertTrue(messages[0].streaming)
    }

    @Test
    fun `keeps reasoning and answer separate`() {
        val acc = StreamAccumulator()
        acc.onMessageBlock(ChatEvent.MessageBlock("r1", "reasoning", "in_progress", 1))
        acc.onDelta(ChatEvent.TextDelta("r1", 0, "先想一想"))
        acc.onMessageBlock(ChatEvent.MessageBlock("m1", "message", "in_progress", 2))
        acc.onDelta(ChatEvent.TextDelta("m1", 0, "答案"))

        val messages = acc.snapshot()
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Kind.REASONING, messages[0].kind)
        assertEquals(ChatMessage.Kind.TEXT, messages[1].kind)
    }

    @Test
    fun `delta without preceding block still creates message`() {
        val acc = StreamAccumulator()
        acc.onDelta(ChatEvent.TextDelta("unknown", 0, "孤儿增量"))

        val messages = acc.snapshot()
        assertEquals(1, messages.size)
        assertEquals("孤儿增量", messages[0].text)
    }

    @Test
    fun `pairs tool call with output by call id`() {
        val acc = StreamAccumulator()
        acc.onToolCall(ChatEvent.ToolCall("call_1", "execute_shell_command", "{\"command\":\"ls\"}", 1))
        acc.onToolOutput(ChatEvent.ToolOutput("call_1", "execute_shell_command", "file.txt", 2))

        val messages = acc.snapshot()
        assertEquals(1, messages.size)
        val tool = messages[0].tool!!
        assertEquals("execute_shell_command", tool.name)
        assertEquals("file.txt", tool.output)
        assertTrue(tool.done)
    }

    @Test
    fun `tool output without call creates standalone card`() {
        val acc = StreamAccumulator()
        acc.onToolOutput(ChatEvent.ToolOutput("call_x", "tool", "输出", 1))

        val messages = acc.snapshot()
        assertEquals(1, messages.size)
        assertEquals("输出", messages[0].tool?.output)
    }

    @Test
    fun `duplicate tool call does not create extra card`() {
        val acc = StreamAccumulator()
        acc.onToolCall(ChatEvent.ToolCall("c", "t", "a", 1))
        acc.onToolCall(ChatEvent.ToolCall("c", "t", "b", 2))

        assertEquals(1, acc.snapshot().size)
        assertEquals("b", acc.snapshot()[0].tool?.arguments)
    }

    @Test
    fun `finish clears streaming flags`() {
        val acc = StreamAccumulator()
        acc.onMessageBlock(ChatEvent.MessageBlock("m", "message", "in_progress", 1))
        acc.onDelta(ChatEvent.TextDelta("m", 0, "x"))
        acc.finish()

        assertFalse(acc.snapshot()[0].streaming)
    }

    @Test
    fun `user message is appended with unique ids`() {
        val acc = StreamAccumulator()
        val a = acc.appendUser("hi")
        val b = acc.appendUser("hi again")

        assertTrue(a.id != b.id)
        assertEquals(2, acc.snapshot().count { it.role == Role.USER })
    }

    @Test
    fun `error message is rendered as text with error flag`() {
        val acc = StreamAccumulator()
        acc.appendError("网络异常")

        val message = acc.snapshot().single()
        assertEquals("网络异常", message.error)
        assertTrue(message.text.isEmpty())
    }

    @Test
    fun `interleaved messages keep order`() {
        val acc = StreamAccumulator()
        acc.onMessageBlock(ChatEvent.MessageBlock("m1", "message", "in_progress", 1))
        acc.onDelta(ChatEvent.TextDelta("m1", 0, "A"))
        acc.onToolCall(ChatEvent.ToolCall("c1", "t", "", 2))
        acc.onMessageBlock(ChatEvent.MessageBlock("m2", "message", "in_progress", 3))
        acc.onDelta(ChatEvent.TextDelta("m2", 0, "B"))

        val kinds = acc.snapshot().map { it.kind }
        assertEquals(
            listOf(ChatMessage.Kind.TEXT, ChatMessage.Kind.TOOL, ChatMessage.Kind.TEXT),
            kinds,
        )
    }

    @Test
    fun `error keeps original text for retry and can be removed`() {
        val acc = StreamAccumulator()
        acc.appendUser("在吗")
        acc.appendError("网络中断", retryText = "在吗")

        val error = acc.snapshot().last()
        assertEquals("在吗", error.retryText)
        assertNotNull(error.error)

        acc.removeError(error.id)
        assertTrue(acc.snapshot().none { it.id == error.id })
    }

    /** 建会话失败时要把刚回显的用户气泡撤掉，否则重试会画出两条相同的消息。 */
    @Test
    fun `rollback removes echoed user messages`() {
        val acc = StreamAccumulator()
        val first = acc.appendUser("第一条")
        val second = acc.appendUser("第二条")

        acc.rollback(listOf(second.id))

        assertEquals(listOf(first.id), acc.snapshot().map { it.id })
    }

    /** 撤掉不存在的 id 不应改变列表，也不应抛异常。 */
    @Test
    fun `rollback with unknown id is a no-op`() {
        val acc = StreamAccumulator()
        val kept = acc.appendUser("保留")

        acc.rollback(listOf("local-user-999"))

        assertEquals(1, acc.snapshot().size)
        assertEquals(kept.id, acc.snapshot()[0].id)
    }
}
