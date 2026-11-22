package io.qwenpaw.node.data.qr

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 配对二维码里的一台服务器与一枚访问令牌。
 *
 * [addresses] 按优先级排列，第一条是默认地址，其余在它不可达时依次回退。
 */
data class TokenPair(
    val addresses: List<String>,
    val token: String,
    val version: Int = PairQr.VERSION,
)

/** 解析结果。除 [Ok] 外都不该拿去建实例。 */
sealed interface PairParse {
    data class Ok(val pair: TokenPair) : PairParse

    /** 不是本应用的配对码（扫到了别的二维码）。 */
    data object NotPairQr : PairParse

    /** 本应用新版本生成的、当前客户端不认识。 */
    data class Unsupported(val version: Int) : PairParse

    /** 是本应用的配对码，但内容不完整或已损坏。 */
    data class Broken(@StringRes val reasonRes: Int) : PairParse
}

/**
 * 配对码的编解码。
 *
 * 格式与配套插件的 `_pair_payload` 一一对应，两个仓库各实现一份，靠协议文档
 * 第 37 节里的同一条样例串互相校准：
 *
 * ```
 * qwenpaw-node://pair?v=1&addr=<pct>&addr=<pct>&token=<pct>
 * ```
 *
 * 用 `java.net.URI` 而不是 `android.net.Uri`：解析逻辑要能在 JVM 单测里跑，
 * 而后者在纯 JVM 测试里只有桩实现。查询串自己按 `&` 与 `=` 拆分，
 * 不用 `URI.getQuery` 的参数解析，避免它对重复键与空值的处理不同。
 */
object PairQr {
    const val VERSION = 1
    const val MAX_LEN = 4096
    /** 地址列表上限：正常配对不会超过几个入口，多出来的只可能是构造的载荷。 */
    const val MAX_ADDRESSES = 8

    /** 配套插件提供配对码生成能力时声明的名字（插件 ≥ 0.4.3）。 */
    const val CAPABILITY = "pairing"

    private const val SCHEME = "qwenpaw-node"
    private const val HOST = "pair"
    private const val PREFIX = "$SCHEME://$HOST?"

    private val UNRESERVED =
        ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

    fun encode(addresses: List<String>, token: String): String {
        val parts = buildList {
            add("v=$VERSION")
            addresses.forEach { add("addr=" + pctEncode(it)) }
            add("token=" + pctEncode(token))
        }
        return PREFIX + parts.joinToString("&")
    }

    fun parse(raw: String): PairParse {
        val text = sanitize(raw)
        if (text.length > MAX_LEN) return PairParse.Broken(R.string.pair_qr_too_long)
        if (!text.startsWith(PREFIX, ignoreCase = true)) return PairParse.NotPairQr

        val query = text.substring(PREFIX.length)
        var version = 0
        val addresses = mutableListOf<String>()
        var token: String? = null
        // 未知参数忽略，给后续版本留扩展余地。
        for (segment in query.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            // 没有等号的段没有意义；不当作错误，跳过即可。
            if (eq <= 0) continue
            val key = segment.substring(0, eq)
            val value = pctDecode(segment.substring(eq + 1))
                ?: return PairParse.Broken(R.string.pair_qr_malformed)
            when (key) {
                "v" -> version = value.trim().toIntOrNull()
                    ?: return PairParse.Broken(R.string.pair_qr_malformed)
                "addr" -> {
                    val address = value.trim()
                    if (address.isEmpty()) continue
                    if (addresses.size >= MAX_ADDRESSES || !isValidAddress(address)) {
                        return PairParse.Broken(R.string.pair_qr_bad_address)
                    }
                    addresses.add(address)
                }
                "token" -> token = value.trim()
            }
        }

        if (version <= 0) return PairParse.Broken(R.string.pair_qr_malformed)
        if (version > VERSION) return PairParse.Unsupported(version)
        if (addresses.isEmpty()) return PairParse.Broken(R.string.pair_qr_no_address)
        if (token.isNullOrBlank()) return PairParse.Broken(R.string.pair_qr_no_token)
        return PairParse.Ok(TokenPair(addresses = addresses, token = token!!))
    }

    /**
     * 配对地址必须是可用的 http(s) 地址。
     *
     * 拒绝控制字符、内嵌空白、userinfo 与 fragment：前两者会破坏地址解析，
     * 后两者既可能在界面上伪装主机，也容易被当成额外凭据。path/query 保留，
     * 兼容反向代理子路径与现有样例。
     */
    internal fun isValidAddress(raw: String): Boolean {
        if (raw.isEmpty()) return false
        if (raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return false
        val url = raw.toHttpUrlOrNull() ?: return false
        if (url.scheme != "http" && url.scheme != "https") return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.fragment != null) return false
        return url.host.isNotBlank()
    }

    /**
     * 去掉从聊天软件复制时常见的外壳：首尾空白、成对引号。
     *
     * `+` **不**当空格：编码端会把 `+` 写成 `%2B`，解码时见到的裸 `+` 只可能来自
     * 外部生成器；地址或令牌里真的出现 `+` 时改写成空格会静默破坏凭据。
     */
    private fun sanitize(raw: String): String = raw
        .trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trim()

    internal fun pctEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (ch in UNRESERVED) {
                append(ch)
            } else {
                append('%')
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    /** 百分号解码；遇到不合法的 `%XX` 返回 null。 */
    internal fun pctDecode(value: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '%' -> {
                    if (index + 2 >= value.length + 0) return null
                    val hi = Character.digit(value[index + 1], 16)
                    val lo = Character.digit(value[index + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    out.write((hi shl 4) or lo)
                    index += 3
                }

                ch == '+' -> {
                    out.write('+'.code)
                    index += 1
                }

                else -> {
                    out.writeBytes(ch.toString().toByteArray(Charsets.UTF_8))
                    index += 1
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}