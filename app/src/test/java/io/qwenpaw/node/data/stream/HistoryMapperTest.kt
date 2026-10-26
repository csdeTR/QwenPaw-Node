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

    // 图片附件历史上会被内联成 data URI，映射后应成为 IMAGE 单元。
    @Test
    fun `maps inline base64 image block`() {
        val raw = """
            [
              {"id":"m1","type":"message","role":"user","content":[
                {"type":"text","text":"这张图什么颜色"},
                {"type":"image","image_url":"data:image/png;base64,iVBORw0KGgo="}
              ],"status":"completed"}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Kind.TEXT, messages[0].kind)
        assertEquals(ChatMessage.Kind.IMAGE, messages[1].kind)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", messages[1].imageUrl)
        assertEquals(Role.USER, messages[1].role)
    }

    // 老协议把本地路径放在 source.url，也要能取到。
    @Test
    fun `maps image url from legacy source field`() {
        val raw = """
            [{"id":"m2","type":"message","role":"assistant","content":[
              {"type":"image","source":{"type":"url","url":"http://host/a.png"}}
            ],"status":"completed"}]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(1, messages.size)
        assertEquals(ChatMessage.Kind.IMAGE, messages[0].kind)
        assertEquals("http://host/a.png", messages[0].imageUrl)
    }

    // 文件附件降级成一行文字，避免正文被整块吞掉。
    @Test
    fun `renders file attachment as text hint`() {
        val raw = """
            [{"id":"m3","type":"message","role":"user","content":[
              {"type":"file","file_url":"http://host/report.pdf","filename":"report.pdf"}
            ],"status":"completed"}]
        """.trimIndent()
        val messages = HistoryMapper.map(parse(raw))

        assertEquals(1, messages.size)
        assertEquals(ChatMessage.Kind.TEXT, messages[0].kind)
        assertTrue(messages[0].text.contains("report.pdf"))
    }
}
