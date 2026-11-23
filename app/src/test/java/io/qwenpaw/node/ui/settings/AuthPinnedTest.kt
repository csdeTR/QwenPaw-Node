package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.EnvVarDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPinnedTest {

    @Test
    fun `unset auth is pinned`() {
        assertTrue(authPinned(emptyList()))
    }

    @Test
    fun `false auth is pinned regardless of case`() {
        assertTrue(
            authPinned(listOf(EnvVarDto(key = "QWENPAW_AUTH_ENABLED", value = "FALSE"))),
        )
        assertTrue(
            authPinned(listOf(EnvVarDto(key = "QWENPAW_AUTH_ENABLED", value = " false "))),
        )
    }

    @Test
    fun `enabled auth is not pinned`() {
        assertFalse(
            authPinned(listOf(EnvVarDto(key = "QWENPAW_AUTH_ENABLED", value = "true"))),
        )
        assertFalse(
            authPinned(listOf(EnvVarDto(key = "QWENPAW_AUTH_ENABLED", value = "1"))),
        )
    }

    @Test
    fun `other variables do not affect pin`() {
        assertTrue(authPinned(listOf(EnvVarDto(key = "OTHER", value = "true"))))
    }
}
