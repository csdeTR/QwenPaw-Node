package io.qwenpaw.node.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileStoreTest {

    private fun profile(auth: SshAuthType = SshAuthType.KEY) = SshProfile(
        addresses = listOf(
            SshAddress(
                id = "a1",
                alias = "内网",
                host = "10.0.0.2",
                port = 22222,
                user = "csdetr",
                default = true,
                preferOnVpn = true,
            ),
        ),
        auth = auth,
        shell = TerminalShell.BASH,
        sameHost = false,
        platform = TerminalPlatform.WINDOWS,
    )

    @Test
    fun `profile and key survive a round trip`() {
        val store = SshProfileStore(FakeSharedPreferences())
        val saved = profile()
        assertTrue(store.save("inst", saved, SshSecret("PRIVATE-KEY", "pass")))

        val loaded = store.load("inst")!!
        assertEquals("10.0.0.2", loaded.addresses.single().host)
        assertEquals(22222, loaded.addresses.single().port)
        assertTrue(loaded.addresses.single().preferOnVpn)
        assertEquals(TerminalShell.BASH, loaded.shell)
        assertTrue(loaded.sameHost == false)
        assertEquals(TerminalPlatform.WINDOWS, loaded.platform)
        assertTrue(store.hasCredentials("inst", SshAuthType.KEY))
        assertEquals("PRIVATE-KEY", store.secret("inst").privateKey())
        assertFalse(store.secret("inst").toString().contains("PRIVATE-KEY"))
    }

    @Test
    fun `metadata-only save keeps existing secret`() {
        val store = SshProfileStore(FakeSharedPreferences())
        store.save("inst", profile(), SshSecret("KEY", null))
        assertTrue(store.save("inst", profile().copy(shell = TerminalShell.DASH)))
        assertEquals("KEY", store.secret("inst").privateKey())
    }

    @Test
    fun `switching auth removes the unused secret`() {
        val store = SshProfileStore(FakeSharedPreferences())
        store.save("inst", profile(), SshSecret("KEY", "phrase"))
        store.save(
            "inst",
            profile(auth = SshAuthType.PASSWORD),
            SshSecret(password = "secret"),
        )
        assertFalse(store.hasCredentials("inst", SshAuthType.KEY))
        assertTrue(store.hasCredentials("inst", SshAuthType.PASSWORD))
        assertNull(store.secret("inst").privateKey())
        assertEquals("secret", store.secret("inst").password())
    }

    @Test
    fun `delete removes metadata and secrets`() {
        val store = SshProfileStore(FakeSharedPreferences())
        store.save("inst", profile(), SshSecret("KEY", null))
        store.delete("inst")
        assertNull(store.load("inst"))
        assertFalse(store.hasCredentials("inst", SshAuthType.KEY))
    }

    @Test
    fun `unavailable store refuses writes`() {
        val store = SshProfileStore(null)
        assertFalse(store.available)
        assertFalse(store.save("inst", profile(), SshSecret("KEY", null)))
        assertNull(store.load("inst"))
    }
}
