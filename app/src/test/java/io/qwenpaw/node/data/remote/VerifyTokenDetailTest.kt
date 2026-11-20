package io.qwenpaw.node.data.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `GET /api/auth/verify` 的两种形态。
 *
 * 关键差异在反向代理：那条端点只从 `Authorization` 头里取 token，反代开启
 * `auth_basic` 时头让给了 Basic，令牌走 `?token=`，于是这条端点永远读不到。
 * 旧实现据此把反代实例上的有效令牌判成「已失效」。
 */
class VerifyTokenDetailTest {

    private lateinit var server: MockWebServer
    private lateinit var api: QwenPawApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = QwenPawApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun conn(token: String? = "tk", basic: BasicAuth? = null) = Connection(
        baseUrl = "http://${server.hostName}:${server.port}",
        token = token,
        basicAuth = basic,
    )

    @Test
    fun `200 returns the username from the body`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"valid":true,"username":"alice"}"""),
        )
        val result = api.verifyTokenDetail(conn())
        assertTrue(result.valid)
        assertEquals("alice", result.username)
        val request = server.takeRequest()
        assertEquals("/api/auth/verify", request.requestUrl?.encodedPath)
        assertEquals("Bearer tk", request.getHeader("Authorization"))
    }

    @Test
    fun `401 is reported as invalid token`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":"Invalid or expired token"}"""),
        )
        try {
            api.verifyTokenDetail(conn())
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `403 is treated as cannot answer rather than invalid`() {
        // 服务端未启用登录时返回 403；这时把 token 判成失效会让用户去重填
        // 一份本来就对的凭据。
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"x"}"""))
        assertTrue(api.verifyTokenDetail(conn()).valid)
    }

    @Test
    fun `reverse proxy verifies through a business endpoint`() {
        // 反代分支不能让 /api/auth/verify 顶上去——它读不到 ?token=。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"agents":[]}"""))
        val result = api.verifyTokenDetail(conn(basic = BasicAuth("u", "p")))
        assertTrue(result.valid)
        assertNull(result.username)

        val request = server.takeRequest()
        assertEquals("/api/agents", request.requestUrl?.encodedPath)
        assertEquals("Basic dTpw", request.getHeader("Authorization"))
        // 令牌让位给 Basic，改走查询参数。
        assertEquals("tk", request.requestUrl?.queryParameter("token"))
    }

    @Test
    fun `verifyToken maps 401 to false`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":"Invalid or expired token"}"""),
        )
        assertEquals(false, api.verifyToken(conn()))
    }

    @Test
    fun `verifyToken maps 200 to true`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"valid":true}"""))
        assertEquals(true, api.verifyToken(conn()))
    }

    @Test
    fun `verifyToken on reverse proxy instance does not report revocation`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"agents":[]}"""))
        assertEquals(true, api.verifyToken(conn(basic = BasicAuth("u", "p"))))
    }
}