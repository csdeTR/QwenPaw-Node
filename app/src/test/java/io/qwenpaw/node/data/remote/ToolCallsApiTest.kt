package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工具调用控制端点。
 *
 * 动作类端点返回 202，响应体形状不固定；这里必须做到「不解析也能算成功」，
 * 否则空体会被 `decode` 抛成「服务端返回了空响应」。
 */
class ToolCallsApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "default")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list parses items with countdown snapshots`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"tool_call_id":"call-1","tool_name":"exec",
                     "session_id":"s1","agent_id":"default","status":"running",
                     "started_at":1234.5,"elapsed":10.0,
                     "offload_remaining":20.0,"kill_remaining":null,
                     "extra":{},"end_state":null,"force_cancelled":false,
                     "max_internal_timeout_secs":60.0}],"total":1}""",
            ),
        )
        val list = api.toolCalls(conn, "s1")
        assertEquals(1, list.total)
        assertEquals("call-1", list.items.single().toolCallId)
        assertEquals(20.0, list.items.single().offloadRemaining!!, 0.001)
        assertEquals("/api/tool-calls/s1", server.takeRequest().path)
    }

    @Test
    fun `cancel accepts an empty 202 body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        api.cancelToolCall(conn, "s1", "call-1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tool-calls/s1/call-1/cancel", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("false", body["force"]?.jsonPrimitive?.content)
    }

    /** offload 没有请求体，带体反而可能被按别的语义处理。 */
    @Test
    fun `offload sends no body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        api.offloadToolCall(conn, "s1", "call-1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tool-calls/s1/call-1/offload", req.path)
        assertEquals(0, req.bodySize)
    }

    @Test
    fun `extend deadline sends seconds and target`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        api.extendToolDeadline(conn, "s1", "call-1", seconds = 30.0, target = "kill")
        val req = server.takeRequest()
        assertEquals("/api/tool-calls/s1/call-1/extend-deadline", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("30.0", body["seconds"]?.jsonPrimitive?.content)
        assertEquals("false", body["no_deadline"]?.jsonPrimitive?.content)
        assertEquals("kill", body["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun `output parses content blocks`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"tool_call_id":"call-1","is_closed":true,
                     "final_state":"finished","content":[{"text":"done"}]}""",
            ),
        )
        val output = api.toolCallOutput(conn, "s1", "call-1")
        assertTrue(output.isClosed)
        assertEquals("finished", output.finalState)
        assertEquals("/api/tool-calls/s1/call-1/output", server.takeRequest().path)
    }
}