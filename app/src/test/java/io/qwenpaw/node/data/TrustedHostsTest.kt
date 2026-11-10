package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 受信任连接的归一化与匹配。
 *
 * 用户输入的是「一条地址」，地址检查给出的是「一个主机名」，两者形态差别很大：
 * 协议、端口、路径、末尾的点、IPv6 的方括号都可能只出现在其中一侧。
 * 这里覆盖这些差异，确保命中判断只看 host 本身。
 */
class TrustedHostsTest {

    @Test
    fun `normalizes pasted url`() {
        assertEquals("example.com", normalizeTrustedHost("https://Example.COM/path"))
    }

    @Test
    fun `drops ipv4 port`() {
        assertEquals("192.168.1.5", normalizeTrustedHost("192.168.1.5:8088"))
    }

    @Test
    fun `strips ipv6 brackets`() {
        assertEquals("::1", normalizeTrustedHost("[::1]:8088"))
    }

    @Test
    fun `keeps bare ipv6`() {
        assertEquals("fe80::1", normalizeTrustedHost("fe80::1"))
    }

    @Test
    fun `trims whitespace and trailing dot`() {
        assertEquals("example.com", normalizeTrustedHost("  example.com. "))
    }

    @Test
    fun `rejects unusable input`() {
        assertNull(normalizeTrustedHost(""))
        assertNull(normalizeTrustedHost("http://"))
        assertNull(normalizeTrustedHost("a b"))
        assertNull(normalizeTrustedHost("   "))
    }

    /** 大小写、端口、方括号的差异都不该影响命中。 */
    @Test
    fun `case port and brackets still match`() {
        val trusted = setOf("example.com", "::1")
        assertTrue(isTrustedHost("EXAMPLE.COM", trusted))
        assertTrue(isTrustedHost("example.com:8088", trusted))
        assertTrue(isTrustedHost("http://Example.com/path", trusted))
        assertTrue(isTrustedHost("[::1]:8088", trusted))
        assertTrue(isTrustedHost("::1", trusted))
    }

    @Test
    fun `different host does not match`() {
        assertFalse(isTrustedHost("other.example.com", setOf("example.com")))
        assertFalse(isTrustedHost("192.168.1.6", setOf("192.168.1.5")))
        assertFalse(isTrustedHost("", setOf("example.com")))
    }

    /** 白名单为空时行为必须与改动前完全一致：任何地址都不算受信任。 */
    @Test
    fun `empty set never matches`() {
        assertFalse(isTrustedHost("example.com", emptySet()))
        assertFalse(isTrustedHost("", emptySet()))
    }
}
