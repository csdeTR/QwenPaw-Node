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
 * 智能体与工作区端点。
 *
 * 重点是审批模式的写回：服务端要求 `PUT /api/agents/{id}` 的 body 是**完整的**
 * 配置对象。客户端若只提交 `{"approval_level": "..."}`，其余 30 个字段会被抹掉，
 * 表现为「改完审批模式，智能体其它设置全丢了」。
 */
class AgentAndWorkspaceApiTest {

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
    fun `reads approval level`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"default","name":"Default Agent","approval_level":"AUTO"}""",
            ),
        )
        val profile = api.agent(conn, "default")
        assertEquals("AUTO", profile.approvalLevel)
        assertEquals("Default Agent", profile.name)
        assertEquals("/api/agents/default", server.takeRequest().path)
    }

    @Test
    fun `missing approval level defaults to AUTO`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"default","name":"X"}"""))
        assertEquals("AUTO", api.agent(conn, "default").approvalLevel)
    }

    /**
     * 写回时必须保留原有字段。这里在 GET 响应里放两个客户端 DTO 认不出的字段，
     * 用来验证「用原始 JSON 回写」这条路径没有把它们丢掉。
     */
    @Test
    fun `put keeps unknown fields from current config`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"default","name":"Default Agent","approval_level":"AUTO",
                   "workspace_dir":"/home/u/.qwenpaw/workspaces/default",
                   "backend":"qwenpaw","channels":{"discord":{"enabled":false}}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"id":"default","name":"Default Agent","approval_level":"STRICT"}""",
            ),
        )

        api.setApprovalLevel(conn, "default", "STRICT")

        val get = server.takeRequest()
        val put = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("PUT", put.method)

        val body = json.parseToJsonElement(put.body.readUtf8()).jsonObject
        assertEquals("STRICT", body["approval_level"]?.jsonPrimitive?.content)
        // 其余字段原样回写。
        assertEquals("Default Agent", body["name"]?.jsonPrimitive?.content)
        assertEquals("qwenpaw", body["backend"]?.jsonPrimitive?.content)
        assertEquals(
            "/home/u/.qwenpaw/workspaces/default",
            body["workspace_dir"]?.jsonPrimitive?.content,
        )
        assertTrue(body.containsKey("channels"))
    }

    @Test
    fun `put result reflects new level`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"default","approval_level":"AUTO"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"default","approval_level":"SMART"}"""))
        assertEquals("SMART", api.setApprovalLevel(conn, "default", "SMART").approvalLevel)
    }

    @Test
    fun `reads project directory`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"path":"/home/u/proj","name":"proj","is_workspace_default":false,
                   "workspace_dir":"/home/u/.qwenpaw/workspaces/default","exists":true}""",
            ),
        )
        val dir = api.projectDirectory(conn)
        assertEquals("/home/u/proj", dir.path)
        assertEquals("proj", dir.name)
        assertEquals(false, dir.isWorkspaceDefault)
        assertEquals("/api/workspace/project-directory", server.takeRequest().path)
    }

    /** 恢复默认时 path 必须是 JSON null，不能是空串——传空串服务端会当成路径去校验。 */
    @Test
    fun `reset sends explicit null path`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"path":"/home/u/.qwenpaw/workspaces/default","name":"default",
                   "is_workspace_default":true,"exists":true}""",
            ),
        )
        api.setProjectDirectory(conn, null)
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["path"]!!.toString() == "null")
    }

    @Test
    fun `set path sends the given string`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"path":"/home/u/proj","name":"proj"}"""),
        )
        api.setProjectDirectory(conn, "/home/u/proj")
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("/home/u/proj", body["path"]?.jsonPrimitive?.content)
    }

    /**
     * 恢复默认模型的路径。
     *
     * 实测服务端不接受 null：`provider_id: null` 返回 400，
     * 空串与空对象都报 `Model provider is unavailable`。
     * 因此客户端要先读 `/api/models/active` 拿到全局默认，再显式 PUT 回去。
     */
    @Test
    fun `active model provides fallback target`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"active_llm":{"provider_id":"a2a","model":"CodeArts/deepseek"},
                   "effective_max_input_length":1000000}""",
            ),
        )
        val active = api.activeModel(conn)
        assertEquals("a2a", active.activeLlm?.providerId)
        assertEquals("CodeArts/deepseek", active.activeLlm?.model)
        assertEquals("/api/models/active", server.takeRequest().path)
    }

    @Test
    fun `set chat model sends both fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"model":"glm-4.7-flash","provider_id":"zhipu"}"""),
        )
        api.setChatModel(conn, "chat-1", "zhipu", "glm-4.7-flash")
        val req = server.takeRequest()
        assertEquals("/api/chats/chat-1/model", req.path)
        assertEquals("PUT", req.method)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("zhipu", body["provider_id"]?.jsonPrimitive?.content)
        assertEquals("glm-4.7-flash", body["model"]?.jsonPrimitive?.content)
    }
}