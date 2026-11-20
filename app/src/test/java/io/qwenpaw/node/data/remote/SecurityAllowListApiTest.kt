package io.qwenpaw.node.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
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
 * 免鉴权地址名单的读写。
 *
 * PUT 提交的是**整个**名单，不是增量；body 形如 `{"hosts":[...]}`，
 * 空名单也要显式发一个空数组，否则服务端会保留旧值。
 */
class SecurityAllowListApiTest {

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
    fun `get reads the host list`() {
        server.enqueue(
            MockResponse().setBody("""{"hosts":["127.0.0.1","::1","10.0.0.5"]}"""),
        )
        val dto = api.allowNoAuthHosts(conn)
        assertEquals(listOf("127.0.0.1", "::1", "10.0.0.5"), dto.hosts)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/config/security/allow-no-auth-hosts",
            request.requestUrl!!.encodedPath,
        )
    }

    @Test
    fun `put writes the whole list`() {
        server.enqueue(
            MockResponse().setBody("""{"hosts":["10.0.0.5","[::1]"]}"""),
        )
        val saved = api.setAllowNoAuthHosts(conn, listOf("10.0.0.5", "[::1]"))
        assertEquals(listOf("10.0.0.5", "[::1]"), saved.hosts)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(
            "/api/config/security/allow-no-auth-hosts",
            request.requestUrl!!.encodedPath,
        )
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            listOf("10.0.0.5", "[::1]"),
            body["hosts"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `empty list is sent as an empty array`() {
        server.enqueue(MockResponse().setBody("""{"hosts":[]}"""))
        assertTrue(api.setAllowNoAuthHosts(conn, emptyList()).hosts.isEmpty())

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body.containsKey("hosts"))
        assertTrue(body["hosts"] is JsonArray)
        assertTrue(body["hosts"]!!.jsonArray.isEmpty())
    }
}
