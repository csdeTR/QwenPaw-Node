package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.PortabilitySelectionDto
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** portability 转发端点（经配套插件）。 */
class PortabilityApiTest {

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
    fun `sources parse detected`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"source":"codex","name":"Codex","detected":true},
                     {"source":"qoder","name":"Qoder","detected":false}]""",
            ),
        )
        val list = api.portabilitySources(conn)
        assertEquals(2, list.size)
        assertTrue(list[0].detected)
        assertEquals("/api/plugins/qwenpaw-node-bridge/portability/sources", server.takeRequest().path)
    }

    @Test
    fun `current job null when absent`() = runBlocking {
        server.enqueue(MockResponse().setBody("null"))
        assertNull(api.portabilityCurrentJob(conn))
    }

    @Test
    fun `start job sends selections`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"job_id":"j1","agent_id":"default","state":"running","seq":1,
                     "providers":[],"logs":[]}""",
            ),
        )
        api.portabilityStartJob(
            conn = conn,
            jobId = "j1",
            selections = mapOf(
                "codex" to PortabilitySelectionDto(
                    sessions = false,
                    memory = listOf("m1"),
                    skills = listOf("s1"),
                ),
            ),
            allowPluginExecution = false,
        )
        val request = server.takeRequest()
        assertEquals("/api/plugins/qwenpaw-node-bridge/portability/jobs/j1/start", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"sessions\":false"))
        assertTrue(body.contains("\"memory\":[\"m1\"]"))
        assertTrue(body.contains("\"allow_plugin_execution\":false"))
    }

    @Test
    fun `cancel posts`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"job_id":"j1","agent_id":"default","state":"cancelling","seq":2,
                     "providers":[],"logs":[]}""",
            ),
        )
        val job = api.portabilityCancelJob(conn, "j1")
        assertEquals("cancelling", job.state)
        assertEquals("/api/plugins/qwenpaw-node-bridge/portability/jobs/j1/cancel", server.takeRequest().path)
    }
}
