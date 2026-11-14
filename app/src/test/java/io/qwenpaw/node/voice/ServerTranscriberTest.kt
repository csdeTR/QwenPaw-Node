package io.qwenpaw.node.voice

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 转写请求的形状与失败原因映射。
 *
 * 三件事在这里锁住：
 * 1. 表单字段名是 `file`（不是文件上传用的 `files`），media type 是 `audio/wav`；
 * 2. 服务端在 provider 失败时回 500，那是服务端配置问题而不是网络问题，
 *    文案必须能指向「检查服务端的转写配置」；
 * 3. 无论成功失败，临时录音文件都要删掉——90 秒录音约 2.9MB，留在缓存里会累积。
 */
class ServerTranscriberTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var transcriber: ServerTranscriber

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transcriber = ServerTranscriber(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun conn() = Connection(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "tk",
        agentId = "agent-a",
    )

    /** 建一个最小的 WAV 文件（44 字节头 + 4 字节数据）。 */
    private fun wav(): File {
        val f = File.createTempFile("voice-", ".wav")
        f.writeBytes(WavWriter.header(16_000, 4) + byteArrayOf(0, 0, 1, 0))
        return f
    }

    @Test
    fun `posts wav as file field with agent header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"你好"}"""))
        val file = wav()

        val text = transcriber.transcribe(conn(), file)

        assertEquals("你好", text)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/workspace/transcribe", request.requestUrl?.encodedPath)
        assertEquals("agent-a", request.getHeader("X-Agent-Id"))
        val body = request.body.readUtf8()
        assertTrue("字段名应为 file", body.contains("name=\"file\"; filename=\"${file.name}\""))
        assertFalse("不应使用 file-upload 的 files 字段", body.contains("name=\"files\""))
        assertTrue("media type 应为 audio/wav", body.contains("Content-Type: audio/wav"))
    }

    /** 成功路径也要删文件：缓存目录不能因为一次成功就留下 3MB。 */
    @Test
    fun `deletes temp file on success`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
        val file = wav()
        transcriber.transcribe(conn(), file)
        assertFalse(file.exists())
    }

    @Test
    fun `deletes temp file on failure`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val file = wav()
        runCatching { transcriber.transcribe(conn(), file) }
        assertFalse(file.exists())
    }

    /** `disabled` 与不支持的后缀都是 400 + 对象 detail，两者的处置完全不同。 */
    @Test
    fun `maps disabled and unsupported type to distinct messages`() {
        assertEquals(
            "服务端未启用语音转写",
            failureMessageWith(
                """{"detail":{"code":"TRANSCRIPTION_DISABLED","message":"x"}}""",
            ),
        )
        assertEquals(
            "服务端不接受这种录音格式",
            failureMessageWith(
                """{"detail":{"code":"UNSUPPORTED_FILE_TYPE","message":"x"}}""",
            ),
        )
    }

    /** 500 是服务端 provider 的问题，不能报成网络故障。 */
    @Test
    fun `maps provider failure to server config hint`() {
        val message = failureMessageWith(
            """{"detail":"Transcription failed. Check provider configuration."}""",
            code = 500,
        )
        assertEquals("服务端转写失败，请检查服务端的转写配置", message)
    }

    @Test
    fun `maps auth and missing endpoint`() {
        assertEquals("登录已失效，请重新连接后再试", failureMessageWith("""{"detail":"x"}""", code = 401))
        assertEquals("当前服务端版本不支持语音转写", failureMessageWith("""{"detail":"x"}""", code = 404))
    }

    /** 空文本按失败处理：录了一段却没拿到字，把空串填进输入框等于什么都没说。 */
    @Test
    fun `blank text is treated as failure`() {
        val message = failureMessageWith(body = null)
        assertTrue(message.contains("没有返回识别结果"))
    }

    /**
     * 发一次请求并返回失败文案。[body] 为 null 时使用成功响应，用于「响应成功但内容不可用」的情形。
     */
    private fun failureMessageWith(body: String?, code: Int = 400): String {
        if (body == null) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"   "}"""))
        } else {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        }
        val e = runCatching { transcriber.transcribe(conn(), wav()) }.exceptionOrNull()
        assertTrue("应抛出 TranscriptionException，实际是 $e", e is TranscriptionException)
        return (e as TranscriptionException).message
    }
}