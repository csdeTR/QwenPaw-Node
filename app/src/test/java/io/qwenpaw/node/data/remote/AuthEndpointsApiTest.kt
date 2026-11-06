package io.qwenpaw.node.data.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 登出与吊销凭据的报文形状。
 *
 * 服务端对这两个端点有两条容易写错的约定：
 * 1. `revoke-token` 的 `token` 是**可选**的，不传表示「吊销当前这一个」；
 *    传空串会被当成「要吊销一个空 token」而失败。
 * 2. 未启用登录时返回 403 而不是 401——本来就没有可吊销的东西，
 *    客户端必须把它当成功处理，否则用户会觉得「退不出去」。
 */
class AuthEndpointsApiTest {

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

    private fun conn(token: String? = "tk") = Connection(
        baseUrl = "http://${server.hostName}:${server.port}",
        token = token,
    )

    /** 退出登录：请求体里不带 token，让服务端吊销调用者自己的那一张。 */
    @Test
    fun `revoke current token omits the body field`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"revoked":true}"""))
        api.revokeToken(conn(), null)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/revoke-token", request.requestUrl?.encodedPath)
        assertFalse(request.body.readUtf8().contains("\"token\""))
        assertEquals("Bearer tk", request.getHeader("Authorization"))
    }

    /** 指定吊销某张 token 时才带字段。 */
    @Test
    fun `revoke specific token sends the field`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"revoked":true}"""))
        api.revokeToken(conn(), "other-token")

        assertTrue(server.takeRequest().body.readUtf8().contains("\"token\":\"other-token\""))
    }

    /** 空串与 null 等价：空串发出去会被服务端当成非法 token。 */
    @Test
    fun `blank token is treated as current`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"revoked":true}"""))
        api.revokeToken(conn(), "   ")
        assertFalse(server.takeRequest().body.readUtf8().contains("\"token\""))
    }

    @Test
    fun `revoke all tokens posts without body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"revoked":true}"""))
        api.revokeAllTokens(conn())

        val request = server.takeRequest()
        assertEquals("/api/auth/revoke-all-tokens", request.requestUrl?.encodedPath)
    }

    /** verify 200 → token 有效。 */
    @Test
    fun `verify returns true on 200`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"valid":true}"""))
        assertTrue(api.verifyToken(conn()))
    }

    /** verify 401 → token 失效（与「网络不通」区分开）。 */
    @Test
    fun `verify returns false on 401`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid or expired token"}"""))
        assertFalse(api.verifyToken(conn()))
    }

    /** 403（未启用登录）说明「这个问题问不出来」，不能因此把用户赶去重填密码。 */
    @Test
    fun `verify treats 403 as unusable but not expired`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Authentication is not enabled"}"""))
        assertTrue(api.verifyToken(conn()))
    }

    /** 失败时抛出的是 403，调用方据此给「该实例未启用登录」而不是报错。 */
    @Test
    fun `revoke without auth surfaces 403`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Authentication is not enabled"}"""))
        val error = runCatching { api.revokeToken(conn(null), null) }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals(403, (error as ApiException).code)
    }

    /** 没有 token 的实例不该发吊销请求，接口本身也不该自动补 Authorization。 */
    @Test
    fun `no token means no authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"valid":true}"""))
        api.verifyToken(conn(null))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    /** 登录要带反代凭据，否则请求在 nginx 那一层就被 401 掉，用户看到的是「密码错」。 */
    @Test
    fun `login carries proxy basic credentials`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token":"tk-new","username":"tester"}"""),
        )
        api.login(
            baseUrl = "http://${server.hostName}:${server.port}",
            username = "tester",
            password = "pass1234",
            permanent = true,
            basicAuth = BasicAuth("proxyuser", "proxypass"),
        )

        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.requestUrl?.encodedPath)
        assertEquals(BasicAuth("proxyuser", "proxypass").headerValue, request.getHeader("Authorization"))
    }

    /** 反代拦下的 401 不能翻译成「用户名或密码不正确」。 */
    @Test
    fun `login 401 from proxy is not reported as bad credentials`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Basic realm=\"QwenPaw\"")
                .setBody("<html>401 Authorization Required</html>"),
        )
        val error = runCatching {
            api.login(
                baseUrl = "http://${server.hostName}:${server.port}",
                username = "tester",
                password = "pass1234",
                permanent = true,
            )
        }.exceptionOrNull()

        assertTrue(error is ApiException)
        val text = ErrorText.of(error as ApiException)
        assertTrue(text.contains("反向代理"))
        assertFalse(text.contains("用户名或密码"))
    }
}