package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlTest {

    @Test
    fun `https url is accepted`() {
        val check = validateBrowserUrl(" https://example.com/docs ")
        assertTrue(check.ok)
        assertEquals("https://example.com/docs", check.url)
        assertEquals("example.com", check.host)
        assertNull(check.error)
    }

    @Test
    fun `http url with port is accepted`() {
        val check = validateBrowserUrl("http://10.10.0.211:8088/")
        assertTrue(check.ok)
        assertEquals("10.10.0.211", check.host)
    }

    @Test
    fun `javascript scheme is rejected`() {
        assertEquals(BrowserUrlError.SCHEME, validateBrowserUrl("javascript:alert(1)").error)
    }

    @Test
    fun `file scheme is rejected`() {
        assertEquals(BrowserUrlError.SCHEME, validateBrowserUrl("file:///data/local/tmp/x").error)
    }

    @Test
    fun `intent scheme is rejected`() {
        assertEquals(BrowserUrlError.SCHEME, validateBrowserUrl("intent://scan").error)
    }

    @Test
    fun `empty url is rejected`() {
        assertEquals(BrowserUrlError.EMPTY, validateBrowserUrl("  ").error)
    }

    @Test
    fun `url without host is rejected`() {
        assertEquals(BrowserUrlError.HOST, validateBrowserUrl("https:///path").error)
    }
}
