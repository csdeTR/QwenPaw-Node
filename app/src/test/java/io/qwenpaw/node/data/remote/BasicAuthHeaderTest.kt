package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.local.InstanceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 反向代理的 HTTP Basic 支持。
 *
 * 关键冲突：nginx 的 `auth_basic` 读 `Authorization: Basic …`，而 QwenPaw 自身读
 * `Authorization: Bearer …`，一个请求只能带一个值。因此两者同时存在时把
 * `Authorization` 让给 Basic，token 改走服务端支持的 `?token=`。
 *
 * 这组断言直接抓报文：漏改任何一处（REST / SSE / 图片）都表现为
 * 「文字能出来、图片一直转圈」或「发消息 401」。
 */
class BasicAuthHeaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = "http://${server.hostName}:${server.port}"

    private fun enqueueOk(body: String = "{}") {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    /** `user:password` 的 Base64，UTF-8 编码后与标准实现一致。 */
    @Test
    fun `basic header encodes user and password`() {
        assertEquals("Basic dXNlcjpwYXNz", BasicAuth("user", "pass").headerValue)
    }

    /** 中文与冒号必须按 UTF-8 编码；按平台默认字符集会得到另一种字节序列。 */
    @Test
    fun `basic header handles non ascii and colons`() {
        val value = BasicAuth("账号", "a:b").headerValue
        val decoded = String(
            java.util.Base64.getDecoder().decode(value.removePrefix("Basic ")),
            Charsets.UTF_8,
        )
        assertEquals("账号:a:b", decoded)
    }

    /** 只有 Basic、没有 token：Authorization 给 Basic，不带 `token=`。 */
    @Test
    fun `basic only sets authorization header`() {
        val api = QwenPawApi()
        enqueueOk("""{"version":"2.2.2"}""")
        val conn = Connection(baseUrl(), token = null, basicAuth = BasicAuth("proxy", "pw"))
        api.agents(conn).let { /* 请求已发出 */ }

        val request = server.takeRequest()
        assertEquals("Basic cHJveHk6cHc=", request.getHeader("Authorization"))
        assertNull(request.requestUrl?.queryParameter("token"))
    }

    /** Basic 与 token 同时存在：Authorization 让给 Basic，token 进查询参数。 */
    @Test
    fun `token moves to query when basic is present`() {
        val api = QwenPawApi()
        enqueueOk("""{"agents":[]}""")
        val conn = Connection(baseUrl(), token = "tk", basicAuth = BasicAuth("proxy", "pw"))
        api.agents(conn)

        val request = server.takeRequest()
        assertEquals("Basic cHJveHk6cHc=", request.getHeader("Authorization"))
        assertEquals("tk", request.requestUrl?.queryParameter("token"))
    }

    /** 没有 Basic 时仍走 Bearer，token 不出现在 URL 里（避免被反代日志记录）。 */
    @Test
    fun `bearer is kept when no basic`() {
        val api = QwenPawApi()
        enqueueOk("""{"agents":[]}""")
        api.agents(Connection(baseUrl(), token = "tk"))

        val request = server.takeRequest()
        assertEquals("Bearer tk", request.getHeader("Authorization"))
        assertNull(request.requestUrl?.queryParameter("token"))
    }

    /** SSE 与 REST 共用同一套头，否则「消息能发、流式不回」。 */
    @Test
    fun `stream request carries the same headers`() {
        val api = QwenPawApi()
        val conn = Connection(baseUrl(), token = "tk", basicAuth = BasicAuth("proxy", "pw"))
        val request = api.chatRequest(conn, "{}")

        assertEquals("Basic cHJveHk6cHc=", request.header("Authorization"))
        assertEquals("tk", request.url.queryParameter("token"))
    }

    /** 只有两个字段都非空才算配了反代；只填账号按未配置处理。 */
    @Test
    fun `instance requires both fields`() {
        val base = InstanceConfig(id = "1", name = "n", agentId = "default")
        assertNull(base.copy(basicUser = "u").basicAuth)
        assertNull(base.copy(basicPassword = "p").basicAuth)
        assertNotNull(base.copy(basicUser = "u", basicPassword = "p").basicAuth)
    }
}