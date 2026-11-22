package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片只有与实例同源时才允许附带任何凭据。 */
class RemoteImageOriginTest {

    @Test
    fun `same scheme host and port is same origin`() {
        assertTrue(
            isSameOrigin(
                "http://10.10.0.211:8088",
                "http://10.10.0.211:8088/api/files/preview/a.png",
            ),
        )
    }

    @Test
    fun `different host is cross origin`() {
        assertFalse(
            isSameOrigin("http://10.10.0.211:8088", "http://attacker.example/a.png"),
        )
    }

    @Test
    fun `different port is cross origin`() {
        assertFalse(
            isSameOrigin("http://10.10.0.211:8088", "http://10.10.0.211:9090/a.png"),
        )
    }

    @Test
    fun `different scheme is cross origin`() {
        assertFalse(
            isSameOrigin("https://chat.example.com", "http://chat.example.com/a.png"),
        )
    }

    @Test
    fun `invalid base is cross origin`() {
        assertFalse(isSameOrigin("", "http://10.10.0.211:8088/a.png"))
    }
}
