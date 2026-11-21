package io.qwenpaw.node.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 技能配置表单的端点与请求体。 */
class SkillConfigApiTest {

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
    fun `config get returns inner config object`() {
        server.enqueue(MockResponse().setBody("""{"config":{"a":1}}"""))

        val config = api.skillConfig(conn(), "s1")

        assertEquals(1, (config["a"] as JsonPrimitive).content.toInt())
        assertEquals("/api/skills/s1/config", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `config save and clear use put and delete`() {
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))
        server.enqueue(MockResponse().setBody("""{"cleared":true}"""))

        api.saveSkillConfig(conn(), "s1", JsonObject(mapOf("a" to JsonPrimitive(1))))
        api.clearSkillConfig(conn(), "s1")

        val save = server.takeRequest()
        assertEquals("PUT", save.method)
        assertEquals("/api/skills/s1/config", save.requestUrl?.encodedPath)
        assertTrue(save.body.readUtf8().contains("\"config\""))

        val clear = server.takeRequest()
        assertEquals("DELETE", clear.method)
    }

    @Test
    fun `preload tags and channels use documented bodies`() {
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))

        api.setSkillPreload(conn(), "s1", true)
        api.setSkillTags(conn(), "s1", listOf("a", "b"))
        api.setSkillChannels(conn(), "s1", listOf("all"))

        val preload = server.takeRequest()
        assertEquals("/api/skills/s1/preload", preload.requestUrl?.encodedPath)
        assertEquals("""{"preload":true}""", preload.body.readUtf8())

        val tags = server.takeRequest()
        assertEquals("/api/skills/s1/tags", tags.requestUrl?.encodedPath)
        assertEquals("""["a","b"]""", tags.body.readUtf8())

        val channels = server.takeRequest()
        assertEquals("/api/skills/s1/channels", channels.requestUrl?.encodedPath)
        assertEquals("""["all"]""", channels.body.readUtf8())
    }

    @Test
    fun `file read encodes nested path segments`() {
        server.enqueue(MockResponse().setBody("""{"content":"hello"}"""))

        val content = api.skillFile(conn(), "s1", "references/x.md")

        assertEquals("hello", content)
        assertEquals(
            "/api/skills/s1/files/references/x.md",
            server.takeRequest().requestUrl?.encodedPath,
        )
    }
}
