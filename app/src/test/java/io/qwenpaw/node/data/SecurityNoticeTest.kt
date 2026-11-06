package io.qwenpaw.node.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全提示的判定。
 *
 * 判据的核心是「公网可达」：内网直连既没有暴露面，也不需要为它配证书，
 * 提示只会变成噪声。误报的代价比漏报高——一次假的「你的服务器暴露在广域网」
 * 会让之后的真告警一起被忽略。
 */
class SecurityNoticeTest {

    @Test
    fun `public host without auth is exposed`() {
        val notice = assessSecurity("http://chat.example.com:8088", loginRequired = false)
        assertTrue(notice.publicHost)
        assertTrue(notice.exposed)
        assertTrue(notice.noTls)
        assertTrue(notice.any)
    }

    @Test
    fun `public host over plain http needs tls`() {
        val notice = assessSecurity("http://chat.example.com:8088", loginRequired = true)
        assertFalse(notice.exposed)
        assertTrue(notice.noTls)
        assertTrue(notice.any)
    }

    /** https + 已启用登录：两个风险都不成立。 */
    @Test
    fun `public https with auth is clean`() {
        val notice = assessSecurity("https://chat.example.com", loginRequired = true)
        assertTrue(notice.publicHost)
        assertFalse(notice.exposed)
        assertFalse(notice.noTls)
        assertFalse(notice.any)
    }

    /** 公网 + https + 无登录：只剩「暴露」这一条。 */
    @Test
    fun `public https without auth is exposed only`() {
        val notice = assessSecurity("https://chat.example.com", loginRequired = false)
        assertTrue(notice.exposed)
        assertFalse(notice.noTls)
    }

    @Test
    fun `private addresses are never flagged`() {
        listOf(
            "http://10.10.0.211:8088",
            "http://172.16.0.5:8088",
            "http://192.168.1.10:8088",
            "http://127.0.0.1:8088",
            "http://localhost:8088",
            "http://paw.local:8088",
            "http://paw.lan:8088",
            "http://100.64.0.1:8088",
        ).forEach { url ->
            val notice = assessSecurity(url, loginRequired = false)
            assertFalse("$url 不应被判为公网", notice.publicHost)
            assertFalse("$url 不应产生提示", notice.any)
        }
    }

    /** 解析失败时按「无风险」处理，不弹窗。 */
    @Test
    fun `unparsable url never alerts`() {
        assertFalse(assessSecurity("not a url", loginRequired = false).any)
        assertFalse(assessSecurity("", loginRequired = false).any)
    }

    /** 私有段边界：172.32 已经不在私有段里。 */
    @Test
    fun `private range boundaries are respected`() {
        assertTrue(assessSecurity("http://172.31.0.1:8088", loginRequired = false).publicHost.not())
        assertTrue(assessSecurity("http://172.32.0.1:8088", loginRequired = false).publicHost)
    }
}