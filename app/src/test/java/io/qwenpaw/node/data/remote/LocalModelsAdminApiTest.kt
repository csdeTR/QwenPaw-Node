package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.LocalModelConfigDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地模型管理端点（配置、运行时启停、删除、更新检查）的报文形状。
 *
 * `port` 有两种语义：不写表示保持原值，写 `null` 表示改回自动端口；
 * 删除的模型 id 可以带 `/`，服务端按 `{model_id:path}` 接收，客户端不能再编码一层。
 */
class LocalModelsAdminApiTest {

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

    private fun bodyOf(request: RecordedRequest) =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun `config parses every field the form needs`() {
        server.enqueue(
            MockResponse().setBody(
                """{"max_context_length":65536,"port":8080,
                   "generate_kwargs":{"temperature":0.7}}""",
            ),
        )
        val config = api.localModelConfig(conn)
        assertEquals(65_536, config.maxContextLength)
        assertEquals(8_080, config.port)
        assertEquals(
            "0.7",
            config.generateKwargs!!["temperature"]!!.jsonPrimitive.content,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/local-models/config", request.requestUrl!!.encodedPath)
    }

    @Test
    fun `config put omits fields that were not filled`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.setLocalModelConfig(conn, LocalModelConfigDto(maxContextLength = 131_072))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/local-models/config", request.requestUrl!!.encodedPath)
        val body = bodyOf(request)
        assertEquals(setOf("max_context_length"), body.keys)
        assertEquals("131072", body["max_context_length"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clear port is sent as an explicit null`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.setLocalModelConfig(conn, LocalModelConfigDto(port = 8_080), clearPort = true)

        val cleared = bodyOf(server.takeRequest())
        assertTrue(cleared.containsKey("port"))
        assertTrue(cleared["port"] is JsonNull)
        assertFalse(cleared.containsKey("max_context_length"))

        server.enqueue(MockResponse().setResponseCode(200))
        api.setLocalModelConfig(conn, LocalModelConfigDto(port = 9_090))

        val explicit = bodyOf(server.takeRequest())
        assertEquals("9090", explicit["port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `start posts the model id`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.startLocalModelServer(conn, "qwen3-4b")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/local-models/server", request.requestUrl!!.encodedPath)
        val body = bodyOf(request)
        assertEquals("qwen3-4b", body["model_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stop uses delete without a body`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.stopLocalModelServer(conn)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/local-models/server", request.requestUrl!!.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `delete keeps slashes in the model id`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.deleteLocalModel(conn, "Qwen/Qwen3-4B/qwen3-4b-q4_k_m.gguf")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals(
            "/api/local-models/models/Qwen/Qwen3-4B/qwen3-4b-q4_k_m.gguf",
            request.requestUrl!!.encodedPath,
        )
    }

    @Test
    fun `server update reads the boolean`() {
        server.enqueue(MockResponse().setBody("""{"has_update":true}"""))
        assertTrue(api.localModelServerUpdate(conn).hasUpdate)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/local-models/server/update", request.requestUrl!!.encodedPath)

        server.enqueue(MockResponse().setBody("""{"has_update":false}"""))
        assertFalse(api.localModelServerUpdate(conn).hasUpdate)
    }
}
