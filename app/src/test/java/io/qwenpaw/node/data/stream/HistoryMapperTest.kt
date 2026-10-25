package io.qwenpaw.node.data.stream

import io.qwenpaw.node.data.model.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史消息映射测试，使用本机真实返回结构。 */
class HistoryMapperTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun parse(text: String): List<MessageDto> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MessageDto.serializer()), text)

    @Test
    fun `maps user and assistant text`() {
        val raw = """
            [
              {"type":"message","role":"user","content":[{"type":"text","delta":false,"text":"你好"}],"status":"completed"},
              {"type":"message","role":"assistant","content":[{"type":"text","delta":false,"text":"你好，有什么可以帮你"}],"status":"completed"}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("你好", messages[0].text)
        assertEquals(Role.ASSISTANT, messages[1].role)
    }

    @Test
    fun `maps reasoning separately`() {
        val raw = """
            [
              {"type":"reasoning","role":"assistant","content":[{"type":"text","text":"思考中"}],"status":"completed"},
              {"type":"message","role":"assistant","content":[{"type":"text","text":"结论"}],"status":"completed"}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(ChatMessage.Kind.REASONING, messages[0].kind)
        assertEquals(ChatMessage.Kind.TEXT, messages[1].kind)
    }

    @Test
    fun `pairs tool call and output from history`() {
        val raw = """
            [
              {"type":"plugin_call","role":"assistant","content":[{"type":"data","data":{"call_id":"call_1","name":"execute_shell_command","arguments":"{\"command\": \"ls -la\"}"}}],"status":"completed"},
              {"type":"plugin_call_output","role":"tool","content":[{"type":"data","data":{"call_id":"call_1","name":"execute_shell_command","output":"总计 328"}}],"status":"completed"}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(1, messages.size)
        val tool = messages[0].tool
        assertNotNull(tool)
        assertEquals("execute_shell_command", tool!!.name)
        assertEquals("总计 328", tool.output)
        assertTrue(tool.done)
    }

    @Test
    fun `skips empty assistant message`() {
        val raw = """[{"type":"message","role":"assistant","content":[],"status":"completed"}]"""
        assertTrue(HistoryMapper.map(parse(raw)).isEmpty())
    }

    @Test
    fun `tolerates unknown message type`() {
        val raw = """[{"type":"future_type","role":"assistant","content":[{"type":"text","text":"兼容"}],"status":"completed"}]"""
        val messages = HistoryMapper.map(parse(raw))
        assertEquals("兼容", messages.single().text)
    }

    // 回归：服务端同一个 msg_id 会在历史中重复出现，重复的 LazyColumn key 会直接崩溃。
    @Test
    fun `deduplicates repeated msg id in history`() {
        val raw = """
            [
              {"id":"msg_dup","type":"reasoning","role":"assistant","content":[{"type":"text","text":"想一想"}],"status":"completed"},
              {"id":"msg_dup","type":"message","role":"assistant","content":[{"type":"text","text":"结论"}],"status":"completed"}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(2, messages.size)
        assertEquals(2, messages.map { it.id }.toSet().size)
        assertEquals("想一想", messages[0].text)
        assertEquals("结论", messages[1].text)
    }
}
