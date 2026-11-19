package io.qwenpaw.node.data.remote

import io.qwenpaw.node.TestTexts
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 智能体后端列表、backend-settings 与切换后端。 */
class AgentBackendApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        TestTexts.install()
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "default")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reads backends`() {
        server.enqueue(
            MockResponse().setBody(
                """{"backends":[{"id":"qwenpaw","label":"QwenPaw","status":"available"}]}""",
            ),
        )
        val backends = api.agentsBackends(conn)
        assertEquals("qwenpaw", backends.single().id)
        assertTrue(backends.single().isAvailable)
    }

    @Test
    fun `writes backend settings`() {
        server.enqueue(MockResponse().setBody("{}"))
        api.setAgentBackendSettings(conn, "default", "gpt-5", null)
        val request = server.takeRequest()
        assertEquals("/api/agents/default/backend-settings", request.path)
        assertEquals("PATCH", request.method)
        assertTrue(request.body.readUtf8().contains("\"model\":\"gpt-5\""))
    }

    @Test
    fun `switching backend keeps unknown fields`() {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"default","name":"Default","backend":"qwenpaw","future":1}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"id":"default"}"""))
        api.updateAgentBackend(conn, "default", "codex")
        server.takeRequest()
        val put = server.takeRequest().body.readUtf8()
        assertTrue(put.contains("\"backend\":\"codex\""))
        assertTrue(put.contains("\"future\":1"))
    }
}
