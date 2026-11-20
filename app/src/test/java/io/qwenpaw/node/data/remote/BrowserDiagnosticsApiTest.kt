package io.qwenpaw.node.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 浏览器诊断端点的报文形状。
 *
 * 状态里嵌着上一次自检与生命周期，字段缺失时不能让整页解析失败；
 * 自检是 POST 且不带请求体；日志与修复走配套插件的子路径，`lines` 是查询参数。
 */
class BrowserDiagnosticsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: QwenPawApi
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = QwenPawApi()
        conn = Connection(server.url("/").toString().trimEnd('/'), "tk")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chrome status parses nested lifecycle and last self test`() {
        server.enqueue(
            MockResponse().setBody(
                """{"connected":true,"connected_since":"2026-11-20T10:00:00Z",
                   "extension_version":"1.2.3",
                   "bridge_lifecycle":{"connected":false,"connected_since":null},
                   "last_extension_disconnect":{"reason":"port closed"},
                   "contract_health":{"name":"contract","passed":true,"code":"","status":"ok"},
                   "readiness_state":"ready",
                   "backend":{"kind":"native"},
                   "last_self_test":{"status":"passed","checked_at":"2026-11-20T09:59:00Z",
                     "duration_ms":12.5,
                     "checks":[{"name":"nm_host","passed":true,"status":"ok","code":"",
                       "message":"","repair_action":"setup"}]}}""",
            ),
        )
        val status = api.browserChromeStatus(conn)
        assertTrue(status.connected)
        assertEquals("2026-11-20T10:00:00Z", status.connectedSince)
        assertEquals("1.2.3", status.extensionVersion)
        assertFalse(status.bridgeLifecycle!!.connected)
        assertNotNull(status.lastExtensionDisconnect)
        assertTrue(status.contractHealth!!.passed)
        assertEquals("ready", status.readinessState)
        assertEquals("native", status.backend!!["kind"]!!.jsonPrimitive.content)
        val selfTest = status.lastSelfTest!!
        assertEquals("passed", selfTest.status)
        assertEquals(12.5, selfTest.durationMs, 0.001)
        assertEquals("setup", selfTest.checks.single().repairAction)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/browser/chrome/status", request.requestUrl!!.encodedPath)
    }

    @Test
    fun `self test posts without a body`() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"failed","checked_at":"2026-11-20T10:01:00Z",
                   "duration_ms":3.0,
                   "checks":[{"name":"extension","passed":false,"status":"failed",
                     "code":"no_extension","message":"extension not connected",
                     "repair_action":"install_extension"}]}""",
            ),
        )
        val result = api.browserSelfTest(conn)
        assertEquals("failed", result.status)
        assertFalse(result.checks.single().passed)
        assertEquals("no_extension", result.checks.single().code)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/browser/chrome/self-test", request.requestUrl!!.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `logs endpoint carries the line count`() {
        server.enqueue(
            MockResponse().setBody(
                """{"lines":["line one","line two"],"truncated":true,"path":"/tmp/nm.log"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody("""{"lines":[],"truncated":false}"""),
        )

        val logs = api.bridgeBrowserLogs(conn)
        assertEquals(listOf("line one", "line two"), logs.lines)
        assertTrue(logs.truncated)
        assertEquals("/tmp/nm.log", logs.path)
        val defaultRequest = server.takeRequest()
        assertEquals("GET", defaultRequest.method)
        assertEquals(
            "/api/plugins/qwenpaw-node-bridge/browser/logs",
            defaultRequest.requestUrl!!.encodedPath,
        )
        assertEquals("200", defaultRequest.requestUrl!!.queryParameter("lines"))

        val custom = api.bridgeBrowserLogs(conn, lines = 50)
        assertTrue(custom.lines.isEmpty())
        assertNull(custom.path)
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("lines"))
    }

    @Test
    fun `repair posts the setup action`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.bridgeBrowserRepair(conn)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/api/plugins/qwenpaw-node-bridge/browser/repair",
            request.requestUrl!!.encodedPath,
        )
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("setup", body["action"]!!.jsonPrimitive.content)
    }
}
