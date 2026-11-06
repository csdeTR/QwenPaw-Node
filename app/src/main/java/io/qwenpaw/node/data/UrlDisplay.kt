package io.qwenpaw.node.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 顶栏与列表里的地址写法。
 *
 * 与 `ui/settings/SettingsScreen.kt` 的 `compactUrl` 是**两个不同的东西**，
 * 不要合并：
 *
 * | 场景 | 需要的信息 | 写法 |
 * | --- | --- | --- |
 * | 设置页的地址清单 | 用户要核对「这条到底是不是 https」 | 保留 `https://`，去掉 `http://` |
 * | 顶栏「已通过 … 连接至」 | 只说「连在哪台机器」 | 协议一律不显示，默认端口一并省掉 |
 *
 * 顶栏那一行右边还有上下文胶囊，宽度很紧；`http://` 与 `:443` 对
 * 「连的是哪台机器」没有任何信息量，只是把真正需要看的主机名挤短。
 */
internal object UrlDisplay {

    /**
     * 顶栏用的紧凑写法。
     *
     * 规则：
     * - 协议一律不显示；
     * - `http` 的 80 与 `https` 的 443 不显示（它们由协议唯一确定）；
     * - 其余端口照写，因为那正是用户需要核对的信息；
     * - IPv6 保留方括号，否则 `::1:8088` 之类无法判读；
     * - 无法解析时原样返回，宁可难看也不要吞掉用户填的内容。
     */
    fun address(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return trimmed
        val host = url.host
        // IPv6 地址在 HttpUrl 里是不带方括号的裸形式，展示时要加回来。
        val printable = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = url.port
        return if (port == defaultPort) printable else "$printable:$port"
    }
}

/** 顶栏地址写法的短别名，供界面直接调用。 */
internal fun displayAddress(raw: String): String = UrlDisplay.address(raw)