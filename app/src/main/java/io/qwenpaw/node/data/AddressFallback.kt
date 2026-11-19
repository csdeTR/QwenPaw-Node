package io.qwenpaw.node.data

import io.qwenpaw.node.R
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** 一个地址的失败原因。保留异常本身，由 [io.qwenpaw.node.data.remote.ErrorText] 统一翻译。 */
class AddressFailure(val url: String, val cause: Throwable)

/**
 * 所有地址都不可达。
 *
 * 串行回退此前只抛最后一个异常，用户看不到其余地址为什么失败；并行探测把
 * 每个地址的原因都带上来，界面可以逐行展示。
 */
class MultiAddressFailure(val failures: List<AddressFailure>) : IOException(
    failures.joinToString("\n") { "${it.url}: ${it.cause.message.orEmpty()}" },
    failures.lastOrNull()?.cause,
)

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
        if (list.isEmpty()) throw IllegalArgumentException(Texts.get(R.string.drest_need_server_address))
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
                    Texts.get(R.string.drest_address_unreachable, index + 1, list.size, url),
                )
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException(Texts.get(R.string.drest_all_addresses_failed))
    }

    /**
     * 并行探测，首个成功即返回。
     *
     * 只用于「验证连接」，不用于聊天等普通请求：并行会放大请求量，且普通请求
     * 已经有运行期命中缓存，没有必要为每一次调用都并发探路。
     *
     * 规则与串行版一致：业务错误（401 等）说明地址是通的，立即终止并原样抛出；
     * 连接失败才继续等其他地址。全部失败时汇总每个地址的原因。
     */
    suspend fun <T> firstReachableParallel(
        urls: List<String>,
        staggerMillis: Long = AddressProbePlan.STAGGER_MILLIS,
        totalBudgetMillis: Long = AddressProbePlan.TOTAL_BUDGET_MILLIS,
        /** 每次真正发起时回调（下标从 1 起），并行时顺序不固定。 */
        onAttempt: (index: Int, total: Int, url: String) -> Unit = { _, _, _ -> },
        block: suspend (String) -> T,
    ): Pair<String, T> = coroutineScope {
        val list = candidates(urls)
        if (list.isEmpty()) {
            throw IllegalArgumentException(Texts.get(R.string.drest_need_server_address))
        }
        val outcomes = Channel<ProbeOutcome<T>>(Channel.UNLIMITED)
        val jobs = list.mapIndexed { index, url ->
            launch {
                if (index > 0) delay(staggerMillis * index)
                onAttempt(index + 1, list.size, url)
                try {
                    val value = block(url)
                    outcomes.send(ProbeOutcome.Success(url, value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isConnectionFailure(e)) {
                        outcomes.send(ProbeOutcome.ConnectionFailed(AddressFailure(url, e)))
                    } else {
                        outcomes.send(ProbeOutcome.Terminal(e))
                    }
                }
            }
        }
        var success: Pair<String, T>? = null
        val failures = mutableListOf<AddressFailure>()
        try {
            withTimeout(totalBudgetMillis) {
                while (success == null) {
                    when (val outcome = outcomes.receive()) {
                        is ProbeOutcome.Success<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            success = outcome.url to (outcome.value as T)
                        }
                        is ProbeOutcome.Terminal -> throw outcome.error
                        is ProbeOutcome.ConnectionFailed -> {
                            failures += outcome.failure
                            if (failures.size == list.size) {
                                throw MultiAddressFailure(failures.toList())
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 预算耗尽时把尚未回报的地址也列出来，否则界面只剩一句「都不可达」，
            // 用户不知道是哪几条根本没等到结果。
            val recorded = failures.mapTo(mutableSetOf()) { it.url }
            val pending = list.filter { it !in recorded }.map {
                AddressFailure(it, java.net.SocketTimeoutException("probe budget exhausted"))
            }
            throw MultiAddressFailure(failures + pending)
        } finally {
            jobs.forEach { it.cancel() }
        }
        val result = success
        if (result != null) result else throw MultiAddressFailure(failures.toList())
    }

    private sealed interface ProbeOutcome<out T> {
        data class Success<T>(val url: String, val value: T) : ProbeOutcome<T>
        data class ConnectionFailed(val failure: AddressFailure) : ProbeOutcome<Nothing>
        data class Terminal(val error: Throwable) : ProbeOutcome<Nothing>
    }
}