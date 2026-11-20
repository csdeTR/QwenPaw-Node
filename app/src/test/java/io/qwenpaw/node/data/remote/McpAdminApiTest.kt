package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.McpClientWriteDto
import io.qwenpaw.node.data.model.McpOAuthStartDto
import io.qwenpaw.node.ui.mcp.McpEditDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
 * MCP 管理端点的报文形状。
 *
 * 三处容易写错：新建的 body 是双层结构 `{client_key, client}`；部分更新不能
 * 把 null 字段写进 body（服务端会当成显式清空）；OAuth 的 start / status / revoke
 * 分属三个不同路径，revoke 用的是 DELETE。
 */
class McpAdminApiTest {

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

    private fun bodyOf(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `client list and detail parse nested oauth and access summary`() {
        val client = """
            {"key":"web","name":"Web","enabled":true,"transport":"streamable_http",
             "url":"https://example.com/mcp",
             "oauth_status":{"authorized":true,"expires_at":1700000000.0,
               "scope":"read","client_id":"cid"},
             "access_summary":{"default_effect":"deny","overrides_count":2}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$client]"))
        server.enqueue(MockResponse().setBody(client))

        val clients = api.mcpClients(conn)
        assertEquals(1, clients.size)
        assertEquals("web", clients[0].key)
        assertTrue(clients[0].oauthStatus!!.authorized)
        assertEquals(1_700_000_000.0, clients[0].oauthStatus!!.expiresAt, 0.001)
        assertEquals("deny", clients[0].accessSummary.defaultEffect)
        assertEquals(2, clients[0].accessSummary.overridesCount)
        val listRequest = server.takeRequest()
        assertEquals("GET", listRequest.method)
        assertEquals("/api/mcp", listRequest.requestUrl!!.encodedPath)

        val single = api.mcpClient(conn, "web")
        assertEquals("https://example.com/mcp", single.url)
        assertEquals("cid", single.oauthStatus!!.clientId)
        val detailRequest = server.takeRequest()
        assertEquals("GET", detailRequest.method)
        assertEquals("/api/mcp/web", detailRequest.requestUrl!!.encodedPath)
    }

    @Test
    fun `create wraps the client in client key and client`() {
        server.enqueue(
            MockResponse().setBody(
                """{"key":"fs","name":"Files","enabled":true,"transport":"stdio",
                   "command":"npx","args":["-y","server-fs"]}""",
            ),
        )
        val created = api.createMcpClient(
            conn,
            "fs",
            McpClientWriteDto(
                name = "Files",
                transport = McpEditDraft.STDIO,
                command = "npx",
                args = listOf("-y", "server-fs"),
            ),
        )
        assertEquals("fs", created.key)
        assertEquals(listOf("-y", "server-fs"), created.args)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mcp", request.requestUrl!!.encodedPath)
        val body = bodyOf(request.body.readUtf8())
        assertEquals("fs", body["client_key"]!!.jsonPrimitive.content)
        val client = body["client"]!!.jsonObject
        assertEquals("Files", client["name"]!!.jsonPrimitive.content)
        assertEquals("stdio", client["transport"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("-y", "server-fs"),
            client["args"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // 本机进程不带远端字段：null 不下发。
        assertFalse(client.containsKey("url"))
        assertFalse(client.containsKey("headers"))
        assertFalse(client.containsKey("http_timeout"))
    }

    @Test
    fun `update sends only the changed fields`() {
        server.enqueue(MockResponse().setBody("""{"key":"web","name":"Renamed"}"""))
        api.updateMcpClient(conn, "web", McpClientWriteDto(name = "Renamed"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/mcp/web", request.requestUrl!!.encodedPath)
        val body = bodyOf(request.body.readUtf8())
        assertEquals(1, body.size)
        assertEquals("Renamed", body["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete removes the client without a body`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.deleteMcpClient(conn, "web")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mcp/web", request.requestUrl!!.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `oauth start posts the url and only the non blank options`() {
        server.enqueue(
            MockResponse().setBody(
                """{"auth_url":"https://auth.example/authorize?x=1","session_id":"s-1"}""",
            ),
        )
        val result = api.mcpOAuthStart(
            conn,
            "web",
            McpOAuthStartDto(
                url = "https://example.com/mcp",
                scope = "read write",
                clientId = "cid",
            ),
        )
        assertEquals("https://auth.example/authorize?x=1", result.authUrl)
        assertEquals("s-1", result.sessionId)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mcp/oauth/start/web", request.requestUrl!!.encodedPath)
        val body = bodyOf(request.body.readUtf8())
        assertEquals("https://example.com/mcp", body["url"]!!.jsonPrimitive.content)
        assertEquals("read write", body["scope"]!!.jsonPrimitive.content)
        assertEquals("cid", body["client_id"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("auth_endpoint"))
        assertFalse(body.containsKey("token_endpoint"))

        server.enqueue(
            MockResponse().setBody("""{"auth_url":"https://auth.example/2","session_id":"s-2"}"""),
        )
        api.mcpOAuthStart(conn, "web", McpOAuthStartDto(url = "https://example.com/mcp"))
        val minimal = bodyOf(server.takeRequest().body.readUtf8())
        assertEquals(1, minimal.size)
        assertEquals("https://example.com/mcp", minimal["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `oauth status reads the expiry`() {
        server.enqueue(
            MockResponse().setBody(
                """{"authorized":true,"expires_at":1700000000.0,
                   "scope":"read","client_id":"cid"}""",
            ),
        )
        val status = api.mcpOAuthStatus(conn, "web")
        assertTrue(status.authorized)
        assertEquals(1_700_000_000.0, status.expiresAt, 0.001)
        assertEquals("read", status.scope)
        assertEquals("cid", status.clientId)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/mcp/oauth/status/web", request.requestUrl!!.encodedPath)
    }

    @Test
    fun `oauth revoke uses delete on its own path`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.mcpOAuthRevoke(conn, "web")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mcp/oauth/web", request.requestUrl!!.encodedPath)
        assertEquals(0L, request.bodySize)
    }
}
