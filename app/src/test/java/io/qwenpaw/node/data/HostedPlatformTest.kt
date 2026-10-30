package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 托管平台域名的拦截。
 *
 * 这两类实例的鉴权与转发都由平台自己完成，客户端按「自建实例」直连只会得到
 * 一串看不懂的 401/404。拦截放在 `normalizeBaseUrl` 里，验证与连接两条路径都会经过它，
 * 因此填了地址、点了验证就会被拦下，下一步按钮也不会亮。
 */
class HostedPlatformTest {

    @Test
    fun `agentscope platform domain is recognized`() {
        assertEquals(
            HostedPlatform.AGENTSCOPE,
            hostedPlatformOf("qwenpaw.platform.agentscope.io"),
        )
        assertEquals(
            HostedPlatform.AGENTSCOPE,
            hostedPlatformOf("QWENPAW.Platform.AgentScope.IO"),
        )
    }

    @Test
    fun `modelscope domain and its subdomains are recognized`() {
        assertEquals(HostedPlatform.MODELSCOPE, hostedPlatformOf("modelscope.cn"))
        assertEquals(HostedPlatform.MODELSCOPE, hostedPlatformOf("abc.modelscope.cn"))
    }

    @Test
    fun `ordinary hosts are not affected`() {
        assertNull(hostedPlatformOf("chat.csdetr.cn"))
        assertNull(hostedPlatformOf("192.168.1.10"))
        // 仅仅是包含字符串不算命中。
        assertNull(hostedPlatformOf("modelscope.cn.example.com"))
    }

    @Test
    fun `normalize rejects both hosts with the exact message`() {
        val cases = mapOf(
            "qwenpaw.platform.agentscope.io" to
                "QwenPaw Node无法添加源于AgentScope Platform的实例，请使用私有化部署方案",
            "https://qwenpaw.platform.agentscope.io/api" to
                "QwenPaw Node无法添加源于AgentScope Platform的实例，请使用私有化部署方案",
            "modelscope.cn" to
                "QwenPaw Node无法添加通过魔搭创空间创建的实例，请使用私有化部署方案",
            "https://modelscope.cn/studios/a/b" to
                "QwenPaw Node无法添加通过魔搭创空间创建的实例，请使用私有化部署方案",
        )
        cases.forEach { (address, expected) ->
            try {
                InstanceRepository.normalizeBaseUrl(address)
                fail("应拦截 $address")
            } catch (e: IllegalArgumentException) {
                assertEquals(expected, e.message)
            }
        }
    }

    @Test
    fun `normalize still accepts a self hosted domain`() {
        assertTrue(InstanceRepository.normalizeBaseUrl("chat.csdetr.cn").startsWith("https://"))
    }
}