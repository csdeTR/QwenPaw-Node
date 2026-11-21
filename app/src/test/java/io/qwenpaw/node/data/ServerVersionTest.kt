package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionTest {

    @Test
    fun `post release is newer than plain release`() {
        val post = ServerVersion.parse("2.3.0.post1")!!
        val plain = ServerVersion.parse("2.3.0")!!
        assertTrue(post > plain)
        assertTrue(plain < post)
    }

    @Test
    fun `pre release is older than plain release`() {
        val beta = ServerVersion.parse("2.3.0b2")!!
        val plain = ServerVersion.parse("2.3.0")!!
        assertTrue(beta < plain)
        assertFalse(plain < beta)
    }

    @Test
    fun `minor and patch versions compare numerically`() {
        val newer = ServerVersion.parse("2.3.1")!!
        val channelsMin = ServerVersion.CHANNELS_MIN
        assertTrue(newer > channelsMin)
        assertFalse(channelsMin > newer)
    }

    @Test
    fun `post numbers compare numerically`() {
        assertTrue(
            ServerVersion.parse("2.3.0.post10")!! > ServerVersion.parse("2.3.0.post1")!!,
        )
    }

    @Test
    fun `rc is older than release`() {
        assertTrue(ServerVersion.parse("2.3.0rc1")!! < ServerVersion.parse("2.3.0")!!)
    }

    @Test
    fun `v prefix and two segment versions are accepted`() {
        assertEquals(ServerVersion(2, 3, 1), ServerVersion.parse("v2.3.1"))
        assertEquals(ServerVersion(2, 3), ServerVersion.parse("2.3"))
    }

    @Test
    fun `unknown versions return null`() {
        assertNull(ServerVersion.parse(null))
        assertNull(ServerVersion.parse(""))
        assertNull(ServerVersion.parse("unknown"))
    }
}
