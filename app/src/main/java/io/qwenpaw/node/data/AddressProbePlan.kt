package io.qwenpaw.node.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 探测超时。单位毫秒，三档分别对应建连、单次读、整次调用。 */
data class ProbePolicy(
    val connectMillis: Long,
    val readMillis: Long,
    val callMillis: Long,
)

/** 地址所在的网络类型，只用于选择探测超时，不参与业务判定。 */
enum class AddressKind { LAN, WAN }

/**
 * 验证连接时的地址分类与并行参数。
 *
 * 局域网地址的握手与首包通常远快于公网，用同一组超时会让坏掉的局域网地址
 * 白等到公网时长。分类只看主机，不看端口或路径。
 */
object AddressProbePlan {

    /** 并行探测时相邻两个地址的发起间隔。 */
    const val STAGGER_MILLIS = 200L

    /** 整次验证的总预算。超过后不再等待未返回的地址。 */
    const val TOTAL_BUDGET_MILLIS = 5_000L

    /** 其他探测场景（插件、检索）继续用原来的宽松档位。 */
    val DEFAULT = ProbePolicy(connectMillis = 4_000, readMillis = 5_000, callMillis = 8_000)

    val LAN = ProbePolicy(connectMillis = 1_500, readMillis = 2_500, callMillis = 3_000)

    val WAN = ProbePolicy(connectMillis = 2_500, readMillis = 3_500, callMillis = 4_500)

    fun kindOf(url: String): AddressKind {
        val host = hostOf(url) ?: return AddressKind.WAN
        return if (isLanHost(host)) AddressKind.LAN else AddressKind.WAN
    }

    fun policyOf(url: String): ProbePolicy =
        if (kindOf(url) == AddressKind.LAN) LAN else WAN

    private fun hostOf(url: String): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        return normalized.toHttpUrlOrNull()?.host?.lowercase()
    }

    private fun isLanHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1" || h == "0.0.0.0") return true
        if (h.endsWith(".local")) return true
        if (h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        // IPv6 unique-local (fc00::/7) 与 link-local (fe80::/10)。
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return true
        return false
    }
}
