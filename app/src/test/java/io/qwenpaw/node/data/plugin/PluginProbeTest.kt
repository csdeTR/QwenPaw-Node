package io.qwenpaw.node.data.plugin

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
 * 配套插件探测的三态判定。
 *
 * 这组用例锁住三条容易弄反的规则：
 * 1. 404 才是「未安装」，401 是「没权限问」——后者在开启登录的服务端上必然出现，
 *    当成未安装会让用户永远看到「未检测到配套插件」；
 * 2. `protocol_version` 不等于 1 按未检测到处理，旧客户端不该继续用该插件的端点；
 * 3. 连不上时保留上一次的结论，网络抖动不该把「已安装」翻成「未安装」。
 */
class PluginProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var probe: PluginProbe
    private lateinit var api: QwenPawApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = QwenPawApi()
        probe = PluginProbe(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun conn(token: String? = null) = Connection(
        baseUrl = "http://${server.hostName}:${server.port}",
        token = token,
    )

    private fun info(
        version: String = "0.1.0",
        protocol: Int = 1,
        capabilities: String = """["info"]""",
    ): String = """
        {"plugin_version":"$version","protocol_version":$protocol,
         "server_version":"2.2.2.post1","capabilities":$capabilities}
    """.trimIndent()

    @Test
    fun `200 with protocol 1 is ready`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(info()))
        val status = probe.probe("i1", conn("tk"))
        assertTrue(status is BridgeStatus.Ready)
        assertEquals("已检测到 QwenPaw Node Bridge v0.1.0", status.label)
        assertTrue((status as BridgeStatus.Ready).info.supports("info"))
    }

    /** 404 是唯一可信的「没装」信号。 */
    @Test
    fun `404 means absent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        assertEquals(BridgeStatus.Absent, probe.probe("i1", conn("tk")))
    }

    /** 401 只说明「现在问不了」，不是「没装」。 */
    @Test
    fun `401 stays unknown`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"Not authenticated"}"""),
        )
        assertEquals(BridgeStatus.Unknown, probe.probe("i1", conn(null)))
    }

    /** 契约版本不兼容按未检测到处理：旧客户端不该继续用该插件的端点。 */
    @Test
    fun `incompatible protocol counts as absent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(info(protocol = 2)))
        assertEquals(BridgeStatus.Absent, probe.probe("i1", conn("tk")))
    }

    /** 探测结果按「实例 + 智能体」缓存，换智能体要各自独立。 */
    @Test
    fun `cache is per instance and agent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(info()))
        probe.probe("i1", conn("tk"))
        assertEquals("已检测到 QwenPaw Node Bridge v0.1.0", probe.cached("i1", "default").label)
        assertEquals(BridgeStatus.Unknown, probe.cached("i2", "default"))
        assertFalse(probe.cached("i1", "other").let { it is BridgeStatus.Ready })
    }

    /** 请求路径必须是插件自述端点，不能落到核心 router 的 /status 上。 */
    @Test
    fun `probe hits the info path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(info()))
        probe.probe("i1", conn("tk"))
        assertEquals(
            "/api/plugins/qwenpaw-node-bridge/info",
            server.takeRequest().requestUrl?.encodedPath,
        )
    }
}