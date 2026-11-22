package io.qwenpaw.node.data.channel

import io.qwenpaw.node.TestTexts
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.ErrorText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 保存频道失败时的错误文案。
 *
 * 服务端 422 的 `detail` 是 pydantic 的数组原文，[ErrorText] 必须先把它翻成
 * 「allow_from：应为列表」这类可读文本，而不是把整段 JSON 贴到界面上。
 */
class ChannelSaveErrorTextTest {

    @Before
    fun setUp() {
        TestTexts.install()
    }

    @Test
    fun `pydantic array detail is readable`() {
        val detail = """[{"type":"list_type","loc":["body","allow_from"],""" +
            """"msg":"Input should be a valid list","input":{}}]"""

        assertEquals("allow_from：应为列表", ErrorText.of(ApiException(422, detail)))
    }

    @Test
    fun `plain string detail is unchanged`() {
        assertEquals("Unknown channel", ErrorText.of(ApiException(400, "Unknown channel")))
    }

    @Test
    fun `object detail falls back to its message field`() {
        assertEquals(
            "Plugin task failed",
            ErrorText.of(ApiException(500, """{"message":"Plugin task failed"}""")),
        )
    }

    @Test
    fun `generic 409 is not misreported as chat task`() {
        assertEquals("请求冲突：conflict", ErrorText.of(ApiException(409, "conflict")))
    }

    @Test
    fun `chat task 409 wording is preserved`() {
        assertEquals(
            "该对话已有任务在运行，请先停止或等待完成",
            ErrorText.of(ApiException(409, "A task is already running for this chat")),
        )
    }
}
