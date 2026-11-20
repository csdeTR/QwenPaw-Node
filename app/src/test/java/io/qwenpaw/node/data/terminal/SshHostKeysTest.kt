package io.qwenpaw.node.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshHostKeysTest {

    private val host = "10.0.0.2"
    private val port = 22

    @Test
    fun `same host in two scopes keeps separate fingerprints`() {
        val keys = SshHostKeys(FakeSharedPreferences())

        keys.remember("inst-a", host, port, "SHA256:aaa")
        keys.remember("inst-b", host, port, "SHA256:bbb")

        assertEquals("SHA256:aaa", keys.fingerprint("inst-a", host, port))
        assertEquals("SHA256:bbb", keys.fingerprint("inst-b", host, port))
        assertNull(keys.fingerprint("inst-c", host, port))
    }

    @Test
    fun `forget only removes the given scope`() {
        val keys = SshHostKeys(FakeSharedPreferences())
        keys.remember("inst-a", host, port, "SHA256:aaa")
        keys.remember("inst-b", host, port, "SHA256:bbb")

        keys.forget("inst-a", host, port)

        assertNull(keys.fingerprint("inst-a", host, port))
        assertEquals("SHA256:bbb", keys.fingerprint("inst-b", host, port))
    }

    @Test
    fun `changes inside one scope do not leak into another`() {
        val keys = SshHostKeys(FakeSharedPreferences())
        keys.remember("inst-a", host, port, "SHA256:aaa")

        // 指纹变化后的重新确认只作用于当前实例。
        keys.remember("inst-a", host, port, "SHA256:new")

        assertEquals("SHA256:new", keys.fingerprint("inst-a", host, port))
        assertNull(keys.fingerprint("inst-b", host, port))
    }

    @Test
    fun `key includes scope, host and port`() {
        assertEquals("inst|host.example|2222", hostKey("inst", "host.example", 2222))
    }
}
