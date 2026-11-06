package io.qwenpaw.node.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 401 的三类成因。
 *
 * 这组用例对应三个真实场景：反代要 Basic 凭据、登录密码打错、已持有的 token 失效。
 * 此前它们共用一句「登录已失效，请重新登录」，用户看到的是「我根本没登录过，
 * 怎么失效了」——文案把人引向一个不存在状态。
 */
class ErrorTextAuthTest {

    @Test
    fun `www-authenticate basic wins over detail`() {
        // 反代的 401 里 detail 可能是 HTML 或空串，按 detail 猜会猜错。
        assertEquals(
            ErrorText.AuthFailure.PROXY_BASIC,
            ErrorText.classify401("Not Found", "Basic realm=\"restricted\""),
        )
        assertEquals(
            ErrorText.AuthFailure.PROXY_BASIC,
            ErrorText.classify401("Invalid username or password", "BASIC realm=x"),
        )
    }

    @Test
    fun `invalid credentials is its own case`() {
        assertEquals(
            ErrorText.AuthFailure.BAD_CREDENTIALS,
            ErrorText.classify401("Invalid username or password", null),
        )
        assertEquals("用户名或密码不正确", ErrorText.of(ApiException(401, "Invalid username or password")))
    }

    @Test
    fun `expired token keeps the original wording`() {
        assertEquals(
            ErrorText.AuthFailure.TOKEN_EXPIRED,
            ErrorText.classify401("Invalid or expired token", null, tokenSent = true),
        )
        assertEquals("登录已失效，请重新登录", ErrorText.of(ApiException(401, "Invalid or expired token")))
        // 服务端对「没带 token」与「token 失效」都写 Not authenticated，
        // 只能靠这次请求是否带过 token 区分。
        assertEquals(
            "登录已失效，请重新登录",
            ErrorText.of(ApiException(401, "Not authenticated", tokenSent = true)),
        )
    }

    @Test
    fun `no token and generic detail is treated as not logged in`() {
        // 服务端对「没带 token」与「token 失效」都写 Not authenticated，
        // 只能靠这次请求是否带过 token 区分。
        assertEquals(
            ErrorText.AuthFailure.LOGIN_REQUIRED,
            ErrorText.classify401("Not authenticated", null, tokenSent = false),
        )
        // 本地从没登录过时不能说「已失效」，否则用户会去找一个不存在的失效状态。
        val text = ErrorText.of(ApiException(401, "Not Found", tokenSent = false))
        assertFalse(text.contains("失效"))
        assertTrue(text.contains("登录"))
    }

    @Test
    fun `unknown 401 keeps server detail`() {
        val text = ErrorText.of(ApiException(401, "quota exceeded", tokenSent = true))
        assertEquals("quota exceeded", text)
    }

    /** 反代要凭据时，排查建议指向「去设置里填」而不是「改成 https」。 */
    @Test
    fun `proxy auth hint points at the credential field`() {
        val e = ApiException(401, "401", wwwAuthenticate = "Basic realm=x")
        assertEquals(ErrorText.PROXY_AUTH_REQUIRED, ErrorText.hint(e, usedHttps = false))
        assertTrue(ErrorText.ofWithHint(e, usedHttps = false).contains("反向代理"))
    }

    /** 已经填了凭据仍被拒：用户要核对自己填的那一组，不能再说「需要填写」。 */
    @Test
    fun `rejected proxy credentials are reported as wrong`() {
        val e = ApiException(
            401,
            "401",
            userHint = "反向代理拒绝了这组账号密码，请核对后重试",
            wwwAuthenticate = "Basic realm=x",
        )
        assertTrue(ErrorText.of(e).contains("核对"))
        // userHint 已给出准确说法，不再叠加排查建议。
        assertNull(ErrorText.hint(e, usedHttps = false))
    }

    /** 密码打错与协议无关，不该再叠一条「改成 https」。 */
    @Test
    fun `bad credentials has no extra hint`() {
        val e = ApiException(401, "Invalid username or password")
        assertNull(ErrorText.hint(e, usedHttps = false))
    }

    /** 缺凭据抛的 401 带 userHint，不能被映射成「登录已失效」。 */
    @Test
    fun `missing credentials message is not shown as expired login`() {
        val msg = "该实例已开启登录鉴权，需要用户名和密码"
        val text = ErrorText.of(ApiException(401, msg, msg, tokenSent = false))
        assertEquals(msg, text)
        assertFalse(text.contains("登录已失效"))
    }
}