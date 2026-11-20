package io.qwenpaw.node.data

import androidx.annotation.StringRes
import io.qwenpaw.node.R

/** 编辑实例时界面上已有的凭据。密码与令牌都不回显，因此只能用于「留空保留」。 */
data class StoredCredentials(
    val username: String?,
    val basicUser: String?,
    val basicPassword: String?,
    /** 已保存的访问令牌；令牌模式下留空表示保留它。 */
    val token: String? = null,
)

/** 连接时用哪一套凭据。 */
enum class CredentialMode {
    /** 用户名与密码，向 `/api/auth/login` 换取令牌（原有方式）。 */
    PASSWORD,

    /** 直接用令牌（从网页控制台设备配对、扫码或其他方式取得）。 */
    TOKEN,
}

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

    /** 用一枚现成的令牌校验后落盘。 */
    data class UseToken(
        val token: String,
        val basicUser: String?,
        val basicPassword: String?,
    ) : CredentialPlan

    /** 输入不完整，界面直接显示原因，不发网络请求、不改配置。 */
    data class Invalid(@StringRes val messageRes: Int) : CredentialPlan
}

/**
 * 决定编辑实例时要不要重新登录。
 *
 * 三条与直觉相反的规则：
 * 1. **密码与令牌留空都表示保留原值**，只在已有值生效；没有旧值时留空是输入不完整。
 * 2. **只改用户名不足以重新登录**，必须同时填密码，否则无法验证新账号。
 * 3. **换登录方式本身就是一次重新认证**：从密码改成令牌而令牌留空，不能静默保留
 *    密码换来的旧令牌——那样用户以为换了方式，实际什么都没变。
 */
internal fun planCredentials(
    typedUsername: String,
    typedPassword: String,
    typedBasicUser: String,
    typedBasicPassword: String,
    stored: StoredCredentials,
    mode: CredentialMode = CredentialMode.PASSWORD,
    /** 已保存的令牌；为 null 表示该实例还没有令牌。 */
    storedToken: String? = null,
): CredentialPlan {
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

    if (mode == CredentialMode.TOKEN) {
        val token = typedPassword.trim()
        return when {
            token.isNotEmpty() -> CredentialPlan.UseToken(
                token = token,
                basicUser = resolvedBasic.first,
                basicPassword = resolvedBasic.second,
            )

            !storedToken.isNullOrBlank() && stored.username.isNullOrBlank() ->
                // 该实例本来就是令牌方式，留空即保留。
                CredentialPlan.UseExisting(resolvedBasic.first, resolvedBasic.second)

            else -> CredentialPlan.Invalid(R.string.connect_cred_token_required)
        }
    }

    val username = typedUsername.trim()
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