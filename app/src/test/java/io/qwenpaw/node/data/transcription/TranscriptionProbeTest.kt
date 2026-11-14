package io.qwenpaw.node.data.transcription

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 转写可用性的判定。
 *
 * 这组用例锁住三条实测结论：
 * 1. `transcription_provider_type` 只是开关，`whisper_api` + 空
 *    `configured_provider_id` 时服务端会 500，不能当作可用；
 * 2. 404 才是「版本不支持」，401 是「没权限问」——后者在开启登录的服务端上必然出现；
 * 3. 连不上时保留上一次结论，网络抖动不该把「已就绪」翻成未配置。
 */
class TranscriptionProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var probe: TranscriptionProbe

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        probe = TranscriptionProbe(QwenPawApi())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun conn(token: String? = null) = Connection(
        baseUrl = "http://${server.hostName}:${server.port}",
        token = token,
    )

    private fun type(value: String) =
        MockResponse().setResponseCode(200)
            .setBody("""{"transcription_provider_type":"$value"}""")

    private fun providers(configured: String, listed: Int = 2) {
        val items = (1..listed).joinToString(",") {
            """{"id":"p$it","name":"P$it","available":true}"""
        }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"providers":[$items],"configured_provider_id":"$configured"}"""),
        )
    }

    private fun whisperStatus(available: Boolean) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"available":$available,"ffmpeg_installed":$available,"whisper_installed":$available}""",
            ),
        )
    }

    @Test
    fun `disabled is not usable`() = runBlocking {
        server.enqueue(type("disabled"))
        val status = probe.probe("i1", conn())
        assertEquals(TranscriptionStatus.Disabled, status)
        assertFalse(status.canRecord)
        assertEquals("服务端未启用语音转写", status.label)
    }

    /** 开关打开但没选提供商：服务端会在转写时 500，这里必须提前判成不可用。 */
    @Test
    fun `whisper api without configured provider is misconfigured`() = runBlocking {
        server.enqueue(type("whisper_api"))
        providers(configured = "", listed = 3)
        val status = probe.probe("i1", conn())
        assertEquals(TranscriptionStatus.Misconfigured, status)
        assertFalse(status.canRecord)
    }

    /** 有提供商只是「有能力」，真正生效要看 configured_provider_id。 */
    @Test
    fun `whisper api with configured provider is ready`() = runBlocking {
        server.enqueue(type("whisper_api"))
        providers(configured = "qwenpaw-local")
        val status = probe.probe("i1", conn())
        assertEquals(TranscriptionStatus.Ready, status)
        assertTrue(status.canRecord)
    }

    @Test
    fun `local whisper depends on installed dependencies`() = runBlocking {
        server.enqueue(type("local_whisper"))
        whisperStatus(available = false)
        assertEquals(TranscriptionStatus.Misconfigured, probe.probe("i1", conn()))
    }

    @Test
    fun `local whisper ready when dependencies installed`() = runBlocking {
        server.enqueue(type("local_whisper"))
        whisperStatus(available = true)
        assertEquals(TranscriptionStatus.Ready, probe.probe("i1", conn()))
    }

    /** 404 是「这个服务端版本没有该端点」，要升级服务端而不是改配置。 */
    @Test
    fun `404 means unsupported`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        assertEquals(TranscriptionStatus.Unsupported, probe.probe("i1", conn()))
    }

    /** 401 只说明「现在问不了」：验证阶段没有 token 时必然出现。 */
    @Test
    fun `401 stays unknown`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"Not authenticated"}"""),
        )
        assertEquals(TranscriptionStatus.Unknown, probe.probe("i1", conn(null)))
    }

    /** 连不上时保留上一次结论：网络抖动不该让可用变成未配置。 */
    @Test
    fun `io failure keeps previous conclusion`() = runBlocking {
        server.enqueue(type("whisper_api"))
        providers(configured = "p1")
        assertEquals(TranscriptionStatus.Ready, probe.probe("i1", conn()))
        // 直接关掉服务端，拿一个确定连不上的地址。用 DISCONNECT_AT_START 不够：
        // OkHttp 的 retryOnConnectionFailure 会重发，队列空时拿到的是空响应而不是 IOException。
        val dead = MockWebServer()
        dead.start()
        val deadConn = Connection("http://${dead.hostName}:${dead.port}", null)
        dead.shutdown()
        assertEquals(TranscriptionStatus.Ready, probe.probe("i1", deadConn))
    }

    /** 按实例与智能体缓存，换实例不能串。 */
    @Test
    fun `cache is per instance and agent`() = runBlocking {
        server.enqueue(type("disabled"))
        probe.probe("i1", conn())
        assertEquals(TranscriptionStatus.Disabled, probe.cached("i1", "default"))
        assertEquals(TranscriptionStatus.Unknown, probe.cached("i2", "default"))
        assertEquals(TranscriptionStatus.Unknown, probe.cached("i1", "other"))
    }

    /** 探测顺序与路径：先读开关，再按开关读对应配置。 */
    @Test
    fun `probe reads provider type before providers`() = runBlocking {
        server.enqueue(type("whisper_api"))
        providers(configured = "p1")
        probe.probe("i1", conn())
        assertEquals(
            "/api/workspace/transcription-provider-type",
            server.takeRequest().requestUrl?.encodedPath,
        )
        assertEquals(
            "/api/workspace/transcription-providers",
            server.takeRequest().requestUrl?.encodedPath,
        )
    }
}