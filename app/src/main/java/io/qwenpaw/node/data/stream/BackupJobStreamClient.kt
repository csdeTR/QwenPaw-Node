package io.qwenpaw.node.data.stream

import io.qwenpaw.node.data.model.BackupJobSnapshotDto
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
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 备份任务进度。
 *
 * `GET /api/backups/jobs/{id}/events` 每一帧都是**完整快照**，因此调用方
 * 直接替换状态即可，不需要合并字段；每 15 秒一行 `: heartbeat` 已被
 * [SseReader] 忽略。流在终态后自行结束，断流不会自动重连——任务状态
 * 只在服务端内存里，重连应改用 `GET /jobs/{id}` 取权威终态。
 */
class BackupJobStreamClient(
    private val api: QwenPawApi,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun open(conn: Connection, jobId: String): Flow<BackupJobSnapshotDto> = callbackFlow {
        val call = client.newCall(api.backupJobEventsRequest(conn, jobId))
        val reader = try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                close(
                    ApiException(
                        response.code,
                        extractDetail(text, response.code),
                        detail = extractDetail(text, response.code),
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
                    val snapshot = runCatching {
                        json.decodeFromString(BackupJobSnapshotDto.serializer(), frame)
                    }.getOrNull() ?: continue
                    trySend(snapshot)
                    if (snapshot.isTerminal) break
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

    private fun extractDetail(body: String, code: Int): String {
        if (body.isBlank()) return "HTTP $code"
        val detail = runCatching {
            body.substringAfter("\"detail\"", "").trim().removePrefix(":").trim().trim('"')
        }.getOrDefault("")
        return detail.ifBlank { "HTTP $code" }
    }
}
