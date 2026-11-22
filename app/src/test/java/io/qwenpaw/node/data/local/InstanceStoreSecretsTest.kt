package io.qwenpaw.node.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keystore 不可用时，降级存储不得保留任何口令类字段。 */
class InstanceStoreSecretsTest {

    private fun instance() = InstanceConfig(
        id = "i",
        name = "n",
        agentId = "a",
        addresses = listOf(AddressEntry("http://10.0.0.5:8088")),
        token = "tok",
        username = "user",
        basicUser = "proxy-user",
        basicPassword = "proxy-pass",
    )

    @Test
    fun `strips token and basic password but keeps usernames`() {
        val out = InstanceStore.stripSecrets(listOf(instance())).single()
        assertNull(out.token)
        assertNull(out.basicPassword)
        assertEquals("user", out.username)
        assertEquals("proxy-user", out.basicUser)
        assertEquals("http://10.0.0.5:8088", out.addresses.single().url)
    }
}
