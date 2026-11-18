package io.qwenpaw.node.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 凭据下发闸门。
 *
 * 这一层只决定「经插件下发私钥/口令」是否允许；本地终端不经过它。
 * 关键判据是：公网 + 明文 HTTP 时，即使关闭了安全提示，也必须先确认主机属于自己。
 */
class SshCredentialGateTest {

    @Test
    fun `private address is allowed`() {
        val gate = assessSshCredentialGate(
            url = "http://10.10.0.211:8088",
            loginRequired = true,
            sshTrustedHosts = emptySet(),
        )
        assertTrue(gate.allowed)
    }

    @Test
    fun `public https with login is allowed`() {
        val gate = assessSshCredentialGate(
            url = "https://chat.example.com:8088",
            loginRequired = true,
            sshTrustedHosts = emptySet(),
        )
        assertTrue(gate.allowed)
    }

    @Test
    fun `public plaintext without ssh trust is blocked`() {
        val gate = assessSshCredentialGate(
            url = "http://chat.example.com:8088",
            loginRequired = true,
            sshTrustedHosts = emptySet(),
        )
        assertFalse(gate.allowed)
        assertNotNull(gate.reason)
        assertTrue(gate.reason!!.contains("明文"))
    }

    @Test
    fun `public plaintext with ssh trust is allowed`() {
        val gate = assessSshCredentialGate(
            url = "http://chat.example.com:8088",
            loginRequired = true,
            sshTrustedHosts = setOf("chat.example.com"),
        )
        assertTrue(gate.allowed)
    }

    @Test
    fun `public without auth is blocked even if trusted`() {
        val gate = assessSshCredentialGate(
            url = "http://chat.example.com:8088",
            loginRequired = false,
            sshTrustedHosts = setOf("chat.example.com"),
        )
        assertFalse(gate.allowed)
        assertTrue(gate.reason!!.contains("Web 登录认证"))
    }

    @Test
    fun `trust matching ignores case and port`() {
        val gate = assessSshCredentialGate(
            url = "http://Chat.Example.com:8088",
            loginRequired = true,
            sshTrustedHosts = setOf("chat.example.com"),
        )
        assertTrue(gate.allowed)
    }
}
