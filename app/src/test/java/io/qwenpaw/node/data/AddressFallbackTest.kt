package io.qwenpaw.node.data

import io.qwenpaw.node.data.remote.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 多地址回退。
 *
 * 这一组用例的核心是「什么情况该换地址、什么情况不该换」。
 * 换早了会掩盖真实错误，换晚了则多地址功能形同虚设。
 */
class AddressFallbackTest {

    // ---------------- 候选地址 ----------------

    @Test
    fun `drops blank lines and keeps order`() {
        assertEquals(
            listOf("192.168.1.10:8088", "10.8.0.2:8088"),
            AddressFallback.candidates(
                listOf(" 192.168.1.10:8088 ", "", "10.8.0.2:8088", "   "),
            ),
        )
    }

    @Test
    fun `empty list is rejected before any request`() {
        try {
            runBlocking {
                AddressFallback.firstReachable(listOf("", "  ")) { fail("不应发起请求"); "" }
            }
            fail("应当抛出异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("请填写服务器地址", e.message)
        }
    }

    // ---------------- 失败判定 ----------------

    @Test
    fun `connect refused counts as connection failure`() {
        assertTrue(AddressFallback.isConnectionFailure(ConnectException("Connection refused")))
    }

    @Test
    fun `unknown host counts as connection failure`() {
        assertTrue(AddressFallback.isConnectionFailure(UnknownHostException("10.8.0.2")))
    }

    @Test
    fun `timeout counts as connection failure`() {
        assertTrue(AddressFallback.isConnectionFailure(SocketTimeoutException("timeout")))
    }

    /**
     * OkHttp 实际抛的就是这种普通 IOException，类型看不出来，只能靠文案。
     * 这条是回退能否生效的关键：漏掉它，真实环境里「地址不通」根本不触发回退。
     */
    @Test
    fun `okhttp failed to connect message counts as connection failure`() {
        assertTrue(
            AddressFallback.isConnectionFailure(
                IOException("Failed to connect to /192.168.1.10:8088"),
            ),
        )
    }

    @Test
    fun `wrapped cause is inspected`() {
        assertTrue(
            AddressFallback.isConnectionFailure(
                IOException("request failed", ConnectException("Connection refused")),
            ),
        )
    }

    /** 业务错误说明地址是通的，不能换地址，否则 401 会被下一次尝试掩盖。 */
    @Test
    fun `api exception never triggers fallback`() {
        assertFalse(AddressFallback.isConnectionFailure(ApiException(401, "需要登录")))
        assertFalse(AddressFallback.isConnectionFailure(ApiException(500, "服务端错误")))
        // 即便文案里带着连接字样，只要是 ApiException 就不回退。
        assertFalse(AddressFallback.isConnectionFailure(ApiException(503, "Failed to connect")))
    }

    // ---------------- 顺序与结果 ----------------

    @Test
    fun `stops at first reachable address`() = runBlocking {
        val tried = mutableListOf<String>()
        val (hit, value) = AddressFallback.firstReachable(
            listOf("192.168.1.10:8088", "10.8.0.2:8088"),
        ) { url ->
            tried += url
            if (url.startsWith("192")) throw ConnectException("refused")
            "ok"
        }
        assertEquals("10.8.0.2:8088", hit)
        assertEquals("ok", value)
        assertEquals(
            listOf("192.168.1.10:8088", "10.8.0.2:8088"),
            tried,
        )
    }

    /** 第一个地址就通时不该碰第二个地址。 */
    @Test
    fun `does not touch later addresses when primary works`() = runBlocking {
        val tried = mutableListOf<String>()
        AddressFallback.firstReachable(listOf("a:1", "b:2")) { url ->
            tried += url
            "ok"
        }
        assertEquals(listOf("a:1"), tried)
    }

    @Test
    fun `business error aborts immediately without trying next`() = runBlocking {
        val tried = mutableListOf<String>()
        try {
            AddressFallback.firstReachable(listOf("a:1", "b:2")) { url ->
                tried += url
                throw ApiException(401, "需要登录")
            }
            fail("应当抛出 401")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
        // 只尝试了第一个就停下，401 没有被第二个地址掩盖。
        assertEquals(listOf("a:1"), tried)
    }

    /** 全部失败时抛最后一个异常，它的信息量通常最大。 */
    @Test
    fun `all failing throws last error`() = runBlocking {
        try {
            AddressFallback.firstReachable(listOf("a:1", "b:2")) { url ->
                throw if (url == "a:1") {
                    ConnectException("refused")
                } else {
                    UnknownHostException("b")
                }
            }
            fail("应当抛出异常")
        } catch (e: Throwable) {
            assertTrue(e is UnknownHostException)
        }
    }
}