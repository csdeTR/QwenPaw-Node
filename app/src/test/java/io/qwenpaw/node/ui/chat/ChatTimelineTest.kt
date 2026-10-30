package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.DeliveredFile
import io.qwenpaw.node.data.stream.Role
import io.qwenpaw.node.data.stream.ToolCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话列表分组与长消息折叠的测试。
 *
 * 这两块是纯函数，不需要 Compose 运行时，直接断言输入输出。
 */
class ChatTimelineTest {

    private fun textMessage(id: String, role: Role, text: String) = ChatMessage(
        id = id,
        role = role,
        kind = ChatMessage.Kind.TEXT,
        text = text,
    )

    private fun reasoning(id: String, text: String = "想一想") = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.REASONING,
        text = text,
    )

    private fun tool(id: String, callId: String, name: String, done: Boolean = true) = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TOOL,
        tool = ToolCard(callId = callId, name = name, arguments = "{}", output = "ok", done = done),
    )

    @Test
    fun `默认折叠结果时一个回合只留最后一条正文`() {
        val messages = listOf(
            textMessage("u1", Role.USER, "帮我看看"),
            reasoning("r1"),
            tool("t1", "c1", "shell"),
            textMessage("a1", Role.ASSISTANT, "先说明一下"),
            reasoning("r2"),
            tool("t2", "c2", "read_file"),
            textMessage("a2", Role.ASSISTANT, "看完了"),
        )
        val items = buildItems(messages, AssistantCollapse.RESULT_COLLAPSED)

        // 用户消息 + 一组步骤 + 最后一条正文。
        assertEquals(3, items.size)
        assertEquals("u1", (items[0] as ChatItem.Single).message.id)
        val steps = items[1] as ChatItem.Steps
        // 中间正文也折进组里，最近的正文留在外面。
        assertEquals(listOf("r1", "t1", "a1", "r2", "t2"), steps.steps.map { it.id })
        assertEquals("a2", (items[2] as ChatItem.Single).message.id)
        // 计数只算思考与工具，不含中间正文。
        assertEquals(4, countSteps(steps.steps))
    }

    @Test
    fun `折叠过程时正文全部留在外面`() {
        val messages = listOf(
            textMessage("u1", Role.USER, "帮我看看"),
            reasoning("r1"),
            tool("t1", "c1", "shell"),
            textMessage("a1", Role.ASSISTANT, "第一段"),
            reasoning("r2"),
        )
        val items = buildItems(messages, AssistantCollapse.PROCESS_COLLAPSED)

        assertEquals(4, items.size)
        assertEquals("u1", (items[0] as ChatItem.Single).message.id)
        assertEquals(listOf("r1", "t1"), (items[1] as ChatItem.Steps).steps.map { it.id })
        assertEquals("a1", (items[2] as ChatItem.Single).message.id)
        assertEquals(listOf("r2"), (items[3] as ChatItem.Steps).steps.map { it.id })
    }

    @Test
    fun `全部展开时不产生分组`() {
        val messages = listOf(
            reasoning("r1"),
            tool("t1", "c1", "shell"),
            textMessage("a1", Role.ASSISTANT, "结论"),
        )
        val items = buildItems(messages, AssistantCollapse.EXPANDED)

        assertEquals(3, items.size)
        assertTrue(items.all { it is ChatItem.Single })
    }

    @Test
    fun `用户消息间隔会切成不同回合`() {
        val messages = listOf(
            textMessage("u1", Role.USER, "第一问"),
            textMessage("a1", Role.ASSISTANT, "第一答"),
            textMessage("u2", Role.USER, "第二问"),
            textMessage("a2", Role.ASSISTANT, "第二答"),
        )
        val items = buildItems(messages, AssistantCollapse.RESULT_COLLAPSED)

        // 每个回合各留一条正文，不该把两个回合的最后一条合成一条。
        assertEquals(listOf("u1", "a1", "u2", "a2"), items.map { it.key })
    }

    @Test
    fun `没有正文的回合整段折进步骤组`() {
        val messages = listOf(
            textMessage("u1", Role.USER, "跑一下"),
            reasoning("r1"),
            tool("t1", "c1", "shell"),
        )
        val items = buildItems(messages, AssistantCollapse.RESULT_COLLAPSED)

        assertEquals(2, items.size)
        assertEquals("u1", (items[0] as ChatItem.Single).message.id)
        assertEquals(2, (items[1] as ChatItem.Steps).steps.size)
    }

    @Test
    fun `出错的消息不会被折进步骤组`() {
        val failure = ChatMessage(
            id = "e1",
            role = Role.ASSISTANT,
            kind = ChatMessage.Kind.TEXT,
            error = "连接失败",
            retryText = "重试内容",
        )
        val messages = listOf(
            textMessage("u1", Role.USER, "跑一下"),
            reasoning("r1"),
            failure,
        )
        val items = buildItems(messages, AssistantCollapse.RESULT_COLLAPSED)

        // 否则用户看不到失败原因，也点不到「重试」。
        assertEquals(3, items.size)
        assertEquals("e1", (items[2] as ChatItem.Single).message.id)
    }

    @Test
    fun `步骤组的 key 用首条 id 且保持稳定`() {
        val first = buildItems(listOf(reasoning("r1"), tool("t1", "c1", "shell")), AssistantCollapse.PROCESS_COLLAPSED)
        val grown = buildItems(
            listOf(reasoning("r1"), tool("t1", "c1", "shell"), tool("t2", "c2", "read")),
            AssistantCollapse.PROCESS_COLLAPSED,
        )
        // 步骤变多后 key 不变，LazyColumn 不会重建整个分组。
        assertEquals(first[0].key, grown[0].key)
    }

    @Test
    fun `空列表产出空结果`() {
        AssistantCollapse.entries.forEach { mode ->
            assertTrue(buildItems(emptyList(), mode).isEmpty())
        }
    }

    @Test
    fun `窗口只渲染指定下标之后的条目`() {
        val items = buildItems((1..20).map { textMessage("m$it", Role.USER, "第 $it 条") }, AssistantCollapse.EXPANDED)
        val tail = buildTimeline(items, 12)
        assertEquals(8, tail.size)
        assertEquals("m13", (tail[0] as ChatItem.Single).message.id)

        // start 越界时按边界收敛，不抛异常。
        assertEquals(20, buildTimeline(items, -5).size)
        assertTrue(buildTimeline(items, 999).isEmpty())
    }

    @Test
    fun `窗口按条目而非原始消息分页`() {
        // 一条用户消息后面跟 30 条工具步骤，折叠后只算 2 个条目。
        val messages = listOf(textMessage("u1", Role.USER, "跑一下")) +
            (1..30).map { tool("t$it", "c$it", "shell") }
        val items = buildItems(messages, AssistantCollapse.PROCESS_COLLAPSED)
        assertEquals(2, items.size)
        // 窗口也应按条目下标切分，而不是被 30 条原始消息挤成一页一条。
        assertEquals(2, buildTimeline(items, 0).size)
    }

    @Test
    fun `短消息不折叠`() {
        assertFalse(isLongUserMessage("就一句话"))
        assertFalse(isLongUserMessage((1..6).joinToString("\n") { "第 $it 行" }))
    }

    @Test
    fun `行数超限的长消息要折叠`() {
        val text = (1..9).joinToString("\n") { "第 $it 行" }
        assertTrue(isLongUserMessage(text))
        val preview = collapsedUserPreview(text)
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.lines().size <= 7)
    }

    @Test
    fun `单行但超长的消息也要折叠`() {
        val text = "x".repeat(600)
        assertTrue(isLongUserMessage(text))
        assertTrue(collapsedUserPreview(text).endsWith("…"))
    }

    @Test
    fun `折叠预览保留开头内容`() {
        val text = (1..9).joinToString("\n") { "第 $it 行" }
        val preview = collapsedUserPreview(text)
        assertTrue(preview.startsWith("第 1 行"))
        assertTrue(preview.contains("第 6 行"))
    }

    /**
     * 交付的文件必须留在折叠之外，并排在该回合末尾。
     *
     * 折叠结果模式下过程会收成「处理完成 · N 步」，而交付文件是用户要拿去用的结果，
     * 被折进去等于看不见——官方 Console 也是把它们单独摊开。
     */
    @Test
    fun `交付的文件单独成条并排在回合末尾`() {
        val tool = ChatMessage(
            id = "t1",
            role = Role.ASSISTANT,
            kind = ChatMessage.Kind.TOOL,
            tool = ToolCard(
                callId = "c1",
                name = "send_file_to_user",
                arguments = "{}",
                delivered = listOf(
                    DeliveredFile("a.png", "/tmp/a.png", "data:image/png;base64,AA"),
                ),
            ),
        )
        val items = buildItems(
            listOf(
                textMessage("u1", Role.USER, "发我"),
                tool,
                textMessage("a1", Role.ASSISTANT, "给你"),
            ),
            AssistantCollapse.RESULT_COLLAPSED,
        )

        assertTrue(items.last() is ChatItem.Files)
        assertEquals("files:t1", items.last().key)
        assertEquals("给你", (items[items.size - 2] as ChatItem.Single).message.text)
        assertEquals(1, (items.last() as ChatItem.Files).files.size)
    }

    /** 同一张图交付两次时只留一条，避免卡片重复。 */
    @Test
    fun `重复交付的同一路径只保留一条`() {
        fun tool(id: String) = ChatMessage(
            id = id,
            role = Role.ASSISTANT,
            kind = ChatMessage.Kind.TOOL,
            tool = ToolCard(
                callId = id,
                name = "send_file_to_user",
                arguments = "{}",
                delivered = listOf(DeliveredFile("a.png", "/tmp/a.png", null)),
            ),
        )
        val items = buildItems(
            listOf(textMessage("u1", Role.USER, "发我"), tool("t1"), tool("t2")),
            AssistantCollapse.RESULT_COLLAPSED,
        )

        assertEquals(1, (items.last() as ChatItem.Files).files.size)
    }
}