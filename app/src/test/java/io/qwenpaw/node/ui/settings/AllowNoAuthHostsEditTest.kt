package io.qwenpaw.node.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免鉴权地址的纯文本处理单测。
 *
 * 客户端只做去空格、丢弃空项与按不区分大小写去重，规范化留给服务端；
 * 这些用例钉住「保存后回读不出现两种写法并存」以及「移除回环地址要提示」。
 */
class AllowNoAuthHostsEditTest {

    @Test
    fun `add trims the input and keeps the submitted spelling`() {
        assertEquals(listOf("10.0.0.5"), AllowNoAuthHostsEdit.add(emptyList(), "  10.0.0.5  "))
        assertEquals(
            listOf("10.0.0.5", "[::1]"),
            AllowNoAuthHostsEdit.add(listOf("10.0.0.5"), " [::1] "),
        )
        assertEquals(
            listOf("Example.COM"),
            AllowNoAuthHostsEdit.add(emptyList(), "Example.COM"),
        )
    }

    @Test
    fun `blank input leaves the list untouched`() {
        val hosts = listOf("10.0.0.5")
        assertEquals(hosts, AllowNoAuthHostsEdit.add(hosts, ""))
        assertEquals(hosts, AllowNoAuthHostsEdit.add(hosts, "   "))
        assertEquals(emptyList<String>(), AllowNoAuthHostsEdit.add(emptyList(), " "))
    }

    @Test
    fun `duplicates are ignored regardless of case and ipv6 spelling`() {
        val host = listOf("10.0.0.5")
        assertEquals(host, AllowNoAuthHostsEdit.add(host, " 10.0.0.5 "))
        assertEquals(host, AllowNoAuthHostsEdit.add(host, "10.0.0.5"))

        val localhost = listOf("localhost")
        assertEquals(localhost, AllowNoAuthHostsEdit.add(localhost, "LOCALHOST"))

        val v6 = listOf("::1")
        assertEquals(v6, AllowNoAuthHostsEdit.add(v6, "[::1]"))
        assertEquals(v6, AllowNoAuthHostsEdit.add(v6, "0:0:0:0:0:0:0:1"))

        val expanded = listOf("0:0:0:0:0:0:0:1")
        assertEquals(expanded, AllowNoAuthHostsEdit.add(expanded, "::1"))

        val bracketed = listOf("[::1]")
        assertEquals(bracketed, AllowNoAuthHostsEdit.add(bracketed, "::1"))

        val mixedCase = listOf("Example.COM")
        assertEquals(mixedCase, AllowNoAuthHostsEdit.add(mixedCase, "example.com"))
    }

    @Test
    fun `loopback detection accepts brackets and expanded ipv6`() {
        listOf(
            "127.0.0.1",
            " ::1 ",
            "::1",
            "[::1]",
            "0:0:0:0:0:0:0:1",
            "LOCALHOST",
        ).forEach { assertTrue(it, AllowNoAuthHostsEdit.isLoopback(it)) }

        listOf("127.0.0.2", "::2", "example.com", "").forEach {
            assertFalse(it, AllowNoAuthHostsEdit.isLoopback(it))
        }
    }

    @Test
    fun `remove matches case insensitively and leaves other entries`() {
        assertEquals(
            listOf("10.0.0.5"),
            AllowNoAuthHostsEdit.remove(listOf("localhost", "10.0.0.5"), "LOCALHOST"),
        )
        assertTrue(
            AllowNoAuthHostsEdit
                .remove(listOf("::1", "0:0:0:0:0:0:0:1"), "[::1]")
                .isEmpty(),
        )
        // 只按地址本身比较：移除 localhost 不会连带删掉 127.0.0.1。
        assertEquals(
            listOf("127.0.0.1"),
            AllowNoAuthHostsEdit.remove(listOf("127.0.0.1"), "localhost"),
        )
    }

    @Test
    fun `dropped loopback ips are reported in fixed order`() {
        assertEquals(
            listOf("127.0.0.1", "::1"),
            AllowNoAuthHostsEdit.droppedIpLoopbacks(listOf("::1", "127.0.0.1"), emptyList()),
        )
        assertEquals(
            listOf("127.0.0.1"),
            AllowNoAuthHostsEdit.droppedIpLoopbacks(listOf("127.0.0.1", "::1"), listOf("[::1]")),
        )
        assertEquals(
            listOf("::1"),
            AllowNoAuthHostsEdit.droppedIpLoopbacks(listOf("0:0:0:0:0:0:0:1"), emptyList()),
        )
        assertTrue(
            AllowNoAuthHostsEdit.droppedIpLoopbacks(listOf("localhost"), emptyList()).isEmpty(),
        )
        assertTrue(
            AllowNoAuthHostsEdit
                .droppedIpLoopbacks(emptyList(), listOf("127.0.0.1"))
                .isEmpty(),
        )
    }

    @Test
    fun `dropping localhost is not reported as dropping an ip`() {
        assertEquals(
            listOf("127.0.0.1"),
            AllowNoAuthHostsEdit.droppedIpLoopbacks(
                listOf("localhost", "127.0.0.1"),
                emptyList(),
            ),
        )
        assertTrue(
            AllowNoAuthHostsEdit
                .droppedIpLoopbacks(listOf("localhost"), emptyList())
                .isEmpty(),
        )
    }
}
