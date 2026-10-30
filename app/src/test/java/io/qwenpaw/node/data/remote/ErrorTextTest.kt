package io.qwenpaw.node.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 错误文案映射。
 *
 * 连接页与聊天页共用这一套：先前连接页直接显示 `e.message`，
 * 用户在界面上看到的是 `Failed to connect to /10.0.2.2:8099` 这样的英文异常。
 */
class ErrorTextTest {

    @Test
    fun `business errors win over network heuristics`() {
        // ApiException 继承自 IOException，若不先判会落进「网络中断」分支。
        assertEquals("登录已失效，请重新登录", ErrorText.of(ApiException(401, "unauthorized")))
        assertEquals("会话不存在或已被删除", ErrorText.of(ApiException(404, "not found")))
        assertEquals(
            "该会话已有任务在运行，请先停止或等待完成",
            ErrorText.of(ApiException(409, "conflict")),
        )
        assertEquals("Console 频道未启用，请在服务端开启", ErrorText.of(ApiException(503, "x")))
    }

    @Test
    fun `unknown status code keeps server detail`() {
        assertEquals("teapot", ErrorText.of(ApiException(418, "teapot")))
    }

    @Test
    fun `okhttp plain ioexception is translated`() {
        val text = ErrorText.of(IOException("Failed to connect to /10.0.2.2:8099"))
        assertEquals("无法连接服务器，请确认 QwenPaw 已启动且地址端口正确", text)
        assertFalse("不应把英文异常原样抛给用户", text.contains("Failed to connect"))
    }

    @Test
    fun `unknown host is more specific than connect failure`() {
        assertEquals(
            "找不到服务器，请检查地址与网络",
            ErrorText.of(UnknownHostException("paw.invalid")),
        )
    }

    @Test
    fun `wrapped causes are still recognized`() {
        val wrapped = IOException("wrapper", ConnectException("Connection refused"))
        assertEquals("无法连接服务器，请确认 QwenPaw 已启动且地址端口正确", ErrorText.of(wrapped))
    }

    @Test
    fun `timeout has its own wording`() {
        assertEquals("连接超时，请检查网络后重试", ErrorText.of(SocketTimeoutException()))
    }

    @Test
    fun `generic io failure mentions saved draft`() {
        val text = ErrorText.of(IOException("unexpected end of stream"))
        assertTrue(text.startsWith("网络中断"))
        assertTrue(text.contains("草稿已保存"))
    }

    @Test
    fun `never returns blank`() {
        assertEquals("未知错误", ErrorText.of(RuntimeException()))
    }

    /**
     * 登录接口的 404 不能复用「会话不存在或已被删除」。
     *
     * 真正的原因通常是地址写成了 http，被反代 301 到 https 后 POST 被改写成 GET。
     * 文案必须把用户指向地址，而不是会话数据。
     */
    @Test
    fun `login 404 points at the address instead of the chat`() {
        val e = ApiException(404, "Not Found", QwenPawApi.LOGIN_NOT_REACHED)
        val text = ErrorText.of(e)

        assertTrue(text.contains("https://"))
        assertFalse(text.contains("会话不存在"))
        assertEquals(text, ErrorText.ofWithHint(e, usedHttps = false))
    }

    @Test
    fun `hint suggests https only when the failed request used http`() {
        val e = ApiException(404, "Not Found")
        assertEquals(ErrorText.HTTPS_REQUIRED, ErrorText.hint(e, usedHttps = false))
        // 已经走 https 却拿到 4xx，说明问题不在协议上。
        assertNull(ErrorText.hint(e, usedHttps = true))
    }

    @Test
    fun `hint suggests http when https cannot connect`() {
        val e = IOException("Failed to connect to /10.0.2.2:8099")
        assertEquals(ErrorText.HTTP_ONLY, ErrorText.hint(e, usedHttps = true))
        assertNull(ErrorText.hint(e, usedHttps = false))
    }

    @Test
    fun `ofWithHint joins both lines`() {
        val text = ErrorText.ofWithHint(IOException("Connection refused"), usedHttps = true)
        assertTrue(text.contains("无法连接服务器"))
        assertTrue(text.contains(ErrorText.HTTP_ONLY))
    }
}