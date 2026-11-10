package io.qwenpaw.node.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * 流式下载。
 *
 * 只负责「把响应体写进给定的 [OutputStream]」：目标位置由调用方决定
 * （0.3.0 是 SAF 的 `content://` Uri），本类不碰文件系统，也不保留副本。
 *
 * **不做续传。** 服务端虽然回 `Accept-Ranges: bytes`，但带 `Range` 请求实测仍返回
 * 200 与完整内容（`qp-test` 2.2.2.post1 与 `2.3.0b2` 一致），照做会静默重复写入。
 * 中断后只能整文件重下。
 *
 * 取消通过协程取消传播：每读一块前检查一次，取消时 `execute().use` 负责关闭连接。
 */
class DownloadClient(
    private val client: OkHttpClient,
) {

    /**
     * 下载 [request] 到 [sink]，返回写入的总字节数。
     *
     * [onProgress] 收到的是累计字节与 `Content-Length`；响应没有长度时第二项为 null。
     * 回调按 [PROGRESS_INTERVAL_MS] 节流，并在结束时补一次最终值。
     */
    suspend fun download(
        request: Request,
        sink: OutputStream,
        onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val detail = extractDetail(text, response.code)
                throw ApiException(
                    code = response.code,
                    message = detail,
                    detail = detail,
                    wwwAuthenticate = response.header("WWW-Authenticate"),
                    tokenSent = request.header("Authorization")?.startsWith("Bearer ") == true ||
                        request.url.queryParameter("token") != null,
                )
            }
            val body = response.body ?: throw IOException("响应体为空")
            val total = body.contentLength().takeIf { it >= 0 }
            val source = body.source()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = 0L
            var lastReportAt = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count == -1) break
                sink.write(buffer, 0, count)
                read += count
                val now = System.currentTimeMillis()
                if (now - lastReportAt >= PROGRESS_INTERVAL_MS) {
                    onProgress(read, total)
                    lastReportAt = now
                }
            }
            sink.flush()
            onProgress(read, total)
            read
        }
    }

    private fun extractDetail(body: String, code: Int): String {
        if (body.isBlank()) return "HTTP $code"
        val detail = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.get("detail")?.toString()
        }.getOrNull()
        return (detail ?: body).trim('"').take(400)
    }

    private companion object {
        /** 进度回调间隔：既能让进度条动起来，又不至于每 8 KB 触发一次重组。 */
        const val PROGRESS_INTERVAL_MS = 200L

        val json = Json { ignoreUnknownKeys = true }
    }
}
