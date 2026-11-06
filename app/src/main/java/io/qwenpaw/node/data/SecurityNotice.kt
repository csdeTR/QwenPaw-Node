package io.qwenpaw.node.data

import io.qwenpaw.node.data.remote.QwenPawApi

/**
 * 一次地址安全检查的结论。
 *
 * 只有两种风险值得提醒，它们的处置完全不同：
 *
 * | 风险 | 后果 | 处置 |
 * | --- | --- | --- |
 * | 公网地址 + 未启用登录 | 任何人都能操作这台机器上的智能体 | 设置 `QWENPAW_AUTH_ENABLED=true` |
 * | 公网地址 + 明文 http | 密码、token 与对话内容在链路上可见 | 配置 SSL 证书并改用 https |
 *
 * 两个判断都以「地址会被公网访问到」为前提：内网直连既没暴露面，
 * 也没必要为它配证书，提示只会变成噪声。
 */
data class SecurityNotice(
    /** 判定用的主机名。 */
    val host: String,
    /** 非 localhost、非私有网段。 */
    val publicHost: Boolean,
    /** 实际访问用的是明文 http。 */
    val plaintext: Boolean,
    /** 服务端未启用登录（`enabled && has_users` 为假）。 */
    val authDisabled: Boolean,
) {
    /** 公网可达且没有登录：会把整台机器交出去，最严重的一条。 */
    val exposed: Boolean get() = publicHost && authDisabled

    /** 公网可达且明文传输：密码与对话内容在链路上可读。 */
    val noTls: Boolean get() = publicHost && plaintext

    /** 是否有任何一条需要提示。 */
    val any: Boolean get() = exposed || noTls
}

/**
 * 判定一个地址的安全风险。
 *
 * [loginRequired] 取服务端 `GET /api/auth/status` 的 `enabled && has_users`——
 * 与「是否要填账号」用的是同一个结论。参数名不叫 `authEnabled`：
 * `QWENPAW_AUTH_ENABLED=true` 只是开关，没有注册用户时服务端仍不要求登录，
 * 两者混用会直接把提示条件写反。
 *
 * **宁可少提示，不可误报。** 地址解析失败、主机名为空时一律返回「无风险」：
 * 一次错误的「你的服务器暴露在广域网」会让用户怀疑整个提示机制，
 * 之后的真告警也会被忽略。
 */
internal fun assessSecurity(url: String, loginRequired: Boolean): SecurityNotice {
    val parsed = runCatching { QwenPawApi.resolve(url, "/") }.getOrNull()
        ?: return SecurityNotice(
            host = "",
            publicHost = false,
            plaintext = false,
            authDisabled = !loginRequired,
        )
    val host = parsed.host
    return SecurityNotice(
        host = host,
        publicHost = !InstanceRepository.isLocalHost(host),
        plaintext = parsed.scheme == "http",
        authDisabled = !loginRequired,
    )
}