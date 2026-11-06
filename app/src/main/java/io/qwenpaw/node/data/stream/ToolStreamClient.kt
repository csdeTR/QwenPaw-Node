package io.qwenpaw.node.data.stream

import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 工具调用实时输出。
 *
 * 服务端的 `/stream` 只发 `{"type":"chunk","data":...}` 与结尾的 `{"type":"done"}`，
 * 没有 `event:` 行，**不重放**：晚订阅只能拿到结束帧。因此这里不做自动重连，
 * 断流后由调用方改用 `/output` 取权威结果。
 */
class ToolStreamClient(
    private val api: QwenPawApi,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun open(conn: Connection, sessionId: String, toolCallId: String): Flow<ToolStreamEvent> =
        callbackFlow {
            val call = client.newCall(api.toolCallStreamRequest(conn, sessionId, toolCallId))
            val reader = try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    val detail = extractDetail(text, response.code)
                    // 与 REST 侧一致：带上 WWW-Authenticate 才能把「反代拦下」与
                    // 「服务端拒绝凭据」分开，否则两者都只是一句 401。
                    close(
                        ApiException(
                            response.code,
                            detail,
                            detail = detail,
                            wwwAuthenticate = response.header("WWW-Authenticate"),
                            tokenSent = conn.token != null || conn.basicAuth != null,
                        ),
                    )
                    awaitClose { }
                    return@callbackFlow
                }
                val stream = response.body?.charStream()
                if (stream == null) {
                    close(IOException("响应体为空"))
                    awaitClose { }
                    return@callbackFlow
                }
                stream.buffered()
            } catch (e: IOException) {
                close(e)
                awaitClose { }
                return@callbackFlow
            }

            val sse = SseReader(reader)
            val worker = launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val frame = sse.nextFrame() ?: break
                        if (frame.isBlank()) continue
                        val event = parse(frame) ?: continue
                        trySend(event)
                        if (event is ToolStreamEvent.Done) break
                    }
                    close()
                } catch (e: IOException) {
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

    private fun parse(frame: String): ToolStreamEvent? = runCatching {
        val root = json.parseToJsonElement(frame).jsonObject
        when (root["type"]?.jsonPrimitive?.content) {
            "chunk" -> ToolStreamEvent.Chunk(
                textOf(root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }),
            )
            "done" -> ToolStreamEvent.Done
            else -> null
        }
    }.getOrNull()

    /** `data` 是 agentscope 的块对象：`content[].text`；部分块直接带 `text`。 */
    private fun textOf(data: JsonObject?): String {
        if (data == null) return ""
        val blocks = runCatching { data["content"]?.jsonArray }.getOrNull()
        if (blocks != null) {
            return blocks.mapNotNull { block ->
                runCatching { block.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull()
            }.joinToString("")
        }
        return runCatching { data["text"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
    }

    private fun extractDetail(body: String, code: Int): String {
        if (body.isBlank()) return "HTTP $code"
        val detail = runCatching {
            body.substringAfter("\"detail\"", "").trim().removePrefix(":").trim().trim('"')
        }.getOrDefault("")
        return detail.ifBlank { "HTTP $code" }
    }
}

sealed interface ToolStreamEvent {
    /** 新的输出片段。 */
    data class Chunk(val text: String) : ToolStreamEvent

    /** 流结束。 */
    data object Done : ToolStreamEvent
}