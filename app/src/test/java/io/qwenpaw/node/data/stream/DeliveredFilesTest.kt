package io.qwenpaw.node.data.stream

import io.qwenpaw.node.data.model.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `send_file_to_user` 交付文件的解析。
 *
 * 服务端没有「已交付文件」接口：文件字节以 base64 内联在工具输出的 JSON 字符串里，
 * 文件名只能从配对的 `plugin_call` 参数取。结构来自本机服务端的真实返回。
 */
class DeliveredFilesTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun parseHistory(text: String): List<MessageDto> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MessageDto.serializer()), text)

    private val pngArguments = """{"file_path": "/home/u/workspaces/demo/a/b/sample.png"}"""

    private fun outputWith(data: String) =
        """[{"type":"data","id":"744e","source":{"type":"base64","data":"$data"}}]"""

    @Test
    fun `image output becomes a data uri`() {
        val files = DeliveredFiles.parse(pngArguments, outputWith("iVBORw0KGgoAAAANSUhEUg=="))

        assertEquals(1, files.size)
        val file = files[0]
        assertEquals("sample.png", file.name)
        assertEquals("/home/u/workspaces/demo/a/b/sample.png", file.path)
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==", file.dataUri)
        assertTrue(file.isImage)
    }

    @Test
    fun `non image keeps name and path but drops the bytes`() {
        val args = """{"file_path": "/tmp/report.zip"}"""
        val files = DeliveredFiles.parse(args, outputWith("UEsDBAoAAAAAAA=="))

        assertEquals(1, files.size)
        assertEquals("report.zip", files[0].name)
        assertNull(files[0].dataUri)
        assertTrue(!files[0].isImage)
    }

    @Test
    fun `path falls back to the path field`() {
        val files = DeliveredFiles.parse("""{"path":"/tmp/x.jpg"}""", outputWith("AAAA"))
        assertEquals("x.jpg", files.single().name)
    }

    @Test
    fun `missing arguments or output yields nothing`() {
        assertTrue(DeliveredFiles.parse("", outputWith("AAAA")).isEmpty())
        assertTrue(DeliveredFiles.parse("{}", outputWith("AAAA")).isEmpty())
        assertTrue(DeliveredFiles.parse(pngArguments, null).isEmpty())
        assertTrue(DeliveredFiles.parse(pngArguments, "not json").isEmpty())
        assertTrue(DeliveredFiles.parse(pngArguments, "[]").isEmpty())
    }

    @Test
    fun `non base64 source is ignored`() {
        val files = DeliveredFiles.parse(
            pngArguments,
            """[{"type":"data","source":{"type":"url","url":"http://x/y.png"}}]""",
        )
        assertTrue(files.isEmpty())
    }

    @Test
    fun `history pairs call and output and drops the raw base64`() {
        val raw = """
            [
              {"type":"plugin_call","role":"assistant","content":[{"type":"data","data":{"call_id":"call_1","name":"send_file_to_user","arguments":"{\"file_path\": \"/tmp/x.png\"}"}}],"status":"completed"},
              {"type":"plugin_call_output","role":"tool","content":[{"type":"data","data":{"call_id":"call_1","name":"send_file_to_user","output":"[{\"type\":\"data\",\"source\":{\"type\":\"base64\",\"data\":\"AAAA\"}}]"}}],"status":"completed"}
            ]
        """.trimIndent()
        val messages = HistoryMapper.map(parseHistory(raw))

        assertEquals(1, messages.size)
        val tool = messages[0].tool!!
        assertEquals("send_file_to_user", tool.name)
        assertEquals(1, tool.delivered.size)
        assertEquals("x.png", tool.delivered[0].name)
        // 几十 MB 的 base64 不应留在界面状态里。
        assertNull(tool.output)
        assertTrue(tool.done)
    }

    @Test
    fun `streaming keeps delivered files on the tool card`() {
        val acc = StreamAccumulator()
        acc.onToolCall(
            ChatEvent.ToolCall("c1", "send_file_to_user", """{"file_path":"/tmp/y.png"}""", 1),
        )
        acc.onToolOutput(
            ChatEvent.ToolOutput(
                "c1",
                "send_file_to_user",
                "[{\"type\":\"data\",\"source\":{\"type\":\"base64\",\"data\":\"AAAA\"}}]",
                2,
            ),
        )

        val tool = acc.snapshot().single().tool!!
        assertEquals(1, tool.delivered.size)
        assertEquals("y.png", tool.delivered[0].name)
        assertNull(tool.output)
    }

    @Test
    fun `other tools keep their output untouched`() {
        val acc = StreamAccumulator()
        acc.onToolCall(ChatEvent.ToolCall("c2", "execute_shell_command", "{}", 1))
        acc.onToolOutput(ChatEvent.ToolOutput("c2", "execute_shell_command", "file.txt", 2))

        val tool = acc.snapshot().single().tool!!
        assertEquals("file.txt", tool.output)
        assertTrue(tool.delivered.isEmpty())
    }
}