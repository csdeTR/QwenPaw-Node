package io.qwenpaw.node.data.remote

/**
 * 把异常映射成给用户看的中文提示。
 *
 * 抽成公共对象是因为连接页与聊天页都需要这套映射：先前连接页直接把
 * `e.message` 显示出来，用户在界面上看到的是 `Failed to connect to /10.0.2.2:8099`
 * 这类英文异常，既看不懂也判断不出是地址错了还是服务端没起。
 *
 * 判定顺序要紧：
 * 1. [ApiException] 是业务错误，先按状态码给结论——地址是通的，不能报成「连不上」。
 * 2. `UnknownHostException` 比 `ConnectException` 具体，先判前者。
 * 3. OkHttp 对丢包地址抛的是普通 `IOException`，类型上看不出来，
 *    只能再按文案里的 `Failed to connect` 兜一层。
 */
object ErrorText {

    fun of(e: Throwable): String = when (e) {
        // 调用方给了更准确的说法时优先用它：同一个状态码在不同接口上的含义不同。
        is ApiException -> e.userHint?.takeIf { it.isNotBlank() } ?: when (e.code) {
            // [ApiException.detail] 只在网络层构造时才有值；其它地方直接 new 出来的
            // 异常把原文放在 message 里，两者都要看，否则分类永远落到 OTHER。
            401 -> authFailureText(
                classify401(e.detail ?: e.message, e.wwwAuthenticate, e.tokenSent),
                e.detail ?: e.message,
            )
            404 -> "对话不存在或已被删除"
            409 -> "该对话已有任务在运行，请先停止或等待完成"
            503 -> "Console 频道未启用，请在服务端开启"
            else -> e.message
        }

        else -> when {
            e.hasCause<java.net.UnknownHostException>() -> "找不到服务器，请检查地址与网络"
            e.hasCause<java.net.ConnectException>() || e.isConnectFailure() ->
                "无法连接服务器，请确认 QwenPaw 已启动且地址端口正确"

            e.hasCause<java.net.SocketTimeoutException>() -> "连接超时，请检查网络后重试"
            e is java.io.IOException -> "网络中断：请检查网络后重试（草稿已保存）"
            else -> e.message ?: "未知错误"
        }
    }

    /** 「服务端只接受 https」的排查建议。 */
    const val HTTPS_REQUIRED = "若服务端只接受 https，请把地址改成 https:// 开头，或核对端口"

    /** 「服务端只提供 http」的排查建议。 */
    const val HTTP_ONLY = "该地址可能只提供 http，可尝试改成 http:// 开头"

    /** 反向代理要求 HTTP Basic 时的排查建议。 */
    const val PROXY_AUTH_REQUIRED = "在「设置 → 服务器」里为该实例填写反向代理账号与密码"

    /**
     * 401 的真实成因。
     *
     * 三种情况的处置完全不同：反代要填 Basic 凭据、密码错要重填账号、
     * token 过期要重新登录。此前它们共用一句「登录已失效，请重新登录」，
     * 于是「用户名密码打错」被说成「登录已失效」，用户会去找一个不存在的失效状态。
     */
    enum class AuthFailure {
        /** 反向代理（nginx / openresty）的 `auth_basic` 拒绝了请求。 */
        PROXY_BASIC,

        /** 服务端要求登录，而这次请求根本没带 token（本机还没有凭据）。 */
        LOGIN_REQUIRED,

        /** 登录接口本身拒绝：用户名或密码不对。 */
        BAD_CREDENTIALS,

        /** 已持有的 token 失效。 */
        TOKEN_EXPIRED,

        /** 以上都不是，只能把服务端原文交给用户。 */
        OTHER,
    }

    /**
     * 判定一次 401 的成因。
     *
     * 判定顺序要紧：`WWW-Authenticate: Basic` 是反代加的，服务端自身从不发这个头，
     * 因此它出现时一定是反代先拦下了请求，detail 可能是反代的 HTML 或空串，
     * 不能再按 detail 猜。
     *
     * [tokenSent] 表示这次请求带过 token。没带 token 又被要求认证，
     * 只可能是「服务端要登录但本地没有凭据」，而不是「凭据过期」。
     */
    fun classify401(
        detail: String?,
        wwwAuthenticate: String?,
        tokenSent: Boolean = false,
    ): AuthFailure {
        val challenge = wwwAuthenticate.orEmpty()
        if (challenge.contains("basic", ignoreCase = true)) return AuthFailure.PROXY_BASIC
        val text = detail.orEmpty()
        return when {
            text.contains("Invalid username or password", ignoreCase = true) ->
                AuthFailure.BAD_CREDENTIALS

            // 服务端只有真的收到并核对过 token，才会说「过期」。
            text.contains("Invalid or expired token", ignoreCase = true) ->
                AuthFailure.TOKEN_EXPIRED

            // 没带 token 的 401 只说明「服务端要登录」：本地从没登录过，
            // 没有可失效的东西。必须排在 `Not authenticated` 之前——服务端对
            // 「没带 token」与「token 失效」写的是同一句，只能靠请求本身来分。
            !tokenSent -> AuthFailure.LOGIN_REQUIRED

            text.contains("Not authenticated", ignoreCase = true) ->
                AuthFailure.TOKEN_EXPIRED

            else -> AuthFailure.OTHER
        }
    }

    /** [AuthFailure] 对应的界面文案。[OTHER] 用服务端原文。 */
    fun authFailureText(failure: AuthFailure, detail: String? = null): String = when (failure) {
        AuthFailure.PROXY_BASIC -> "该地址由反向代理保护，需要填写反向代理账号与密码"
        AuthFailure.LOGIN_REQUIRED -> "服务器要求登录，请到「设置 → 服务器」填写账号与密码"
        AuthFailure.BAD_CREDENTIALS -> "用户名或密码不正确"
        AuthFailure.TOKEN_EXPIRED -> "登录已失效，请重新登录"
        AuthFailure.OTHER -> detail?.takeIf { it.isNotBlank() } ?: "登录已失效，请重新登录"
    }

    /**
     * 连接失败时的排查建议；没有可说的返回 null。
     *
     * 与 [of] 分工：[of] 回答「是什么错」，这里回答「接下来试什么」。
     * 两者由界面组合展示，各自可以单测——把两件事塞进一句话，测试就只能比对整句。
     *
     * [usedHttps] 是这次实际访问用的协议，由调用方按规范化后的地址给出。
     */
    fun hint(e: Throwable, usedHttps: Boolean): String? = when {
        // 登录 404 的说明已经由 [QwenPawApi.LOGIN_NOT_REACHED] 承担，不重复。
        e is ApiException && !e.userHint.isNullOrBlank() -> null

        // 401 的三种成因各有准确说法，不再叠「改成 https」这条排查建议：
        // 密码打错与协议无关，反代的处置是在客户端填凭据。
        e is ApiException && e.code == 401 -> when (
            classify401(e.detail ?: e.message, e.wwwAuthenticate, e.tokenSent)
        ) {
            AuthFailure.PROXY_BASIC -> PROXY_AUTH_REQUIRED
            else -> null
        }

        // 业务错误说明地址是通的，只是这条路径不对：走 http 时提示换 https。
        e is ApiException && !usedHttps && e.code in 400..499 -> HTTPS_REQUIRED

        // 连不上：https 打不通而地址本身可能只是个 http 服务。
        usedHttps && (e.hasCause<javax.net.ssl.SSLException>() ||
            e.hasCause<java.net.ConnectException>() ||
            e.hasCause<java.net.SocketTimeoutException>() ||
            e.isConnectFailure()) -> HTTP_ONLY

        else -> null
    }

    /**
     * 界面上的最终文案：「是什么错」+ 换行 + 「接下来试什么」。
     *
     * 没有建议时只返回第一段，不留空行。
     */
    fun ofWithHint(e: Throwable, usedHttps: Boolean): String {
        val text = of(e)
        val extra = hint(e, usedHttps) ?: return text
        return "$text\n$extra"
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isConnectFailure(): Boolean {
        val text = message.orEmpty()
        // localhost 被防火墙丢包时 OkHttp 报 "failed to connect ... after 20000ms"，
        // 这是连接层面失败，应归到「连不上」而非「网络中断」。
        return text.contains("Failed to connect", ignoreCase = true) ||
            text.contains("Connection refused", ignoreCase = true) ||
            text.contains("Network is unreachable", ignoreCase = true)
    }
}