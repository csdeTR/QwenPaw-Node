package io.qwenpaw.node.data.remote

import io.qwenpaw.node.R
import io.qwenpaw.node.data.MultiAddressFailure
import io.qwenpaw.node.data.channel.pydanticDetailText
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
            401 -> {
                val detail = e.detail ?: e.message
                if (detail.contains("trusted_address_required")) {
                    Texts.get(R.string.error_bridge_trusted_address_required)
                } else {
                    authFailureText(
                        classify401(detail, e.wwwAuthenticate, e.tokenSent),
                        detail,
                    )
                }
            }
            404 -> Texts.get(R.string.error_not_found_404)
            409 -> conflictText(e.detail ?: e.message)
            503 -> Texts.get(R.string.error_console_disabled_503)
            // 422 的 detail 是 pydantic 的数组原文，整段贴到界面上看不出是哪个字段错了。
            // 其余状态码同样可能是 JSON 形状的 detail，一并转成可读文本。
            else -> {
                val raw = e.detail ?: e.message
                if (raw.contains("channel_runtime_not_integrated")) {
                    Texts.get(R.string.error_channel_runtime_not_integrated)
                } else {
                    val text = jsonDetailText(raw) ?: jsonDetailText(e.message) ?: e.message.orEmpty()
                    maskSecrets(text)
                }
            }
        }

        // 并行验证的汇总失败：逐地址给出原因，否则用户只知道「都连不上」，
        // 分不清是域名解析、端口拒绝还是超时。
        is MultiAddressFailure -> buildString {
            append(Texts.get(R.string.drest_all_addresses_failed))
            e.failures.forEach {
                append("\n")
                append(it.url)
                append(": ")
                append(of(it.cause))
            }
        }

        else -> when {
            e.hasCause<java.net.UnknownHostException>() -> Texts.get(R.string.error_host_not_found)
            e.hasCause<java.net.ConnectException>() || e.isConnectFailure() ->
                Texts.get(R.string.error_connect_failed)

            e.hasCause<java.net.SocketTimeoutException>() -> Texts.get(R.string.error_timeout)
            e is java.io.IOException -> Texts.get(R.string.error_network_lost)
            else -> e.message ?: Texts.get(R.string.error_unknown)
        }
    }

    /**
     * 409 的成因不止「对话正在跑」。
     *
     * 插件安装/卸载也会回 409：插件已加载、插件正在执行任务、依赖需要升级。
     * 以前一律显示「该对话已有任务在运行」，让用户去停一个并不存在的对话任务。
     */
    private fun conflictText(detail: String?): String {
        val text = detail?.trim().orEmpty()
        val raw = jsonDetailText(text) ?: text
        if (raw.isBlank()) return Texts.get(R.string.error_conflict_409_no_detail)
        val lower = raw.lowercase()
        return when {
            lower.contains("already running") ||
                lower.contains("already in progress") ||
                raw.contains("\u4efb\u52a1\u5df2\u5728\u8fd0\u884c") ||
                raw.contains("\u4efb\u52a1\u6b63\u5728\u8fdb\u884c") ||
                raw.contains("\u63d2\u4ef6\u6b63\u5728\u6267\u884c\u4efb\u52a1") ->
                Texts.get(R.string.error_busy_409)

            lower.contains("already loaded") ||
                lower.contains("already installed") ||
                raw.contains("\u5df2\u52a0\u8f7d") ||
                raw.contains("\u5df2\u5b89\u88c5") ->
                Texts.get(R.string.error_plugin_already_installed)

            raw.contains("channel_runtime_not_integrated") ->
                Texts.get(R.string.error_channel_runtime_not_integrated)

            else -> Texts.get(R.string.error_conflict_409, maskSecrets(raw))
        }
    }

    /**
     * 掩掉错误原文里可能回显的凭据。
     *
     * 422 的 pydantic 报文会带提交值，插件 409/500 也可能把请求体拼进 detail。
     * 只处理常见的键名与 `Bearer`/`?token=` 形态，并对超长文本截断，避免
     * 整段请求体占满界面。
     */
    fun maskSecrets(text: String): String {
        var out = SECRET_KEY.replace(text) { "${it.groupValues[1]}\"***\"" }
        out = BEARER.replace(out, "Bearer ***")
        out = QUERY_TOKEN.replace(out) { "${it.groupValues[1]}***" }
        return if (out.length > MAX_DETAIL) out.take(MAX_DETAIL) + "…" else out
    }

    private val SECRET_KEY = Regex(
        """(?i)("?(?:token|password|passwd|api_key|apikey|secret|authorization)"?\s*[:=]\s*)("[^"]*"|(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+|[^,}\s]+)""",
    )
    private val BEARER = Regex("""(?i)Bearer\s+[A-Za-z0-9._~+/=-]+""")
    private val QUERY_TOKEN = Regex("""(?i)([?&]token=)[^&\s]+""")
    private const val MAX_DETAIL = 400

    /** 「服务端只接受 https」的排查建议。 */
    val HTTPS_REQUIRED: String get() = Texts.get(R.string.error_https_required)

    /** 「服务端只提供 http」的排查建议。 */
    val HTTP_ONLY: String get() = Texts.get(R.string.error_http_only)

    /** 反向代理要求 HTTP Basic 时的排查建议。 */
    val PROXY_AUTH_REQUIRED: String get() = Texts.get(R.string.error_proxy_auth_required)

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
        AuthFailure.PROXY_BASIC -> Texts.get(R.string.error_auth_proxy_basic)
        AuthFailure.LOGIN_REQUIRED -> Texts.get(R.string.error_auth_login_required)
        AuthFailure.BAD_CREDENTIALS -> Texts.get(R.string.error_auth_bad_credentials)
        AuthFailure.TOKEN_EXPIRED -> Texts.get(R.string.error_auth_token_expired)
        AuthFailure.OTHER -> detail?.takeIf { it.isNotBlank() }?.let(::maskSecrets)
            ?: Texts.get(R.string.error_auth_token_expired)
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

    /**
     * JSON 形状的 `detail` 转成一行中文。
     *
     * FastAPI 的 422 给的是 `[{loc,msg,type}]`，`QwenPawApi.extractDetail` 会把它
     * 原样塞进 message；对象形状（`{message: ...}`）出现在插件类接口的 409、500 上。
     * 解析失败时返回 null，调用方按原来的纯文本显示。
     */
    private fun jsonDetailText(detail: String?): String? {
        val text = detail?.trim().orEmpty()
        if (text.length < 2) return null
        val first = text.first()
        if (first != '[' && first != '{') return null
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        pydanticDetailText(element)?.takeIf { it.isNotBlank() }?.let { return it }
        val obj = element as? JsonObject ?: return null
        return listOf("message", "detail", "msg", "reason").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
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