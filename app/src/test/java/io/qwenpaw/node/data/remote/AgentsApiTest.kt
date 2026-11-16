package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 智能体管理端点。 */
class AgentsApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
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
    fun `list parses pinned and backend`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"agents":[{"id":"default","name":"Default","description":"",
                     "workspace_dir":"/w/default","enabled":true,"pinned":true,
                     "startup_status":"running","backend":"qwenpaw"}]}""",
            ),
        )
        val list = api.agents(conn)
        assertEquals(1, list.size)
        assertTrue(list[0].pinned)
        assertEquals("/w/default", list[0].workspaceDir)
        assertEquals("qwenpaw", list[0].backend)
    }

    @Test
    fun `create sends name and optional id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"work","workspace_dir":"/w/work","enabled":true,"pinned":false}""",
            ),
        )
        val ref = api.createAgent(conn, "工作", "用于项目", "work")
        assertEquals("work", ref.id)
        val request = server.takeRequest()
        assertEquals("/api/agents", request.path)
        assertEquals("""{"name":"工作","description":"用于项目","id":"work"}""", request.body.readUtf8())
    }

    @Test
    fun `copy sends options`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"id":"copy","workspace_dir":"/w/copy"}"""),
        )
        api.copyAgent(conn, "default", "副本", copySkills = true, copyJobs = false)
        val request = server.takeRequest()
        assertEquals("/api/agents/default/copy", request.path)
        assertEquals(
            """{"name":"副本","copy_md_files":true,"copy_skills":true,"copy_jobs":false}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `toggle and pin send embedded flags`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        api.setAgentEnabled(conn, "work", false)
        api.setAgentPinned(conn, "work", true)
        assertEquals("/api/agents/work/toggle", server.takeRequest().path)
        val pin = server.takeRequest()
        assertEquals("/api/agents/work/pin", pin.path)
        assertEquals("""{"pinned":true}""", pin.body.readUtf8())
    }

    @Test
    fun `reorder sends full id list`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"agent_ids":["default","work"]}"""))
        api.reorderAgents(conn, listOf("default", "work"))
        val request = server.takeRequest()
        assertEquals("/api/agents/order", request.path)
        assertEquals("""{"agent_ids":["default","work"]}""", request.body.readUtf8())
    }

    @Test
    fun `status parses running count`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"running","running_task_count":2,
                     "last_run_at":"2026-11-16T10:00:00","last_finish_at":null}""",
            ),
        )
        val status = api.agentStatus(conn, "work")
        assertEquals("running", status.status)
        assertEquals(2, status.runningTaskCount)
        assertEquals("/api/agents/work/agent-status", server.takeRequest().path)
    }

    @Test
    fun `rename keeps unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"work","name":"旧名","description":"旧描述","approval_level":"AUTO",
                     "future":{"keep":1}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody("""{"id":"work","name":"新名","approval_level":"AUTO"}"""),
        )
        api.updateAgentProfile(conn, "work", "新名", "新描述")
        server.takeRequest()
        val put = server.takeRequest()
        val body = put.body.readUtf8()
        assertTrue(body.contains("\"future\":{\"keep\":1}"))
        assertTrue(body.contains("\"name\":\"新名\""))
        assertEquals("/api/agents/work", put.path)
    }
}
