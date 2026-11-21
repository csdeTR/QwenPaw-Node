package io.qwenpaw.node.data

import io.qwenpaw.node.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑实例时的凭据组合。重点是「密码留空保留原值」与「改用户名必须带密码」。 */
class CredentialPlanTest {

    private val stored = StoredCredentials(
        username = "alice",
        basicUser = "proxy",
        basicPassword = "proxy-pass",
    )

    @Test
    fun `blank credentials keep existing token`() {
        val plan = planCredentials("", "", "", "", stored)
        assertTrue(plan is CredentialPlan.UseExisting)
        plan as CredentialPlan.UseExisting
        assertEquals(null, plan.basicUser)
        assertEquals(null, plan.basicPassword)
    }

    @Test
    fun `same username without password keeps existing token`() {
        val plan = planCredentials("alice", "", "", "", stored)
        assertTrue(plan is CredentialPlan.UseExisting)
    }

    @Test
    fun `changed username without password is rejected`() {
        val plan = planCredentials("bob", "", "", "", stored)
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_username_password_required, plan.messageRes)
    }

    @Test
    fun `username with password triggers login`() {
        val plan = planCredentials("alice", "new-pass", "", "", stored)
        plan as CredentialPlan.Login
        assertEquals("alice", plan.username)
        assertEquals("new-pass", plan.password)
    }

    @Test
    fun `proxy username without password keeps stored password`() {
        val plan = planCredentials("", "", "new-proxy", "", stored)
        plan as CredentialPlan.UseExisting
        assertEquals("new-proxy", plan.basicUser)
        assertEquals("proxy-pass", plan.basicPassword)
    }

    @Test
    fun `proxy username without stored password is rejected`() {
        val plan = planCredentials(
            "", "", "proxy", "",
            StoredCredentials(username = null, basicUser = null, basicPassword = null),
        )
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_proxy_password_required, plan.messageRes)
    }

    @Test
    fun `proxy password without username is rejected`() {
        val plan = planCredentials("", "", "", "secret", stored)
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_proxy_user_required, plan.messageRes)
    }

    @Test
    fun `password without username is rejected`() {
        val plan = planCredentials("", "secret", "", "", stored)
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_username_password_required, plan.messageRes)
    }

    @Test
    fun `clearing both proxy fields clears stored basic`() {
        val plan = planCredentials("alice", "", "", "", stored)
        plan as CredentialPlan.UseExisting
        assertEquals(null, plan.basicUser)
        assertEquals(null, plan.basicPassword)
    }

    // ---- 访问令牌 ----

    @Test
    fun `filled token is used as is`() {
        val plan = planCredentials(
            "", "", "", "", stored,
            mode = CredentialMode.TOKEN,
            typedToken = "abc.def",
        )
        plan as CredentialPlan.UseToken
        assertEquals("abc.def", plan.token)
    }

    @Test
    fun `token mode reads the token field not the password field`() {
        val plan = planCredentials(
            "ignored", "should-not-be-used", "", "", stored,
            mode = CredentialMode.TOKEN,
            typedToken = "tok123",
        )
        plan as CredentialPlan.UseToken
        assertEquals("tok123", plan.token)
    }

    @Test
    fun `blank token in token mode keeps the saved token`() {
        val plan = planCredentials(
            "", "", "", "",
            StoredCredentials(
                username = "alice",
                basicUser = null,
                basicPassword = null,
                token = "saved",
            ),
            mode = CredentialMode.TOKEN,
            storedToken = "saved",
            storedMode = CredentialMode.TOKEN,
        )
        assertTrue("令牌实例的 username 可能非空，不能因此拒绝保留", plan is CredentialPlan.UseExisting)
    }

    @Test
    fun `blank token in token mode without saved token is rejected`() {
        val plan = planCredentials(
            "", "", "", "", stored,
            mode = CredentialMode.TOKEN,
        )
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_token_required, plan.messageRes)
    }

    @Test
    fun `switching to token mode does not reuse the password login token`() {
        // 用户从密码改成令牌却没填令牌：这时静默保留密码换来的旧令牌会让
        // 「换了登录方式」变成一句空话。
        val plan = planCredentials(
            "alice", "", "", "", stored,
            mode = CredentialMode.TOKEN,
            storedToken = "old",
            storedMode = CredentialMode.PASSWORD,
        )
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_token_required, plan.messageRes)
    }

    @Test
    fun `switching from token to password requires a password`() {
        val plan = planCredentials(
            "alice", "", "", "",
            StoredCredentials(
                username = "alice",
                basicUser = null,
                basicPassword = null,
                token = "saved",
            ),
            mode = CredentialMode.PASSWORD,
            storedToken = "saved",
            storedMode = CredentialMode.TOKEN,
        )
        plan as CredentialPlan.Invalid
        assertEquals(R.string.connect_cred_username_password_required, plan.messageRes)
    }

    @Test
    fun `token mode still resolves proxy credentials`() {
        val plan = planCredentials(
            "", "", "new-proxy", "new-pass", stored,
            mode = CredentialMode.TOKEN,
            typedToken = "tok",
        )
        plan as CredentialPlan.UseToken
        assertEquals("new-proxy", plan.basicUser)
        assertEquals("new-pass", plan.basicPassword)
    }
}
