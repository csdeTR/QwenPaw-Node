package io.qwenpaw.node.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 插件管理端点的报文形状。
 *
 * 重点在 `interrupt_tasks`：卸载与删除把它放在**查询参数**上而不是 body 里，
 * 写错位置时服务端不会报错，只会继续拒绝——用户看到「还有任务在跑」却没有出路。
 * 依赖升级的 status / apply / cancel 三个动作共用同一个操作路径。
 */
class PluginAdminApiTest {

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
    fun `plugin list and detail parse status and diagnostics`() {
        val plugin = """
            {"id":"a","name":"A","version":"1.0.0","author":"me",
             "enabled":true,"loaded":true,"status":"ok",
             "diagnostics":[{"code":"warn","message":"x"}],
             "plugin_type":"plugin","market_id":"m-1",
             "installation_origin":"market"}
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$plugin]"))
        server.enqueue(MockResponse().setBody(plugin))

        val plugins = api.plugins(conn)
        assertEquals(1, plugins.size)
        assertEquals("a", plugins[0].id)
        assertEquals("ok", plugins[0].status)
        assertEquals(1, plugins[0].diagnostics.size)
        assertEquals("m-1", plugins[0].marketId)
        val listRequest = server.takeRequest()
        assertEquals("GET", listRequest.method)
        assertEquals("/api/plugins", listRequest.requestUrl!!.encodedPath)

        val detail = api.pluginDetail(conn, "a")
        assertEquals("A", detail.name)
        assertTrue(detail.loaded)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/plugins/a", request.requestUrl!!.encodedPath)
    }

    @Test
    fun `config write sends the whole block and both flags`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.updatePluginConfig(
            conn,
            "a",
            Json.parseToJsonElement("""{"api_key":"secret","limit":3}""").jsonObject,
            confirmLegacy = true,
            interruptTasks = true,
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/plugins/a/config", request.requestUrl!!.encodedPath)
        val body = bodyOf(request)
        val config = body["config"]!!.jsonObject
        assertEquals("secret", config["api_key"]!!.jsonPrimitive.content)
        assertEquals("3", config["limit"]!!.jsonPrimitive.content)
        assertEquals("true", body["confirm_legacy"]!!.jsonPrimitive.content)
        assertEquals("true", body["interrupt_tasks"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse().setResponseCode(200))
        api.updatePluginConfig(
            conn,
            "a",
            Json.parseToJsonElement("""{"api_key":"secret"}""").jsonObject,
        )
        val defaults = bodyOf(server.takeRequest())
        assertEquals("false", defaults["confirm_legacy"]!!.jsonPrimitive.content)
        assertEquals("false", defaults["interrupt_tasks"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enabled toggle carries the interrupt flag in the body`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.setPluginEnabled(conn, "a", enabled = false, interruptTasks = true)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/plugins/a/enabled", request.requestUrl!!.encodedPath)
        val body = bodyOf(request)
        assertEquals("false", body["enabled"]!!.jsonPrimitive.content)
        assertEquals("true", body["interrupt_tasks"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unload puts interrupt tasks on the query string`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.unloadPlugin(conn, "a")

        val plain = server.takeRequest()
        assertEquals("POST", plain.method)
        assertEquals("/api/plugins/a/unload", plain.requestUrl!!.encodedPath)
        assertNull(plain.requestUrl!!.queryParameter("interrupt_tasks"))

        server.enqueue(MockResponse().setResponseCode(200))
        api.unloadPlugin(conn, "a", interruptTasks = true)

        val forced = server.takeRequest()
        assertEquals("POST", forced.method)
        assertEquals("/api/plugins/a/unload", forced.requestUrl!!.encodedPath)
        assertEquals("true", forced.requestUrl!!.queryParameter("interrupt_tasks"))
    }

    @Test
    fun `delete puts interrupt tasks on the query string`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.deletePlugin(conn, "a")

        val plain = server.takeRequest()
        assertEquals("DELETE", plain.method)
        assertEquals("/api/plugins/a", plain.requestUrl!!.encodedPath)
        assertNull(plain.requestUrl!!.queryParameter("interrupt_tasks"))
        assertEquals(0L, plain.bodySize)

        server.enqueue(MockResponse().setResponseCode(200))
        api.deletePlugin(conn, "a", interruptTasks = true)

        val forced = server.takeRequest()
        assertEquals("DELETE", forced.method)
        assertEquals("/api/plugins/a", forced.requestUrl!!.encodedPath)
        assertEquals("true", forced.requestUrl!!.queryParameter("interrupt_tasks"))
    }

    @Test
    fun `repair dependency posts to its own path`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api.repairPluginDependencies(conn, "a")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/plugins/a/repair-dependencies", request.requestUrl!!.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `dependency status apply and cancel share the operation path`() {
        server.enqueue(MockResponse().setBody("""{"status":"applying"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"succeeded"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"cancelled"}"""))

        val status = api.dependencyUpgrade(conn, "op-1")
        assertEquals("applying", status["status"]!!.jsonPrimitive.content)
        val statusRequest = server.takeRequest()
        assertEquals("GET", statusRequest.method)
        assertEquals(
            "/api/plugins/dependency-upgrades/op-1",
            statusRequest.requestUrl!!.encodedPath,
        )

        val applied = api.applyDependencyUpgrade(conn, "op-1")
        assertEquals("succeeded", applied["status"]!!.jsonPrimitive.content)
        val applyRequest = server.takeRequest()
        assertEquals("POST", applyRequest.method)
        assertEquals(
            "/api/plugins/dependency-upgrades/op-1/apply",
            applyRequest.requestUrl!!.encodedPath,
        )

        val cancelled = api.cancelDependencyUpgrade(conn, "op-1")
        assertEquals("cancelled", cancelled["status"]!!.jsonPrimitive.content)
        val cancelRequest = server.takeRequest()
        assertEquals("DELETE", cancelRequest.method)
        assertEquals(
            "/api/plugins/dependency-upgrades/op-1",
            cancelRequest.requestUrl!!.encodedPath,
        )
        assertFalse(cancelRequest.requestUrl!!.encodedPath.endsWith("/cancel"))
    }
}
