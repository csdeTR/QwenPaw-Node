package io.qwenpaw.node.data.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 令牌载荷的读取。只用于界面展示，不验签。 */
class TokenClaimsTest {

    private fun token(payloadJson: String): String {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$encoded.deadbeef"
    }

    @Test
    fun `reads subject expiry and id`() {
        val claims = TokenClaimsCodec.decodeUnverified(
            token("""{"sub":"alice","iat":1700000000,"exp":1800000000,"jti":"abc"}"""),
        )
        claims!!
        assertEquals("alice", claims.subject)
        assertEquals(1_700_000_000L, claims.issuedAt)
        assertEquals(1_800_000_000L, claims.expiresAt)
        assertEquals("abc", claims.id)
    }

    @Test
    fun `missing optional fields are null`() {
        val claims = TokenClaimsCodec.decodeUnverified(token("""{"sub":"alice"}"""))
        claims!!
        assertNull(claims.expiresAt)
        assertNull(claims.issuedAt)
        assertNull(claims.id)
    }

    @Test
    fun `non numeric expiry is ignored instead of crashing`() {
        val claims = TokenClaimsCodec.decodeUnverified(token("""{"exp":"soon"}"""))
        claims!!
        assertNull(claims.expiresAt)
    }

    @Test
    fun `malformed payload returns null`() {
        assertNull(TokenClaimsCodec.decodeUnverified(""))
        assertNull(TokenClaimsCodec.decodeUnverified("!!!"))
        assertNull(TokenClaimsCodec.decodeUnverified("bm90LWpzb24.c2ln"))
    }

    @Test
    fun `token without signature part still reads payload`() {
        // 服务端签发的一定带签名段；这里只是固定「读载荷不依赖签名」。
        val claims = TokenClaimsCodec.decodeUnverified(
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"sub":"a"}""".toByteArray()),
        )
        assertEquals("a", claims?.subject)
    }

    @Test
    fun `padded base64url is accepted`() {
        val payload = java.util.Base64.getUrlEncoder()
            .encodeToString("""{"sub":"a"}""".toByteArray()) // 带 '='
        assertEquals("a", TokenClaimsCodec.decodeUnverified("$payload.sig")?.subject)
    }
}