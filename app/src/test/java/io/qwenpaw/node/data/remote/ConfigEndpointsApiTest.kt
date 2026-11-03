package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工具、环境变量与后台策略。
 *
 * 三处都有容易写错的形状：工具字段是 `config_values` 而不是 `config`、
 * toggle 端点**没有请求体**、`PATCH /api/envs` 的 body 是扁平字典而不是数组。
 * 这些错误在真机上只表现为「开关没反应」或「配置项空白」，很难从现象反推。
 */
class ConfigEndpointsApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private val json = Json { ignoreUnknownKeys = true }
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
    fun `tools list parses config fields and masked values`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"web_search","enabled":true,"category":"search",
                     "description":"联网搜索","async_execution":true,
                     "requires_config":true,
                     "config_fields":[{"name":"api_key","label":"密钥","type":"password","required":true}],
                     "config_values":{"api_key":"***"}}]""",
            ),
        )
        val tool = api.tools(conn).single()
        assertEquals("web_search", tool.name)
        assertTrue(tool.enabled)
        assertTrue(tool.asyncExecution)
        assertTrue(tool.requiresConfig)
        assertEquals(1, tool.configFields?.size)
        assertEquals("password", tool.configFields?.single()?.type)
        assertEquals("***", tool.configValues?.get("api_key")?.jsonPrimitive?.content)
        assertEquals("/api/tools", server.takeRequest().path)
    }

    /**
     * 实测 2.2.2.post1：没有配置项的工具返回 `"config_fields":null` / `"config_values":null`，
     * 不是空数组/空对象。声明成非空集合会让整份工具列表解析失败——
     * 表现为「工具页一直转圈或报加载失败」，而从响应里看不出问题。
     */
    @Test
    fun `null config fields parse`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"read_file","enabled":true,"category":null,
                     "requires_config":false,"config_fields":null,"config_values":null}]""",
            ),
        )
        val tool = api.tools(conn).single()
        assertEquals("read_file", tool.name)
        assertNull(tool.configFields)
        assertNull(tool.configValues)
        assertNull(tool.category)
    }

    /**
     * 非 select 类型的字段返回 `"options":null`。
     *
     * 与 `config_fields:null` 是同一类问题：一个 null 字段就能让整份列表解析失败，
     * 页面上表现为「没有可用的工具」，从响应里看不出异常。
     */
    @Test
    fun `null options parse in config fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"web_search","enabled":true,"requires_config":true,
                     "config_fields":[{"name":"api_key","label":"Key","type":"password",
                                       "options":null,"default":"tavily"}],
                     "config_values":{"api_key":"***"}}]""",
            ),
        )
        val field = api.tools(conn).single().configFields!!.single()
        assertEquals("password", field.type)
        assertNull(field.options)
        assertEquals("tavily", field.default?.jsonPrimitive?.content)
    }

    /** toggle 没有请求体：带体反而可能被服务端按别的语义处理。 */
    @Test
    fun `toggle sends no body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"name":"web_search","enabled":false}"""))
        val tool = api.toggleTool(conn, "web_search")
        assertFalse(tool.enabled)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/tools/web_search/toggle", req.path)
        assertEquals(0, req.bodySize)
    }

    @Test
    fun `async execution sends a boolean field`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"name":"x","async_execution":true}"""))
        api.setToolAsync(conn, "x", true)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/tools/x/async-execution", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("true", body["async_execution"]?.jsonPrimitive?.content)
    }

    /** 保存工具配置的 body 是 `{"config": {...}}`，不是扁平字典。 */
    @Test
    fun `saving tool config wraps values in config`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"success"}"""))
        api.saveToolConfig(conn, "x", mapOf("k" to kotlinx.serialization.json.JsonPrimitive("v")))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tools/x/config", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("v", body["config"]?.jsonObject?.get("k")?.jsonPrimitive?.content)
    }

    @Test
    fun `env catalog exposes editability`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"key":"QWENPAW_PORT","default":"8088","effective_value":"8088",
                     "source":"default","description_key":"k","editable":false,
                     "value_type":"integer","readonly_reason_code":null,
                     "mutability":"startup_only","configured":false}]""",
            ),
        )
        val spec = api.envCatalog(conn).single()
        assertEquals("QWENPAW_PORT", spec.key)
        assertFalse(spec.editable)
        assertEquals("startup_only", spec.mutability)
        assertEquals("/api/envs/catalog", server.takeRequest().path)
    }

    /** 内置变量恢复默认走 reset，不是 DELETE。 */
    @Test
    fun `reset env uses the reset path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[]"""))
        api.resetEnvVar(conn, "QWENPAW_PORT")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/envs/QWENPAW_PORT/reset", req.path)
    }

    @Test
    fun `offload policy round trips a single field`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"default_action":"offload"}"""))
        val policy = api.offloadPolicy(conn)
        assertEquals("offload", policy.defaultAction)
        assertEquals("/api/settings/offload-policy", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"default_action":"keep_foreground"}"""))
        api.setOffloadPolicy(conn, "keep_foreground")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("keep_foreground", body["default_action"]?.jsonPrimitive?.content)
    }
}