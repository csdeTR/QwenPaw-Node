package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
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
 * 默认模型与供应商连通性。
 *
 * 两条容易写错的约束：`GET /active?scope=agent` 缺 `agent_id` 回 400，
 * 因此只有该作用域才带它；`POST /{provider}/test` 失败也是 200，要看 `success`。
 */
class ModelsApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "agent-1")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `global scope omits agent id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"active_llm":{"provider_id":"a2a","model":"m1"},
                    "effective_max_input_length":1000000}""",
            ),
        )

        val active = api.activeModels(conn, "global")

        assertEquals("a2a", active.activeLlm?.providerId)
        assertEquals(1000000L, active.effectiveMaxInputLength)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/models/active", url.encodedPath)
        assertEquals("global", url.queryParameter("scope"))
        assertNull("global 作用域不该带 agent_id", url.queryParameter("agent_id"))
    }

    @Test
    fun `agent scope carries agent id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"active_llm":null}"""))

        val active = api.activeModels(conn, "agent", "QwenPaw QA Agent")

        assertNull("服务端在没写过该智能体默认模型时返回 null", active.activeLlm)
        val url = server.takeRequest().requestUrl!!
        assertEquals("agent", url.queryParameter("scope"))
        // 空格由 HttpUrl 编码，原样传中文与空格都不会拼坏查询串。
        assertEquals("QwenPaw QA Agent", url.queryParameter("agent_id"))
    }

    @Test
    fun `set active model sends scope and agent id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"active_llm":{"provider_id":"a2a","model":"m1"}}""",
            ),
        )

        api.setActiveModel(conn, "a2a", "m1", "agent", "agent-1")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/models/active", request.requestUrl!!.encodedPath)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"provider_id\":\"a2a\""))
        assertTrue(body.contains("\"model\":\"m1\""))
        assertTrue(body.contains("\"scope\":\"agent\""))
        assertTrue(body.contains("\"agent_id\":\"agent-1\""))
    }

    @Test
    fun `set active model global omits agent id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"active_llm":null}"""))

        api.setActiveModel(conn, "a2a", "m1", "global")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"scope\":\"global\""))
        assertFalse("global 不该带 agent_id", body.contains("agent_id"))
    }

    @Test
    fun `provider test failure is 200 with success false`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":false,
                    "message":"Connection failed: API error when connecting to `http://localhost:1234/v1`",
                    "status":null,"http_status":null,"retryable":null,
                    "checked_at":null,"verification":null}""",
            ),
        )

        val result = api.testProvider(conn, "lmstudio")

        assertFalse("测试失败不是 HTTP 错误，界面必须看 success", result.success)
        assertTrue(result.message.startsWith("Connection failed"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/models/lmstudio/test", request.requestUrl!!.encodedPath)
    }

    @Test
    fun `provider test success decodes message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"message":"Connection successful","status":null,
                    "http_status":null,"retryable":null,"checked_at":null,
                    "verification":null}""",
            ),
        )

        val result = api.testProvider(conn, "dashscope")

        assertTrue(result.success)
        assertEquals("Connection successful", result.message)
    }

    /** 未知供应商是 404，原文里句末有没有句号两版不一致，客户端不加工。 */
    @Test
    fun `unknown provider surfaces server detail`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"detail":"Provider 'nope' not found."}"""),
        )

        val error = runCatching { api.testProvider(conn, "nope") }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(404, (error as ApiException).code)
    }
}
