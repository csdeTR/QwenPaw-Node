package io.qwenpaw.node.data.remote

import io.qwenpaw.node.ui.settings.RunningConfigEdit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 运行配置、安全与服务端设置端点。
 *
 * 重点有三处：运行配置必须整份回写（未知字段不能丢）、file-guard 允许部分 body、
 * 语言只接受服务端的 7 个合法值。
 */
class ConfigSecurityApiTest {

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
    fun `running config round trip keeps unknown fields`() = runBlocking {
        val getBody = """
            {"max_iters":100,"max_input_length":128000,
             "light_context_config":{"context_compact_config":{
               "compact_threshold_ratio":0.8,"keep_me":true}},
             "memory_backend_configs":{"remelight":{"api_key":"***"}},
             "future_field":{"x":1}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(getBody))
        val raw = api.runningConfig(conn)
        assertEquals("100", raw["max_iters"]?.jsonPrimitive?.content)

        val form = RunningConfigEdit.fromJson(raw).copy(maxIters = "120")
        val patched = RunningConfigEdit.apply(raw, form)
        server.enqueue(MockResponse().setBody(patched.toString()))
        api.saveRunningConfig(conn, patched)

        server.takeRequest() // GET
        val putBody = server.takeRequest().body.readUtf8()
        assertTrue(putBody.contains("\"max_iters\":120"))
        assertTrue(putBody.contains("\"future_field\""))
        assertTrue(putBody.contains("\"api_key\":\"***\""))
        assertTrue(putBody.contains("\"keep_me\":true"))
    }

    @Test
    fun `tool guard builtin rules parse`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"R1","tools":["shell"],"params":["command"],
                     "category":"command_injection","severity":"HIGH",
                     "patterns":["rm -rf"],"exclude_patterns":[],
                     "description":"危险命令","remediation":"确认后再执行"}]""",
            ),
        )
        val rules = api.toolGuardBuiltinRules(conn)
        assertEquals(1, rules.size)
        assertEquals("R1", rules[0].id)
        assertEquals("HIGH", rules[0].severity)
        assertEquals("/api/config/security/tool-guard/builtin-rules", server.takeRequest().path)
    }

    @Test
    fun `file guard put sends only enabled`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"enabled":false,"paths":["/etc/passwd"],"allow_preview_outside_workspace":false}""",
            ),
        )
        val guard = api.setFileGuardEnabled(conn, false)
        assertFalse(guard.enabled)
        assertEquals(1, guard.paths.size)
        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"enabled":false}""", body)
    }

    @Test
    fun `sandbox status parses effective and reason`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"enabled":true,"effective":false,"reason":"missing capability"}""",
            ),
        )
        val status = api.sandboxStatus(conn)
        assertTrue(status.enabled)
        assertFalse(status.effective)
        assertEquals("missing capability", status.reason)
    }

    @Test
    fun `language get and put`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"language":"zh"}"""))
        assertEquals("zh", api.serverLanguage(conn))
        server.enqueue(MockResponse().setBody("""{"language":"ja"}"""))
        assertEquals("ja", api.setServerLanguage(conn, "ja"))
        assertEquals("/api/settings/language", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("/api/settings/language", put.path)
        assertEquals("""{"language":"ja"}""", put.body.readUtf8())
    }

    @Test
    fun `skill scanner keeps unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"mode":"warn","timeout":30,"whitelist":[{"name":"x"}],"future":1}""",
            ),
        )
        val raw = api.skillScanner(conn)
        val patched = raw.jsonObject.let { obj ->
            kotlinx.serialization.json.JsonObject(
                obj + ("mode" to kotlinx.serialization.json.JsonPrimitive("block")),
            )
        }
        server.enqueue(MockResponse().setBody(patched.toString()))
        api.saveSkillScanner(conn, patched)
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"future\":1"))
        assertTrue(body.contains("\"mode\":\"block\""))
    }
}
