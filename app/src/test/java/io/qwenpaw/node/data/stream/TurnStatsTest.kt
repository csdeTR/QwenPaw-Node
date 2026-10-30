package io.qwenpaw.node.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单轮用量的解析与挂载。
 *
 * 服务端把整轮用量挂在该轮**最后一条** assistant 消息上，因此有三件事必须成立：
 * 流式帧要能读到、历史 metadata 要能读到、界面上只能出现一次。
 */
class TurnStatsTest {

    // ---------------- 流式帧 ----------------

    /**
     * 流式帧里 `usage` 与 `context_usage` 平级，都是 `turn_usage` 的子键。
     *
     * `cache_hit_rate` 服务端给的是**百分数**（实测 74.99 表示 75%），
     * 解析后统一折成 0..1 的小数，避免界面与日志两处各折一次、结果不一致。
     */
    @Test
    fun `parses stats from streaming turn usage`() {
        val raw = """
            {"type":"turn_usage","session_id":"s",
             "usage":{"provider_id":"dashscope","model_name":"qwen3-max",
                      "prompt_tokens":13753,"completion_tokens":73,
                      "cache_read_tokens":11264,"cache_hit_rate":82.0},
             "context_usage":{"estimated_tokens":13815,"max_input_length":131072}}
        """.trimIndent().replace("\n", " ")
        val event = ChatEventParser.parse(raw) as ChatEvent.TurnUsage
        val stats = event.stats
        assertNotNull(stats)
        assertEquals(13753L, stats!!.promptTokens)
        assertEquals(73L, stats.completionTokens)
        assertEquals(11264L, stats.cacheReadTokens)
        assertEquals(0.82, stats.cacheHitRate, 1e-9)
        assertEquals("qwen3-max", stats.model)
        assertEquals("dashscope", stats.providerId)
    }

    /** 缺 `usage` 是正常情况（例如被打断），不能因此丢掉上下文比例。 */
    @Test
    fun `missing usage keeps context ratio`() {
        val raw = """{"type":"turn_usage","context_usage":{"estimated_tokens":100,"max_input_length":1000}}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.TurnUsage
        assertNull(event.stats)
        assertEquals(0.1, event.ratio, 1e-9)
    }

    /** 全零且无模型名不应被当成有效统计，否则界面会显示「输入 0 · 输出 0」。 */
    @Test
    fun `all zero usage is treated as absent`() {
        val raw = """{"type":"turn_usage","usage":{"prompt_tokens":0,"completion_tokens":0},"context_usage":{"estimated_tokens":1,"max_input_length":10}}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.TurnUsage
        assertNull(event.stats)
    }

    @Test
    fun `hasAny covers model only case`() {
        assertTrue(TurnStats(model = "qwen3-max").hasAny)
        assertTrue(TurnStats(promptTokens = 1).hasAny)
        assertTrue(!TurnStats().hasAny)
    }

    // ---------------- 流式累积 ----------------

    /**
     * 统计只挂到该轮最后一条正文上。
     *
     * 中间正文拿到统计会让界面上每段话下面都重复一遍同样的数字，
     * 而服务端的口径就是「整轮一次」。
     */
    @Test
    fun `finish attaches stats to last assistant text only`() {
        val acc = StreamAccumulator()
        acc.onMessageBlock(ChatEvent.MessageBlock("m1", "message", "completed", 1))
        acc.onDelta(ChatEvent.TextDelta("m1", 0, "第一段"))
        acc.onMessageBlock(ChatEvent.MessageBlock("m2", "message", "completed", 2))
        acc.onDelta(ChatEvent.TextDelta("m2", 0, "第二段"))
        acc.applyTurnStats(TurnStats(promptTokens = 100, completionTokens = 20, model = "qwen3-max"))
        acc.finish()

        val snapshot = acc.snapshot()
        assertEquals(2, snapshot.size)
        assertTrue(!snapshot[0].turnEnd)
        assertNull(snapshot[0].stats)
        assertTrue(snapshot[1].turnEnd)
        assertEquals(100L, snapshot[1].stats!!.promptTokens)
    }

    /** 没有正文（只有工具调用）时不该崩，也不该把统计挂在工具卡片上。 */
    @Test
    fun `finish without assistant text is safe`() {
        val acc = StreamAccumulator()
        acc.onToolCall(ChatEvent.ToolCall("c1", "shell", "{}", 1))
        acc.applyTurnStats(TurnStats(promptTokens = 10, completionTokens = 1))
        acc.finish()
        val snapshot = acc.snapshot()
        assertEquals(1, snapshot.size)
        assertNull(snapshot[0].stats)
    }

    // ---------------- 历史 metadata ----------------

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun parse(text: String): List<io.qwenpaw.node.data.model.MessageDto> =
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(
                io.qwenpaw.node.data.model.MessageDto.serializer(),
            ),
            text,
        )

    /** 历史接口的结构：`metadata.metadata.qwenpaw_turn_usage.usage`。 */
    @Test
    fun `maps stats from history metadata`() {
        val raw = """
            [
              {"id":"m1","type":"message","role":"assistant",
               "content":[{"type":"text","text":"回答"}],"status":"completed",
               "metadata":{"timestamp":"2026-10-29T02:13:41.029Z",
                           "finished_at":"2026-10-29T02:13:45.512Z",
                           "original_name":"Default Agent",
                           "metadata":{"qwenpaw_turn_usage":{"usage":{
                               "provider_id":"dashscope","model_name":"qwen3-max",
                               "prompt_tokens":13753,"completion_tokens":73,
                               "cache_read_tokens":11264,"cache_hit_rate":0.82}}}}}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))
        assertEquals(1, messages.size)
        val message = messages[0]
        assertEquals("2026-10-29T02:13:41.029Z", message.timestamp)
        assertEquals("2026-10-29T02:13:45.512Z", message.finishedAt)
        assertEquals("Default Agent", message.senderName)
        assertTrue(message.turnEnd)
        assertEquals(13753L, message.stats!!.promptTokens)
        assertEquals("qwen3-max", message.stats!!.model)
    }

    /** 中间消息没有这一层，属于常态，不应产生 stats 也不该标 turnEnd。 */
    @Test
    fun `message without usage has no stats`() {
        val raw = """[{"id":"m1","type":"message","role":"assistant","content":[{"type":"text","text":"片段"}],"status":"completed"}]"""
        val messages = HistoryMapper.map(parse(raw))
        assertNull(messages[0].stats)
        assertTrue(!messages[0].turnEnd)
    }

    /**
     * 同一条 dto 拆成文字 + 图片时，统计只该挂在文字上。
     * 挂在图片上等于气泡下面多一行数字而上面没有，位置很怪。
     */
    @Test
    fun `stats go to text unit when message has image`() {
        val raw = """
            [
              {"id":"m1","type":"message","role":"assistant",
               "content":[{"type":"text","text":"看图"},
                          {"type":"image","image_url":"http://x/a.png","filename":"a.png"}],
               "status":"completed",
               "metadata":{"metadata":{"qwenpaw_turn_usage":{"usage":{
                   "prompt_tokens":10,"completion_tokens":2,"model_name":"q"}}}}}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Kind.TEXT, messages[0].kind)
        assertNotNull(messages[0].stats)
        assertEquals(ChatMessage.Kind.IMAGE, messages[1].kind)
        assertNull(messages[1].stats)
    }

    /**
     * 实测：服务端把整轮的 usage 挂在**该轮每一条** assistant 消息上，
     * 不是只挂收尾那条。原样渲染会让一个多步回合里每段正文下面都重复同一组数字。
     */
    @Test
    fun `repeated usage per turn is collapsed to the closing message`() {
        val usage = """
            "metadata":{"qwenpaw_turn_usage":{"usage":{
                "prompt_tokens":13654,"completion_tokens":229,"model_name":"qwen3.8-flash"}}}
        """.trimIndent().replace("\n", "")
        val raw = """
            [
              {"id":"u1","type":"message","role":"user","content":[{"type":"text","text":"问题"}]},
              {"id":"r1","type":"reasoning","role":"assistant","content":[{"type":"text","text":"想想"}],
               "metadata":{$usage}},
              {"id":"m1","type":"message","role":"assistant","content":[{"type":"text","text":"中间回复"}],
               "metadata":{$usage}},
              {"id":"m2","type":"message","role":"assistant","content":[{"type":"text","text":"最终回复"}],
               "metadata":{$usage}}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))
        // 只有最后一条正文带统计与 turnEnd。
        assertEquals(1, messages.count { it.turnEnd })
        assertEquals(1, messages.count { it.stats != null })
        val closing = messages.filter { it.turnEnd }.single()
        assertEquals("最终回复", closing.text)
        assertEquals(13654L, closing.stats!!.promptTokens)
    }

    /** 两个回合各自收尾，互不影响。 */
    @Test
    fun `each turn gets its own closing message`() {
        fun turn(tag: String, p: Long) = """
            {"id":"m-$tag","type":"message","role":"assistant",
             "content":[{"type":"text","text":"$tag"}],
             "metadata":{"metadata":{"qwenpaw_turn_usage":{"usage":{
                 "prompt_tokens":$p,"completion_tokens":1}}}}}
        """.trimIndent().replace("\n", "")
        val raw = "[" + listOf(
            """{"id":"u1","type":"message","role":"user","content":[{"type":"text","text":"一"}]}""",
            turn("a", 100),
            """{"id":"u2","type":"message","role":"user","content":[{"type":"text","text":"二"}]}""",
            turn("b", 200),
        ).joinToString(",") + "]"
        val messages = HistoryMapper.map(parse(raw))
        val closings = messages.filter { it.turnEnd }
        assertEquals(2, closings.size)
        assertEquals(100L, closings[0].stats!!.promptTokens)
        assertEquals(200L, closings[1].stats!!.promptTokens)
    }

    /** 缓存命中率服务端给百分数，解析后必须折成 0..1。 */
    @Test
    fun `cache hit rate percent is converted to fraction`() {
        val raw = """
            [{"id":"m1","type":"message","role":"assistant",
              "content":[{"type":"text","text":"x"}],
              "metadata":{"metadata":{"qwenpaw_turn_usage":{"usage":{
                  "prompt_tokens":100,"completion_tokens":5,
                  "cache_hit_rate":74.99633806943021}}}}}]
        """.trimIndent()
        val stats = HistoryMapper.map(parse(raw)).single().stats!!
        assertEquals(0.75, stats.cacheHitRate, 0.001)
        assertTrue(!stats.cacheHitRate.isNaN())
    }
}