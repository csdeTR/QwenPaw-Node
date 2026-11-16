package io.qwenpaw.node.data.remote

import io.qwenpaw.node.ui.settings.harnessLines
import io.qwenpaw.node.ui.settings.toLoginUi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ACP 与 Harness 端点，以及登录返回的两种形状。 */
class AcpHarnessApiTest {

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
    fun `acp agent put keeps unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"enabled":true,"command":"opencode","args":["acp"],"env":{},
                     "trusted":true,"tool_parse_mode":"update_detail",
                     "stdio_buffer_limit_bytes":52428800,"future":1}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"enabled":true,"command":"x"}"""))
        val raw = api.acpAgentConfig(conn, "opencode")
        val patched = kotlinx.serialization.json.JsonObject(
            raw.jsonObject + ("command" to kotlinx.serialization.json.JsonPrimitive("x")),
        )
        api.saveAcpAgentConfig(conn, "opencode", patched)
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"future\":1"))
        assertTrue(body.contains("\"command\":\"x\""))
    }

    @Test
    fun `node runtime parses candidates`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"node_path":"","effective_node_path":"/usr/bin/node",
                     "candidates":[{"key":"system","label":"system","node_path":"/usr/bin/node",
                       "npx_path":"/usr/bin/npx","node_version":"v22","npx_version":"10",
                       "available":true,"reason_code":"","reason":""}]}""",
            ),
        )
        val runtime = api.acpNodeRuntime(conn)
        assertEquals("/usr/bin/node", runtime.effectiveNodePath)
        assertEquals(1, runtime.candidates.size)
        assertTrue(runtime.candidates[0].available)
    }

    @Test
    fun `harness list parses three states`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"providers":[
                     {"id":"codex","name":"Codex","available":true,"installed":false,
                      "authenticated":false,"error":"Codex runtime not found."},
                     {"id":"qoder","name":"Qoder","available":true,"installed":true,
                      "authenticated":true}]}""",
            ),
        )
        val list = api.harnesses(conn)
        assertEquals(2, list.size)
        assertFalse(list[0].installed)
        assertEquals("Codex runtime not found.", list[0].error)
        assertTrue(list[1].authenticated)
    }

    @Test
    fun `codex login exposes user code and url`() {
        val json = Json.parseToJsonElement(
            """{"type":"chatgptDeviceCode","userCode":"ABCD-EFGH",
                 "verificationUrl":"https://example.com/device","expiresIn":600}""",
        ).jsonObject
        val ui = json.toLoginUi("codex")
        assertEquals("https://example.com/device", ui.url)
        assertTrue(ui.fields.any { it.first == "userCode" && it.second == "ABCD-EFGH" })
    }

    @Test
    fun `qoder login exposes external command`() {
        val json = Json.parseToJsonElement(
            """{"type":"external","loginId":"qoder-1","command":"/usr/bin/qoder login"}""",
        ).jsonObject
        val ui = json.toLoginUi("qoder")
        assertEquals(null, ui.url)
        assertTrue(ui.fields.any { it.second == "/usr/bin/qoder login" })
    }

    @Test
    fun `harness lines handle models and errors`() {
        val models = Json.parseToJsonElement(
            """{"models":[{"id":"gpt-5","name":"GPT-5","description":"flagship"}]}""",
        ).jsonObject
        assertEquals(listOf("GPT-5 · flagship"), harnessLines("models", models))
        val error = Json.parseToJsonElement("""{"message":"not installed"}""").jsonObject
        assertEquals(listOf("not installed"), harnessLines("models", error))
        val empty = Json.parseToJsonElement("""{"skills":[]}""").jsonObject
        assertEquals(listOf("列表为空"), harnessLines("skills", empty))
    }
}
