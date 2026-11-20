package io.qwenpaw.node.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 本地模型端点的报文形状。
 *
 * 服务端返回的模型目录条目字段很多，客户端只取四项；进度里没有百分比，
 * `total_bytes` 可能为 null。这两点都由这些用例钉住。
 */
class LocalModelsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: QwenPawApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = QwenPawApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun conn() = Connection("http://${server.hostName}:${server.port}", "tk")

    @Test
    fun `server status reads install flags and message`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"available":false,"installable":true,"installed":false,
                   "port":null,"model_name":null,"message":"llama.cpp is not installed"}""",
            ),
        )
        val dto = api.localModelServer(conn())
        assertFalse(dto.available)
        assertTrue(dto.installable)
        assertFalse(dto.installed)
        assertNull(dto.port)
        assertEquals("llama.cpp is not installed", dto.message)
        assertEquals("/api/local-models/server", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `model list keeps only the fields the UI needs`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [{"id":"qwen3-4b","name":"Qwen3 4B","size_bytes":2500000000,
                  "downloaded":true,"source":"auto","supports_multimodal":false,
                  "ranking":{"score":1},"unknown_future_field":"x"}]
                """.trimIndent(),
            ),
        )
        val list = api.localModels(conn())
        assertEquals(1, list.size)
        assertEquals("qwen3-4b", list[0].id)
        assertEquals("Qwen3 4B", list[0].name)
        assertEquals(2_500_000_000L, list[0].sizeBytes)
        assertTrue(list[0].downloaded)
        assertEquals("/api/local-models/models", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `empty recommendation list parses as empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(api.localModels(conn()).isEmpty())
    }

    @Test
    fun `start download sends model name and auto source`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"accepted","message":"ok"}"""))
        api.startLocalModelDownload(conn(), "qwen3-4b")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/local-models/models/download", request.requestUrl?.encodedPath)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("qwen3-4b", (body["model_name"] as JsonPrimitive).content)
        assertEquals("auto", (body["source"] as JsonPrimitive).content)
    }

    @Test
    fun `download already running is a 409`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":"A model download is already in progress."}"""),
        )
        try {
            api.startLocalModelDownload(conn(), "qwen3-4b")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(409, e.code)
        }
    }

    @Test
    fun `progress tolerates null total and reads speed`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"downloading","model_name":"qwen3-4b",
                   "downloaded_bytes":1024,"total_bytes":null,
                   "speed_bytes_per_sec":12.5,"source":"auto","error":null,
                   "local_path":null}""",
            ),
        )
        val dto = api.localModelDownloadProgress(conn())
        assertEquals("downloading", dto.status)
        assertEquals(1024L, dto.downloadedBytes)
        assertNull(dto.totalBytes)
        assertEquals(12.5, dto.speedBytesPerSec, 0.001)
        assertEquals("/api/local-models/models/download", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `idle progress has zeroed counters`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"idle","model_name":null,"downloaded_bytes":0,
                   "total_bytes":null,"speed_bytes_per_sec":0.0,"source":null,
                   "error":null,"local_path":null}""",
            ),
        )
        val dto = api.localModelDownloadProgress(conn())
        assertEquals("idle", dto.status)
        assertEquals(0L, dto.downloadedBytes)
    }

    @Test
    fun `cancel uses delete without a body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","message":"canceling"}"""))
        api.cancelLocalModelDownload(conn())

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/local-models/models/download", request.requestUrl?.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `runtime install uses the server download endpoints`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"accepted","message":"ok"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"downloading","model_name":null,"downloaded_bytes":10,
                   "total_bytes":100,"speed_bytes_per_sec":1.0,"source":null,
                   "error":null,"local_path":null}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","message":"canceling"}"""))

        api.startServerDownload(conn())
        assertEquals("/api/local-models/server/download", server.takeRequest().requestUrl?.encodedPath)

        val progress = api.serverDownloadProgress(conn())
        assertEquals(100L, progress.totalBytes)
        assertEquals("/api/local-models/server/download", server.takeRequest().requestUrl?.encodedPath)

        api.cancelServerDownload(conn())
        assertEquals("/api/local-models/server/download", server.takeRequest().requestUrl?.encodedPath)
    }
}