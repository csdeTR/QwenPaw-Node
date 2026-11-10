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

/**
 * 把用户输入整理成可比较的 host；无法识别时返回 null。
 *
 * 用户在设置里粘贴的往往是一整条地址（`https://Example.COM:8088/path`），
 * 而地址检查拿到的是解析后的主机名。两者必须先收敛到同一形态才能比较，
 * 因此这里去掉协议、端口、路径与末尾的点，并把方括号形式的 IPv6 解成裸地址。
 *
 * 只按 host 记、不按端口记：同一台机器换个端口不改变风险属性，
 * 按端口记会让用户为 `10.0.0.5:8088` 与 `10.0.0.5:9000` 各加一次。
 */
internal fun normalizeTrustedHost(raw: String): String? {
    var host = raw.trim()
    if (host.isEmpty()) return null
    // 协议前缀大小写不敏感：`HTTPS://` 也是常见写法。
    val lower = host.lowercase()
    host = when {
        lower.startsWith("https://") -> host.substring(8)
        lower.startsWith("http://") -> host.substring(7)
        else -> host
    }
    // 路径、查询串与锚点都不属于主机名，一律截掉。
    val cut = host.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (cut >= 0) host = host.substring(0, cut)
    host = when {
        // 方括号只用于给 IPv6 加端口，取出括号内的裸地址。
        host.startsWith("[") -> {
            val end = host.indexOf(']')
            if (end < 0) return null
            host.substring(1, end)
        }
        // 多个冒号只可能是 IPv6，原样保留；单个冒号后面是端口，去掉。
        host.count { it == ':' } > 1 -> host
        ':' in host -> host.substringBefore(':')
        else -> host
    }
    host = host.trimEnd('.').lowercase()
    if (host.isEmpty()) return null
    // 域名与 IP 字面量只允许这些字符：空白、路径残留、`@` 都在这里被挡掉。
    if (host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '.' && it != ':' }) {
        return null
    }
    return host
}

/**
 * 判断一次地址检查得到的 host 是否在受信任集合里。
 *
 * 两侧都过 [normalizeTrustedHost]：大小写、带不带端口、带不带方括号都不该影响命中，
 * 而集合里存的是过去写入的值，不能假设它一定已经归一化。
 * 任一侧无法识别时按不匹配处理——宁可多弹一次提示，也不能凭一条读不懂的记录放行。
 */
internal fun isTrustedHost(host: String, trustedHosts: Set<String>): Boolean {
    if (trustedHosts.isEmpty()) return false
    val target = normalizeTrustedHost(host) ?: return false
    return trustedHosts.any { normalizeTrustedHost(it) == target }
}