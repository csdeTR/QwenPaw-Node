package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 市场安装、技能导入与广播。 */
class MarketInstallApiTest {

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
    fun `plugin upload sends force query`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"demo","name":"Demo","version":"1.0","loaded":true}""",
            ),
        )
        val source = SafZipSource.fromStream("demo.zip", 4) {
            "data".byteInputStream()
        }
        val result = api.uploadPlugin(conn, source, force = true)
        assertTrue(result.loaded)
        val request = server.takeRequest()
        assertEquals("/api/plugins/upload?force=true", request.path)
        assertTrue(request.body.readUtf8().contains("filename=\"demo.zip\""))
    }

    @Test
    fun `plugin install by url sends source and force`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"demo","loaded":true}"""))
        api.installPlugin(conn, "https://example.com/demo.zip", force = false)
        val request = server.takeRequest()
        assertEquals("/api/plugins/install", request.path)
        assertEquals(
            """{"source":"https://example.com/demo.zip","force":false}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `skill upload 409 detail becomes conflicts`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":{"imported":[],"count":0,
                     "conflicts":[{"reason":"conflict","skill_name":"pdf","suggested_name":"pdf-2"}]}}""",
            ),
        )
        val source = SafZipSource.fromStream("skill.zip", 4) { "data".byteInputStream() }
        val result = api.uploadSkillZip(conn, toPool = false, source = source)
        assertEquals(0, result.count)
        assertEquals(1, result.conflicts.size)
        assertEquals("pdf-2", result.conflicts[0].suggestedName)
    }

    @Test
    fun `hub install start and status`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"task_id":"t1","bundle_url":"https://x/s.zip","status":"pending"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"task_id":"t1","bundle_url":"https://x/s.zip","status":"completed",
                     "result":{"imported":["s"],"count":1,"conflicts":[]}}""",
            ),
        )
        val task = api.hubInstallStart(
            conn,
            "https://x/s.zip",
            providerKey = "qwenpaw",
            version = "1.0",
        )
        assertEquals("t1", task.taskId)
        server.takeRequest()
        val done = api.hubInstallStatus(conn, "t1")
        assertTrue(done.isTerminal)
        assertEquals(listOf("s"), done.result?.imported)
    }

    @Test
    fun `broadcast 409 detail becomes conflicts`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":{"downloaded":[],"conflicts":[
                     {"workspace_id":"a1","workspace_name":"工作","name":"pdf",
                      "suggested_name":"pdf-2"}]}}""",
            ),
        )
        val result = api.broadcastSkill(conn, "pdf", listOf("a1"))
        assertEquals(1, result.conflicts.size)
        assertEquals("工作", result.conflicts[0].workspaceName)
        val request = server.takeRequest()
        assertEquals("/api/skills/pool/download", request.path)
        assertTrue(request.body.readUtf8().contains("\"workspace_id\":\"a1\""))
    }
}
