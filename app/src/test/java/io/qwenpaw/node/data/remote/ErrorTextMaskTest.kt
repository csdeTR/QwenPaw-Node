package io.qwenpaw.node.data.remote

import io.qwenpaw.node.TestTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 错误原文里的凭据必须被掩掉，再交给界面。 */
class ErrorTextMaskTest {

    @Before
    fun setUp() {
        TestTexts.install()
    }

    @Test
    fun `masks secret fields`() {
        val out = ErrorText.maskSecrets(
            """{"token":"abc123","password":"pw","api_key":"key","authorization":"Bearer xyz"}""",
        )
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("\"pw\""))
        assertFalse(out.contains("\"key\""))
        assertFalse(out.contains("xyz"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `masks bearer and query token`() {
        val out = ErrorText.maskSecrets(
            "failed Authorization: Bearer eyJhbGciOi.abc and http://h/api?token=secret&x=1",
        )
        assertFalse(out.contains("eyJhbGciOi"))
        assertFalse(out.contains("secret"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `truncates very long detail`() {
        val out = ErrorText.maskSecrets("x".repeat(1000))
        assertTrue(out.length < 500)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `bridge trusted address 401 is actionable`() {
        val detail = """{"code":"trusted_address_required","message":"来源地址不在私有网段"}"""
        assertEquals(
            "请在 Bridge 设置页添加受信任地址，并配置匹配的来源网段",
            ErrorText.of(ApiException(401, detail, tokenSent = true)),
        )
    }
}
