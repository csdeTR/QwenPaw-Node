package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.channel.jsonBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 频道端点的报文形状。
 *
 * 两处容易写错：列表是以频道名为键的对象而不是数组；`PUT` 是整段替换，
 * 提交的必须是合并后的完整对象。
 */
class ChannelApiTest {

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
    fun `channel list is keyed by channel name`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "wechat": {"enabled": true, "bot_prefix": "", "bot_token": "t", "isBuiltin": true},
                  "telegram": {"enabled": false, "bot_prefix": "", "isBuiltin": true},
                  "third": {"enabled": false, "bot_prefix": ""}
                }
                """.trimIndent(),
            ),
        )
        val list = api.channels(conn())

        assertEquals(setOf("wechat", "telegram", "third"), list.keys)
        assertTrue(jsonBoolean(list.getValue("wechat"), "enabled"))
        assertFalse(jsonBoolean(list.getValue("telegram"), "enabled"))
        assertFalse(jsonBoolean(list.getValue("third"), "isBuiltin"))
        // 原始字段留着，写回时整段带回。
        assertEquals("t", (list.getValue("wechat")["bot_token"] as JsonPrimitive).content)
        assertEquals("/api/config/channels", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `qrcode response carries image and poll token`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"qrcode_img":"iVBORw0KGgo=","poll_token":"p1"}"""),
        )
        val dto = api.channelQrcode(conn(), "wechat")
        assertEquals("iVBORw0KGgo=", dto.qrcodeImg)
        assertEquals("p1", dto.pollToken)
        assertEquals("/api/config/channels/wechat/qrcode", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `unsupported channel surfaces 404`() {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"detail":"QR code not supported for channel: telegram"}"""),
        )
        try {
            api.channelQrcode(conn(), "telegram")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `status sends poll token as query parameter`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"confirmed","credentials":{"bot_token":"t"}}"""),
        )
        val dto = api.channelQrStatus(conn(), "wechat", "p 1")
        assertEquals("confirmed", dto.status)
        assertEquals("t", (dto.credentials["bot_token"] as JsonPrimitive).content)

        val request = server.takeRequest()
        assertEquals("/api/config/channels/wechat/qrcode/status", request.requestUrl?.encodedPath)
        assertEquals("p 1", request.requestUrl?.queryParameter("token"))
    }

    @Test
    fun `channel names are not double encoded`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.channel(conn(), "plugin:demo")
        // addPathSegment 负责编码；先 encodeSegment 再拼会被二次编码成 %253A。
        assertEquals(
            "/api/config/channels/plugin:demo",
            server.takeRequest().path,
        )
    }

    @Test
    fun `update sends the merged object as put body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val body = JsonObject(
            mapOf(
                "enabled" to JsonPrimitive(true),
                "bot_prefix" to JsonPrimitive("[b]"),
                "bot_token" to JsonPrimitive("secret"),
            ),
        )
        api.updateChannel(conn(), "wechat", body)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/config/channels/wechat", request.requestUrl?.encodedPath)
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(true, (sent["enabled"] as JsonPrimitive).boolean)
        assertEquals("[b]", (sent["bot_prefix"] as JsonPrimitive).content)
        assertEquals("secret", (sent["bot_token"] as JsonPrimitive).content)
    }

    @Test
    fun `schemas expose labels and qrcode availability`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "dingtalk": {"label":"DingTalk","qrcode_available":false,
                               "availability":"missing_code",
                               "repair":{"action":"install","plugin_id":"dingtalk",
                                         "source":"bundled:dingtalk"}},
                  "wechat": {"label":"WeChat","qrcode_available":true,
                             "availability":"validated"}
                }
                """.trimIndent(),
            ),
        )
        val schemas = api.channelSchemas(conn())
        assertEquals(setOf("dingtalk", "wechat"), schemas.keys)
        assertEquals("DingTalk", (schemas.getValue("dingtalk")["label"] as JsonPrimitive).content)
        assertFalse(jsonBoolean(schemas.getValue("dingtalk"), "qrcode_available"))
        assertTrue(jsonBoolean(schemas.getValue("wechat"), "qrcode_available"))
        assertEquals(
            "/api/config/channels/schemas",
            server.takeRequest().requestUrl?.encodedPath,
        )
    }

    @Test
    fun `closing a qr session uses delete with the poll token`() {
        server.enqueue(MockResponse().setResponseCode(204))
        api.closeChannelQrcode(conn(), "wechat", "p 1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/config/channels/wechat/qrcode", request.requestUrl?.encodedPath)
        assertEquals("p 1", request.requestUrl?.queryParameter("token"))
    }
}