package io.qwenpaw.node.data

/** 外部浏览器地址的校验结果。 */
enum class BrowserUrlError {
    EMPTY,
    TOO_LONG,
    MALFORMED,
    SCHEME,
    HOST,
}

data class BrowserUrlCheck(
    val url: String? = null,
    val host: String? = null,
    val error: BrowserUrlError? = null,
) {
    val ok: Boolean get() = error == null && url != null
}

/**
 * 只放行 http / https。
 *
 * 服务端下发的链接、市场条目与 OAuth 地址都不可信，`javascript:`、`file:`、
 * `intent:` 这类 scheme 一旦交给系统会打开本不该打开的目标。
 */
fun validateBrowserUrl(raw: String?): BrowserUrlCheck {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return BrowserUrlCheck(error = BrowserUrlError.EMPTY)
    if (text.length > MAX_BROWSER_URL_LENGTH) {
        return BrowserUrlCheck(error = BrowserUrlError.TOO_LONG)
    }
    val uri = runCatching { java.net.URI(text) }.getOrNull()
        ?: return BrowserUrlCheck(error = BrowserUrlError.MALFORMED)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return BrowserUrlCheck(error = BrowserUrlError.SCHEME)
    }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: return BrowserUrlCheck(error = BrowserUrlError.HOST)
    return BrowserUrlCheck(url = text, host = host)
}

private const val MAX_BROWSER_URL_LENGTH = 2048
