package io.qwenpaw.node.log

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 网络请求日志。
 *
 * 只记四件事：方法、地址、状态码、耗时。**不记请求体与响应体**——
 * 对话内容属于用户隐私，日志应当能让用户放心导出给别人看。
 *
 * 地址里**要带协议**。此前只记 `method + encodedPath`，于是「GET 全部 200、
 * 唯独 POST 登录 404」这种现场看不出走的是 http 还是 https，也就分不清
 * 是反向代理把请求跳转改写了，还是服务端确实没有这个端点。
 * query 仍然不记，它可能含 token 之类的敏感参数。
 *
 * [RunLog.d] 在未开启时是空操作，因此无条件挂载这个拦截器的成本可以忽略。
 */
class LoggingInterceptor(
    private val kind: LogKind = LogKind.NETWORK,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!RunLog.isEnabled) return chain.proceed(chain.request())

        val request = chain.request()
        val url = request.url
        // 端口显式写出来：`http://host` 与 `https://host` 的默认端口不同，
        // 而「到底连到哪个端口」正是排查跳转问题时要确认的第一件事。
        val target = "${url.scheme}://${url.host}:${url.port}${url.encodedPath}"
        val label = "${request.method} $target"
        val started = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            val cost = (System.nanoTime() - started) / 1_000_000
            // 3xx 只有在 network interceptor 里才看得到：application interceptor
            // 拿到的是跟随跳转之后的最终响应，中间那一跳被 OkHttp 内部消化了。
            val note = if (response.code in 300..399) "（重定向，未跟随）" else ""
            RunLog.d(kind, "HTTP", "$label → ${response.code}$note（${cost}ms）")
            response
        } catch (e: IOException) {
            val cost = (System.nanoTime() - started) / 1_000_000
            RunLog.e(kind, "HTTP", e, "$label 失败，耗时 ${cost}ms")
            throw e
        }
    }
}