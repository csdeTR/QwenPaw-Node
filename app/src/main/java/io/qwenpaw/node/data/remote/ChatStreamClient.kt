package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.stream.ChatEvent
import io.qwenpaw.node.data.stream.ChatEventParser
import io.qwenpaw.node.data.stream.SseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 对话流客户端。
 *
 * QwenPaw 的 `POST /api/console/chat` 是长连接 SSE：
 * - 客户端断开后服务端**继续执行**，所以重连用 `reconnect: true` 挂回同一条流；
 * - 同一 chat 并发发送会返回 409，需要在 UI 层串行化。
 * - 收到 `status=completed` 后服务端还会补一帧 `turn_usage`，这里等到流自然结束再关闭。
 */
class ChatStreamClient(
    private val api: QwenPawApi,
    private val client: OkHttpClient,
) {

    fun open(conn: Connection, body: String): Flow<ChatEvent> = callbackFlow {
        val call = client.newCall(api.chatRequest(conn, body))
        // 重连与首次发送走同一个入口，靠 body 里是否带 reconnect 区分。
        val reconnecting = body.contains("\"reconnect\":true")
        io.qwenpaw.node.log.RunLog.d(
            io.qwenpaw.node.log.LogKind.STREAM,
            "SSE",
            if (reconnecting) "挂回既有流" else "建立新流",
        )

        val reader = try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val detail = extractDetail(text, response.code)
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "SSE",
                    "建流被拒：${response.code} $detail",
                )
                close(ApiException(response.code, detail))
                awaitClose { }
                return@callbackFlow
            }
            val stream = response.body?.charStream()
            if (stream == null) {
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "SSE",
                    "响应体为空",
                )
                close(IOException("响应体为空"))
                awaitClose { }
                return@callbackFlow
            }
            stream.buffered()
        } catch (e: IOException) {
            io.qwenpaw.node.log.RunLog.e(io.qwenpaw.node.log.LogKind.STREAM, "SSE", e, "建流失败")
            close(e)
            awaitClose { }
            return@callbackFlow
        }

        val sse = SseReader(reader)
        val worker = launch(Dispatchers.IO) {
            var frames = 0
            try {
                while (true) {
                    val frame = sse.nextFrame() ?: break
                    if (frame.isBlank()) continue
                    frames++
                    val event = ChatEventParser.parse(frame)
                    if (event !is ChatEvent.Ignored) {
                        trySend(event)
                    }
                }
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "SSE",
                    "流正常结束，共 $frames 帧",
                )
                close()
            } catch (e: IOException) {
                // 断网时最常见的现场：流在读帧途中被中断，这里必须留下帧数与异常。
                io.qwenpaw.node.log.RunLog.e(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "SSE",
                    e,
                    "读流中断，已收到 $frames 帧",
                )
                close(e)
            } finally {
                runCatching { reader.close() }
            }
        }

        awaitClose {
            worker.cancel()
            runCatching { call.cancel() }
            runCatching { reader.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun extractDetail(body: String, code: Int): String {
        if (body.isBlank()) return "HTTP $code"
        val detail = runCatching {
            body.substringAfter("\"detail\"", "")
                .trim()
                .removePrefix(":")
                .trim()
                .trim('"')
        }.getOrDefault("")
        return detail.ifBlank { "HTTP $code" }
    }
}
