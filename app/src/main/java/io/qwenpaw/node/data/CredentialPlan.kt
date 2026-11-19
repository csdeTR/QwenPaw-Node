package io.qwenpaw.node.data

import androidx.annotation.StringRes
import io.qwenpaw.node.R

/** 编辑实例时界面上已有的凭据。密码不回显，因此只能用于「留空保留」。 */
data class StoredCredentials(
    val username: String?,
    val basicUser: String?,
    val basicPassword: String?,
)

/** 编辑实例时对登录凭据与反代凭据的处理计划。 */
sealed interface CredentialPlan {
    /** 不重新登录，保留已保存的 token；反代凭据按计划更新或清除。 */
    data class UseExisting(
        val basicUser: String?,
        val basicPassword: String?,
    ) : CredentialPlan

    /** 重新登录后再落盘。 */
    data class Login(
        val username: String,
        val password: String,
        val basicUser: String?,
        val basicPassword: String?,
    ) : CredentialPlan

    /** 输入不完整，界面直接显示原因，不发网络请求、不改配置。 */
    data class Invalid(@StringRes val messageRes: Int) : CredentialPlan
}

/**
 * 决定编辑实例时要不要重新登录。
 *
 * 两条与直觉相反的规则：
 * 1. **密码留空表示保留原值**，只在已有值生效；没有旧密码时留空是输入不完整。
 * 2. **只改用户名不足以重新登录**，必须同时填密码，否则无法验证新账号。
 */
internal fun planCredentials(
    typedUsername: String,
    typedPassword: String,
    typedBasicUser: String,
    typedBasicPassword: String,
    stored: StoredCredentials,
): CredentialPlan {
    val username = typedUsername.trim()
    val basicUser = typedBasicUser.trim()

    val resolvedBasic: Pair<String?, String?> = when {
        basicUser.isBlank() && typedBasicPassword.isBlank() -> null to null
        basicUser.isBlank() ->
            return CredentialPlan.Invalid(R.string.connect_cred_proxy_user_required)
        typedBasicPassword.isBlank() && stored.basicPassword.isNullOrBlank() ->
            return CredentialPlan.Invalid(R.string.connect_cred_proxy_password_required)
        typedBasicPassword.isBlank() -> basicUser to stored.basicPassword
        else -> basicUser to typedBasicPassword
    }

    return when {
        username.isBlank() && typedPassword.isBlank() ->
            CredentialPlan.UseExisting(resolvedBasic.first, resolvedBasic.second)

        username.isBlank() ->
            return CredentialPlan.Invalid(R.string.connect_cred_username_password_required)

        typedPassword.isBlank() && username == stored.username ->
            CredentialPlan.UseExisting(resolvedBasic.first, resolvedBasic.second)

        typedPassword.isBlank() ->
            return CredentialPlan.Invalid(R.string.connect_cred_username_password_required)

        else -> CredentialPlan.Login(
            username = username,
            password = typedPassword,
            basicUser = resolvedBasic.first,
            basicPassword = resolvedBasic.second,
        )
    }
}
