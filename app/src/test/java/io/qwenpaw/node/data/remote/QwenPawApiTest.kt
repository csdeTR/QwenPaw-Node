package io.qwenpaw.node.data.remote

import io.qwenpaw.node.ui.chat.ChatImageLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求体构造测试，重点盯住图片附件的字段形状。 */
class QwenPawApiTest {

    private val api = QwenPawApi()
    private val json = Json { ignoreUnknownKeys = true }

    // 回归：服务端读的是扁平字段 image_url，早期发 source.url 会被静默丢弃。
    @Test
    fun `image part uses flat image_url field`() {
        val part = api.mediaPart("image", "/tmp/a.png", "a.png")
        assertEquals("image", part["type"]?.jsonPrimitive?.content)
        assertEquals("/tmp/a.png", part["image_url"]?.jsonPrimitive?.content)
        assertFalse(part.containsKey("source"))
    }

    @Test
    fun `file part uses file_url and filename`() {
        val part = api.mediaPart("file", "/tmp/r.pdf", "r.pdf")
        assertEquals("file", part["type"]?.jsonPrimitive?.content)
        assertEquals("/tmp/r.pdf", part["file_url"]?.jsonPrimitive?.content)
        assertEquals("r.pdf", part["filename"]?.jsonPrimitive?.content)
    }

    @Test
    fun `video part uses video_url field`() {
        val part = api.mediaPart("video", "/tmp/v.mp4", "v.mp4")
        assertEquals("/tmp/v.mp4", part["video_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat body keeps image_url inside content`() {
        val body = api.buildChatBody(
            sessionId = "s",
            userId = "u",
            parts = listOf(api.textPart("hi"), api.mediaPart("image", "/tmp/a.png", "a.png")),
        )
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertTrue(content[1].jsonObject.containsKey("image_url"))
    }

    /**
     * 新建会话时必须带上 `meta.console_placeholder_name`。
     *
     * 服务端 `console.py` 用 `chat.meta["console_placeholder_name"] == chat.name`
     * 判断「这个名字只是占位」，再把会话名换成首条消息，随后派发标题生成任务。
     * 缺这一层，自动命名永远不会发生，会话会一直停在截断后的前 20 个字。
     */
    @Test
    fun `create chat body carries placeholder meta when requested`() {
        val body = api.createChatBody("帮我看下报错", "s-1", "u-1", placeholder = true)
        assertEquals("帮我看下报错", body["name"]?.jsonPrimitive?.content)
        assertEquals("console", body["channel"]?.jsonPrimitive?.content)
        val meta = body["meta"]?.jsonObject
        assertEquals("帮我看下报错", meta?.get("console_placeholder_name")?.jsonPrimitive?.content)
    }

    /** 不带占位标记时不写 meta：用户手改过名字的会话不该被再当成占位。 */
    @Test
    fun `create chat body omits meta by default`() {
        val body = api.createChatBody("名字", "s-1", "u-1", placeholder = false)
        assertFalse(body.containsKey("meta"))
    }

    // resolve 把服务端本地绝对路径拼到预览端点，外链与 data URI 原样保留。
    @Test
    fun `image loader resolve handles all url shapes`() {
        val loader = ChatImageLoader()
        val conn = Connection("http://10.0.0.1:8088", null, "default")

        assertEquals("http://host/a.png", loader.resolve(conn, "http://host/a.png"))
        assertEquals("data:image/png;base64,AAA", loader.resolve(conn, "data:image/png;base64,AAA"))
        assertEquals(
            "http://10.0.0.1:8088/api/files/preview/home/u/a.png",
            loader.resolve(conn, "/home/u/a.png"),
        )
    }
}