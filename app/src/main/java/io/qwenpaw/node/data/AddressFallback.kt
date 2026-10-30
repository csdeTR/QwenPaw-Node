package io.qwenpaw.node.data

import io.qwenpaw.node.data.remote.ApiException

/**
 * 多地址回退的判定逻辑。
 *
 * 抽成纯函数是因为这部分规则最容易出错，而 `InstanceRepository` 依赖 Android 的
 * `InstanceStore`，不便在 JVM 单测里构造。
 *
 * 两条规则值得记住：
 *
 * 1. **只对连接类失败换地址。** HTTP 业务错误（4xx/5xx）说明地址是通的，
 *    换下一个既解决不了问题，还会把真实原因（例如 401 需要登录）掩盖掉。
 * 2. **命中后不改写顺序。** 用户把局域网地址排在第一是有意的，
 *    隧道地址通常更慢；安静地重排会让下次连接莫名变慢。
 */
internal object AddressFallback {

    /** 去掉空行与首尾空白，保持用户设定顺序。 */
    fun candidates(urls: List<String>): List<String> =
        urls.map { it.trim() }.filter { it.isNotBlank() }

    /**
     * 是否属于「这个地址连不上」。
     *
     * OkHttp 对连接被丢弃的地址抛的是普通 `IOException`，类型上看不出来，
     * 只能靠文案里的 `Failed to connect` 识别。
     */
    fun isConnectionFailure(e: Throwable): Boolean {
        // 业务错误一律不触发回退。
        if (e is ApiException) return false
        var current: Throwable? = e
        while (current != null) {
            when (current) {
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is java.net.NoRouteToHostException,
                -> return true
            }
            val text = current.message.orEmpty()
            if (text.contains("Failed to connect", ignoreCase = true) ||
                text.contains("network is unreachable", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * 按顺序尝试，返回命中的地址与结果。
     *
     * 全部失败时抛出**最后一个**异常——那通常是最有信息量的一个，
     * 例如列表里最后一个地址是域名，报「找不到服务器」比报「连接被拒绝」更能说明问题。
     */
    suspend fun <T> firstReachable(
        urls: List<String>,
        /** 每次尝试前回调（下标从 1 起），用于界面显示正在试哪一个。 */
        onAttempt: (index: Int, total: Int, url: String) -> Unit = { _, _, _ -> },
        block: suspend (String) -> T,
    ): Pair<String, T> {
        val list = candidates(urls)
        if (list.isEmpty()) throw IllegalArgumentException("请填写服务器地址")
        var lastError: Throwable? = null
        for ((index, url) in list.withIndex()) {
            onAttempt(index + 1, list.size, url)
            try {
                return url to block(url)
            } catch (e: Exception) {
                if (!isConnectionFailure(e)) throw e
                io.qwenpaw.node.log.RunLog.e(
                    io.qwenpaw.node.log.LogKind.NETWORK,
                    "Fallback",
                    e,
                    "地址 ${index + 1}/${list.size} 不可达：$url",
                )
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("所有地址都无法连接")
    }
}