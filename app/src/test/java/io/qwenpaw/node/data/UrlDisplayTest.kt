package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏地址写法。
 *
 * 判据只有一条：这一行要回答「连的是哪台机器」。协议对这个问题没有信息量，
 * 默认端口由协议唯一确定，同样没有；非默认端口必须留着，那正是要核对的东西。
 */
class UrlDisplayTest {

    @Test
    fun `http default port is dropped`() {
        assertEquals("paw.local", displayAddress("http://paw.local:80"))
    }

    @Test
    fun `https default port is dropped`() {
        assertEquals("example.com", displayAddress("https://example.com:443"))
    }

    @Test
    fun `custom port is kept`() {
        assertEquals("10.0.2.9:8088", displayAddress("http://10.0.2.9:8088"))
        assertEquals("example.com:8443", displayAddress("https://example.com:8443"))
    }

    /** https 的 80 与 http 的 443 都不是默认端口，必须显示，否则会看成另一台机器。 */
    @Test
    fun `non default pairs keep the port`() {
        assertEquals("example.com:80", displayAddress("https://example.com:80"))
        assertEquals("example.com:443", displayAddress("http://example.com:443"))
    }

    @Test
    fun `ipv6 keeps brackets`() {
        assertEquals("[::1]:8088", displayAddress("http://[::1]:8088"))
        assertEquals("[::1]", displayAddress("http://[::1]:80"))
    }

    /** 缺协议时按 http 解析：`normalizeBaseUrl` 对域名会补 https，这里只做展示，不改变事实。 */
    @Test
    fun `scheme may be omitted`() {
        assertEquals("10.0.2.9:8088", displayAddress("10.0.2.9:8088"))
    }

    /** 解析不了就原样返回：宁可难看，也不要吞掉用户填的内容。 */
    @Test
    fun `unparsable input is returned as is`() {
        assertEquals("not a url", displayAddress("not a url"))
        assertEquals("", displayAddress("   "))
    }
}