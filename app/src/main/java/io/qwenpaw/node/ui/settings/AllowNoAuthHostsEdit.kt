package io.qwenpaw.node.ui.settings

/**
 * 免鉴权地址的纯文本处理，不依赖 Android 资源，可直接单测。
 *
 * 客户端只做三件事：去首尾空格、丢弃空项、按不区分大小写去重。IPv4/IPv6 的
 * 规范化（补全或压缩零段、把 `0:0:0:0:0:0:0:1` 写成 `::1`）交给服务端：
 * 若客户端先规范化，保存后回读的形态与用户输入不一致，会被误认为「没有存上」。
 *
 * 回环判断只用于界面提示，不影响提交内容。主机名比较一律不区分大小写，
 * 因为 `localhost` 与 `LOCALHOST` 指向同一台机器，IPv6 的十六进制同样如此。
 */
object AllowNoAuthHostsEdit {

    const val LOOPBACK_V4 = "127.0.0.1"
    const val LOOPBACK_V6 = "::1"

    private const val LOOPBACK_V6_EXPANDED = "0:0:0:0:0:0:0:1"
    private const val LOOPBACK_HOSTNAME = "localhost"

    private val LOOPBACK_KEYS = setOf(LOOPBACK_V4, LOOPBACK_V6, LOOPBACK_HOSTNAME)

    /** 该地址是否指向本机。`[::1]` 与零段展开写法一并认作回环。 */
    fun isLoopback(raw: String): Boolean = comparable(raw) in LOOPBACK_KEYS

    /**
     * 加入一条地址；空白输入与重复项都返回原名单，调用方据此区分「没有变化」。
     *
     * 保留用户输入的原始形态（只去掉首尾空格），保证服务端回读前界面不出现
     * 两种写法并存。
     */
    fun add(hosts: List<String>, raw: String): List<String> {
        val host = raw.trim()
        if (host.isEmpty()) return hosts
        val target = comparable(host)
        if (hosts.any { comparable(it) == target }) return hosts
        return hosts + host
    }

    /** 移除该地址；比较时忽略首尾空格与大小写。 */
    fun remove(hosts: List<String>, raw: String): List<String> {
        val target = comparable(raw)
        return hosts.filterNot { comparable(it) == target }
    }

    /**
     * 本次改动丢掉了哪些本机回环 IP。
     *
     * 只核对 `127.0.0.1` 与 `::1` 两个地址，不看 `localhost`：前者是服务端默认
     * 监听的地址，丢掉后本机浏览器也会被要求登录，属于需要额外提示的后果。
     *
     * @return 被移除的回环 IP，按 [LOOPBACK_V4]、[LOOPBACK_V6] 的顺序。原名单里
     *   本来就没有的地址不算「移除」，不会出现在结果里。
     */
    fun droppedIpLoopbacks(before: List<String>, after: List<String>): List<String> {
        val remaining = after.mapTo(mutableSetOf()) { comparable(it) }
        val removed = before.mapTo(mutableSetOf()) { comparable(it) }
        return listOf(LOOPBACK_V4, LOOPBACK_V6).filter { it in removed && it !in remaining }
    }

    /**
     * 比较用的归一形态：去空格、转小写、去掉 IPv6 的方括号、把零段展开写法折回 `::1`。
     * 其余形态原样返回，规范化留给服务端。
     */
    private fun comparable(raw: String): String {
        var host = raw.trim().lowercase()
        if (host.length >= 2 && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length - 1)
        }
        return if (host == LOOPBACK_V6_EXPANDED) LOOPBACK_V6 else host
    }
}
