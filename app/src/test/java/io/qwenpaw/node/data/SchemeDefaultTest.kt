package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未写协议时的默认协议。
 *
 * 这一条直接对应一次真实故障：地址只填 `chat.csdetr.cn` 时被补成 `http://`，
 * 反向代理把 http 301 到 https，OkHttp 跟随跳转时把 POST 改写成 GET 并丢掉请求体，
 * 登录接口于是返回 404，界面却说「会话不存在或已被删除」。
 *
 * 判定逻辑抽成纯函数，才能在 JVM 上把「哪个主机用哪个协议」钉住。
 */
class SchemeDefaultTest {

    @Test
    fun `public domain defaults to https`() {
        assertEquals("https", InstanceRepository.defaultSchemeFor("chat.csdetr.cn"))
        assertEquals("https", InstanceRepository.defaultSchemeFor("Chat.Example.COM"))
    }

    @Test
    fun `public ip defaults to https`() {
        assertEquals("https", InstanceRepository.defaultSchemeFor("8.8.8.8"))
    }

    @Test
    fun `loopback and localhost stay on http`() {
        assertEquals("http", InstanceRepository.defaultSchemeFor("localhost"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("127.0.0.1"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("127.1.2.3"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("::1"))
    }

    @Test
    fun `private and cgnat ranges stay on http`() {
        assertEquals("http", InstanceRepository.defaultSchemeFor("10.43.2.1"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("192.168.1.10"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("172.16.0.9"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("172.31.255.1"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("169.254.1.1"))
        // easytier / tailscale 常用段：直连服务端没有 TLS。
        assertEquals("http", InstanceRepository.defaultSchemeFor("100.64.0.1"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("100.127.0.1"))
        // 172.32 已不在私有段内。
        assertEquals("https", InstanceRepository.defaultSchemeFor("172.32.0.1"))
    }

    @Test
    fun `lan suffixes stay on http`() {
        assertEquals("http", InstanceRepository.defaultSchemeFor("nas.local"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("paw.lan"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("box.internal"))
        assertEquals("http", InstanceRepository.defaultSchemeFor("mini.home"))
    }

    @Test
    fun `host extraction drops port and path`() {
        assertEquals("chat.csdetr.cn", InstanceRepository.hostOf("chat.csdetr.cn:8443"))
        assertEquals("chat.csdetr.cn", InstanceRepository.hostOf("https://chat.csdetr.cn/api/x"))
        assertEquals("10.0.2.2", InstanceRepository.hostOf("10.0.2.2:8088"))
        assertEquals("::1", InstanceRepository.hostOf("[::1]:8088"))
    }

    @Test
    fun `bare domain becomes https with 443`() {
        assertEquals("https://chat.csdetr.cn:443", InstanceRepository.normalizeBaseUrl("chat.csdetr.cn"))
        assertEquals("https://chat.csdetr.cn:8443", InstanceRepository.normalizeBaseUrl("chat.csdetr.cn:8443"))
    }

    @Test
    fun `lan address becomes http with the given port`() {
        assertEquals("http://10.43.2.1:8088", InstanceRepository.normalizeBaseUrl("10.43.2.1:8088"))
        assertEquals("http://192.168.1.10:8088", InstanceRepository.normalizeBaseUrl("192.168.1.10:8088"))
        assertEquals("http://127.0.0.1:8088", InstanceRepository.normalizeBaseUrl("127.0.0.1:8088"))
        assertEquals("http://localhost:8088", InstanceRepository.normalizeBaseUrl("localhost:8088"))
        assertEquals("http://nas.local:8088", InstanceRepository.normalizeBaseUrl("nas.local:8088"))
    }

    @Test
    fun `explicit scheme is never rewritten`() {
        // 用户明确写了 http 就是 http，不能因为「公网域名应该用 https」而擅自升级。
        assertEquals("http://chat.csdetr.cn:80", InstanceRepository.normalizeBaseUrl("http://chat.csdetr.cn"))
        assertEquals("https://chat.csdetr.cn:443", InstanceRepository.normalizeBaseUrl("https://chat.csdetr.cn"))
        // https 未写端口时回填 80 会得到 https://host:80，请求直接连不上。
        assertFalse(InstanceRepository.normalizeBaseUrl("https://chat.csdetr.cn").endsWith(":80"))
    }

    @Test
    fun `full width punctuation is folded before scheme decision`() {
        // 中文输入法把冒号打成全角时，地址看起来是对的但解析会失败。
        assertEquals("https://chat.csdetr.cn:443", InstanceRepository.normalizeBaseUrl("chat.csdetr.cn"))
        assertEquals(
            "http://192.168.1.10:8088",
            InstanceRepository.normalizeBaseUrl("192.168.1.10：8088"),
        )
    }

    @Test
    fun `usesHttps follows the same rule`() {
        assertTrue(InstanceRepository.usesHttps("chat.csdetr.cn"))
        assertTrue(InstanceRepository.usesHttps("https://chat.csdetr.cn"))
        assertFalse(InstanceRepository.usesHttps("10.0.2.2:8088"))
        assertFalse(InstanceRepository.usesHttps("http://chat.csdetr.cn"))
        // 无法解析时按「不是 https」处理，不抛异常给界面。
        assertFalse(InstanceRepository.usesHttps(""))
    }
}