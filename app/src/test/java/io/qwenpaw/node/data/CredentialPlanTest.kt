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
}
