package io.qwenpaw.node.data.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 配套插件更新状态端点的报文形状。
 *
 * 两件事要钉住：路径写在 `/update/status` 下（`/status` 会被核心的 plugins 路由抢先匹配），
 * 以及 404 表示「插件没有这条端点」而不是故障——旧插件不该让客户端报错。
 */
class BridgeUpdateStatusApiTest {

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
    fun `update status parses all fields`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"update_available":true,"latest_version":"0.5.0",
                 "latest_url":"https://example.invalid/bridge-0.5.0.zip",
                 "notify_client":true,"checked_at":"2026-11-20T10:00:00Z"}
                """.trimIndent(),
            ),
        )

        val status = api.bridgeUpdateStatus(conn)

        assertTrue(status!!.updateAvailable)
        assertEquals("0.5.0", status.latestVersion)
        assertEquals("https://example.invalid/bridge-0.5.0.zip", status.latestUrl)
        assertTrue(status.notifyClient)
        assertEquals("2026-11-20T10:00:00Z", status.checkedAt?.jsonPrimitive?.content)
        assertEquals(
            "/api/plugins/qwenpaw-node-bridge/update/status",
            server.takeRequest().path,
        )
    }

    @Test
    fun `missing fields fall back to no update`() {
        server.enqueue(MockResponse().setBody("{}"))

        val status = api.bridgeUpdateStatus(conn)

        assertFalse(status!!.updateAvailable)
        assertFalse(status.notifyClient)
        assertEquals("", status.latestVersion)
        assertNull(status.checkedAt)
    }

    @Test
    fun `404 means the plugin has no update endpoint`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"detail\":\"Not Found\"}"))

        assertNull(api.bridgeUpdateStatus(conn))
    }
}
