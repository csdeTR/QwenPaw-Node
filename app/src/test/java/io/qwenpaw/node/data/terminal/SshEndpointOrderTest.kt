package io.qwenpaw.node.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshEndpointOrderTest {

    private fun address(
        id: String,
        host: String = "10.0.0.1",
        port: Int = 22,
        user: String = "csdetr",
        default: Boolean = false,
        preferOnVpn: Boolean = false,
    ) = SshAddress(
        id = id,
        host = host,
        port = port,
        user = user,
        default = default,
        preferOnVpn = preferOnVpn,
    )

    @Test
    fun `default address comes first without vpn`() {
        val ordered = SshEndpointOrder.order(
            listOf(
                address("plain", host = "10.0.0.2"),
                address("default", host = "10.0.0.1", default = true),
                address("other", host = "10.0.0.3"),
            ),
            vpnActive = false,
        )
        assertEquals(listOf("default", "plain", "other"), ordered.map { it.id })
    }

    @Test
    fun `vpn addresses come first when vpn is active`() {
        val ordered = SshEndpointOrder.order(
            listOf(
                address("lan", host = "192.168.1.2", default = true),
                address("vpn-a", host = "10.43.0.2", preferOnVpn = true),
                address("vpn-b", host = "10.43.0.3", preferOnVpn = true),
            ),
            vpnActive = true,
        )
        assertEquals(listOf("vpn-a", "vpn-b", "lan"), ordered.map { it.id })
    }

    @Test
    fun `default among vpn addresses stays first`() {
        val ordered = SshEndpointOrder.order(
            listOf(
                address("vpn-a", host = "10.43.0.2", preferOnVpn = true),
                address("vpn-default", host = "10.43.0.1", default = true, preferOnVpn = true),
            ),
            vpnActive = true,
        )
        assertEquals(listOf("vpn-default", "vpn-a"), ordered.map { it.id })
    }

    @Test
    fun `duplicates and blank targets are removed`() {
        val ordered = SshEndpointOrder.order(
            listOf(
                address("one", host = "Example.COM", user = "csdetr"),
                address("duplicate", host = "example.com", user = "csdetr"),
                address("blank-host", host = " "),
                address("blank-user", user = " "),
            ),
            vpnActive = false,
        )
        assertEquals(listOf("one"), ordered.map { it.id })
        assertTrue(ordered.single().host == "Example.COM")
    }
}
