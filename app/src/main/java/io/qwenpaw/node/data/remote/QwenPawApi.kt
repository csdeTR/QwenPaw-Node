package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.model.AgentListDto
import io.qwenpaw.node.data.model.ApprovalDto
import io.qwenpaw.node.data.model.ApprovalListDto
import io.qwenpaw.node.data.model.AuthStatusDto
import io.qwenpaw.node.data.model.AuthTokenDto
import io.qwenpaw.node.data.model.ChatHistoryDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.model.ChatStatusDto
import io.qwenpaw.node.data.model.PushMessagesDto
import io.qwenpaw.node.data.model.UploadResultDto
import io.qwenpaw.node.data.model.VersionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 连接目标：一个 QwenPaw 实例。 */
data class Connection(
    val baseUrl: String,
    val token: String?,
    val agentId: String = "default",
)

/** 服务端返回的非 2xx 错误。 */
class ApiException(
    val code: Int,
    override val message: String,
) : IOException(message)

/**
 * QwenPaw REST 客户端。
 *
 * 只使用服务端已存在的路由：auth、version、agents、chats、
 * console（chat / upload / stop / push-messages）、approval。
 * 所有方法都是阻塞调用，调用方需自行切到 IO 线程。
 */
class QwenPawApi(
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    // ---------- 基础 ----------

    fun version(baseUrl: String): VersionDto =
        decode(request(Connection(baseUrl, null), "GET", "/api/version"), VersionDto.serializer())

    fun authStatus(baseUrl: String): AuthStatusDto =
        decode(request(Connection(baseUrl, null), "GET", "/api/auth/status"), AuthStatusDto.serializer())

    fun login(baseUrl: String, username: String, password: String, permanent: Boolean): AuthTokenDto {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("expires_in", if (permanent) 0 else 2592000)
        }
        return decode(
            request(Connection(baseUrl, null), "POST", "/api/auth/login", body),
            AuthTokenDto.serializer(),
        )
    }

    fun register(baseUrl: String, username: String, password: String): AuthTokenDto {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("expires_in", 0)
        }
        return decode(
            request(Connection(baseUrl, null), "POST", "/api/auth/register", body),
            AuthTokenDto.serializer(),
        )
    }

    // ---------- 智能体 ----------

    fun agents(conn: Connection): List<AgentDto> =
        decode(request(conn, "GET", "/api/agents"), AgentListDto.serializer()).agents

    // ---------- 会话 ----------

    fun chats(conn: Connection, archived: Boolean? = false): List<ChatSpecDto> =
        decode(
            request(conn, "GET", "/api/chats?archived=$archived"),
            kotlinx.serialization.builtins.ListSerializer(ChatSpecDto.serializer()),
        )

    fun chat(conn: Connection, chatId: String): ChatHistoryDto =
        decode(request(conn, "GET", "/api/chats/$chatId"), ChatHistoryDto.serializer())

    fun chatStatus(conn: Connection, chatId: String): ChatStatusDto =
        decode(request(conn, "GET", "/api/chats/$chatId/status"), ChatStatusDto.serializer())

    fun createChat(conn: Connection, name: String, sessionId: String, userId: String): ChatSpecDto {
        val body = buildJsonObject {
            put("name", name)
            put("session_id", sessionId)
            put("user_id", userId)
            put("channel", "console")
        }
        return decode(request(conn, "POST", "/api/chats", body), ChatSpecDto.serializer())
    }

    fun renameChat(conn: Connection, chatId: String, name: String): ChatSpecDto {
        val body = buildJsonObject { put("name", name) }
        return decode(request(conn, "PUT", "/api/chats/$chatId", body), ChatSpecDto.serializer())
    }

    fun deleteChat(conn: Connection, chatId: String) {
        request(conn, "DELETE", "/api/chats/$chatId")
    }

    // ---------- 审批 ----------

    fun pendingApprovals(conn: Connection, sessionId: String? = null): List<ApprovalDto> {
        val path = if (sessionId.isNullOrBlank()) {
            "/api/approval/list"
        } else {
            "/api/approval/list?session_id=$sessionId"
        }
        return decode(request(conn, "GET", path), ApprovalListDto.serializer()).pendingApprovals
    }

    fun approve(conn: Connection, requestId: String, sessionId: String, userId: String?, scope: String = "exact") {
        val body = buildJsonObject {
            put("request_id", requestId)
            put("session_id", sessionId)
            if (userId != null) put("user_id", userId)
            put("scope", scope)
        }
        request(conn, "POST", "/api/approval/approve", body)
    }

    fun deny(conn: Connection, requestId: String, sessionId: String, reason: String?) {
        val body = buildJsonObject {
            put("request_id", requestId)
            put("session_id", sessionId)
            if (reason != null) put("reason", reason)
        }
        request(conn, "POST", "/api/approval/deny", body)
    }

    fun pushMessages(conn: Connection, sessionId: String? = null): PushMessagesDto {
        val path = if (sessionId.isNullOrBlank()) {
            "/api/console/push-messages"
        } else {
            "/api/console/push-messages?session_id=$sessionId"
        }
        return decode(request(conn, "GET", path), PushMessagesDto.serializer())
    }

    // ---------- 运行控制 ----------

    fun stopChat(conn: Connection, chatId: String) {
        request(conn, "POST", "/api/console/chat/stop?chat_id=$chatId", buildJsonObject { })
    }

    /** 文件上传，返回服务端保存后的路径。 */
    fun upload(conn: Connection, file: File, mime: String?): UploadResultDto {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody((mime ?: "application/octet-stream").toMediaType()),
            )
            .build()
        val url = resolve(conn.baseUrl, "/api/console/upload")
        val req = baseBuilder(conn, url).post(body).build()
        return decode(execute(req), UploadResultDto.serializer())
    }

    // ---------- 请求体构造 ----------

    fun buildChatBody(
        sessionId: String,
        userId: String,
        parts: List<JsonObject>,
        reconnect: Boolean = false,
    ): String {
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("user_id", userId)
            put("channel", "console")
            if (reconnect) put("reconnect", true)
            put(
                "input",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray { parts.forEach { add(it) } },
                            )
                        },
                    )
                },
            )
        }
        return payload.toString()
    }

    fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun mediaPart(kind: String, url: String): JsonObject = buildJsonObject {
        put("type", kind)
        putJsonObject("source") {
            put("type", "url")
            put("url", url)
        }
    }

    /** 供流式客户端使用：构造 SSE 请求（不执行）。 */
    fun chatRequest(conn: Connection, body: String): Request =
        baseBuilder(conn, resolve(conn.baseUrl, "/api/console/chat"))
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .build()

    // ---------- 内部实现 ----------

    private fun request(
        conn: Connection,
        method: String,
        path: String,
        body: JsonObject? = null,
    ): String {
        val url = resolve(conn.baseUrl, path)
        val builder = baseBuilder(conn, url)
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: EMPTY_BODY)
            "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
            "DELETE" -> builder.delete()
            else -> error("不支持的方法 $method")
        }
        return execute(builder.build())
    }

    private fun baseBuilder(conn: Connection, url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url).header("X-Agent-Id", conn.agentId)
        conn.token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, extractDetail(text, response.code))
            }
            return text
        }
    }

    private fun <T> decode(text: String, serializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        if (text.isBlank()) throw ApiException(204, "服务端返回了空响应")
        return json.decodeFromString(serializer, text)
    }

    private fun extractDetail(body: String, code: Int): String {
        if (body.isBlank()) return "HTTP $code"
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["detail"]?.toString()
        }.getOrNull()
        return (detail ?: body).trim('"').take(400)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 流式对话可能持续很久，读超时给足；其余请求单次不会超过这个值。
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** 把用户填写的 baseUrl 与路径拼成完整的 HttpUrl。 */
        fun resolve(baseUrl: String, path: String): HttpUrl {
            val normalized = baseUrl.trim().trimEnd('/')
                .let { if (it.contains("://")) it else "http://$it" }
            val url = normalized.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("地址不合法：$baseUrl")
            return url.newBuilder()
                .addPathSegments(path.removePrefix("/").substringBefore('?'))
                .query(path.substringAfter('?', "").ifBlank { null })
                .build()
        }
    }
}
