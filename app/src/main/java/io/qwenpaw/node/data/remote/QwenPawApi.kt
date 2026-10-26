package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.model.AgentListDto
import io.qwenpaw.node.data.model.ApprovalDto
import io.qwenpaw.node.data.model.ApprovalListDto
import io.qwenpaw.node.data.model.AuthStatusDto
import io.qwenpaw.node.data.model.AuthTokenDto
import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatHistoryDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.model.ChatStatusDto
import io.qwenpaw.node.data.model.ModelPoolDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.PushMessagesDto
import io.qwenpaw.node.data.model.ThinkingViewDto
import io.qwenpaw.node.data.model.TokenUsageDto
import io.qwenpaw.node.data.model.UploadResultDto
import io.qwenpaw.node.data.model.VersionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    /** 置顶 / 取消置顶。服务端接受 `pinned` 与 `name` 同处一个 PATCH/PUT 语义。 */
    fun setPinned(conn: Connection, chatId: String, pinned: Boolean): ChatSpecDto {
        val body = buildJsonObject { put("pinned", pinned) }
        return decode(request(conn, "PUT", "/api/chats/$chatId", body), ChatSpecDto.serializer())
    }

    /** 归档会话；运行中的会话服务端会拒绝（409）。 */
    fun archiveChat(conn: Connection, chatId: String): ChatSpecDto =
        decode(request(conn, "POST", "/api/chats/$chatId/archive"), ChatSpecDto.serializer())

    /** 取消归档，幂等。 */
    fun unarchiveChat(conn: Connection, chatId: String): ChatSpecDto =
        decode(request(conn, "POST", "/api/chats/$chatId/unarchive"), ChatSpecDto.serializer())

    /** 会话分组列表。 */
    fun chatGroups(conn: Connection): List<ChatGroupDto> =
        decode(
            request(conn, "GET", "/api/chats/groups"),
            kotlinx.serialization.builtins.ListSerializer(ChatGroupDto.serializer()),
        )

    /** 设置会话所属分组。 */
    fun setChatGroup(conn: Connection, chatId: String, groupId: String?): ChatSpecDto {
        val body = buildJsonObject {
            if (groupId == null) put("group_id", kotlinx.serialization.json.JsonNull)
            else put("group_id", groupId)
        }
        return decode(request(conn, "PUT", "/api/chats/$chatId", body), ChatSpecDto.serializer())
    }

    /** 读取会话思考模式。 */
    fun chatThinking(conn: Connection, chatId: String): ThinkingViewDto =
        decode(request(conn, "GET", "/api/chats/$chatId/thinking"), ThinkingViewDto.serializer())

    /** 设置会话思考模式；失败（模型不支持）时服务端返回 422。 */
    fun setChatThinking(conn: Connection, chatId: String, level: String, budgetTokens: Long? = null): ThinkingViewDto {
        val body = buildJsonObject {
            put("level", level)
            if (budgetTokens != null) put("budget_tokens", budgetTokens)
        }
        return decode(request(conn, "PUT", "/api/chats/$chatId/thinking", body), ThinkingViewDto.serializer())
    }

    /** 为会话选择模型；传 null 表示恢复智能体默认。 */
    fun setChatModel(conn: Connection, chatId: String, providerId: String?, model: String?): ThinkingViewDto {
        val body = if (providerId.isNullOrBlank() || model.isNullOrBlank()) {
            buildJsonObject { put("provider_id", kotlinx.serialization.json.JsonNull) }
        } else {
            buildJsonObject {
                put("provider_id", providerId)
                put("model", model)
            }
        }
        return decode(request(conn, "PUT", "/api/chats/$chatId/model", body), ThinkingViewDto.serializer())
    }

    /** 供应商列表。 */
    fun providers(conn: Connection): List<ProviderDto> =
        decode(
            request(conn, "GET", "/api/models"),
            kotlinx.serialization.builtins.ListSerializer(ProviderDto.serializer()),
        )

    /** 某个供应商的模型池。 */
    fun modelPool(conn: Connection, providerId: String, limit: Int = 200): ModelPoolDto =
        decode(
            request(conn, "GET", "/api/models/$providerId/pool?limit=$limit"),
            ModelPoolDto.serializer(),
        )

    /** token 用量汇总。 */
    fun tokenUsage(conn: Connection, startDate: String? = null, endDate: String? = null): TokenUsageDto {
        val query = buildList {
            if (startDate != null) add("start_date=$startDate")
            if (endDate != null) add("end_date=$endDate")
        }.joinToString("&")
        val path = if (query.isBlank()) "/api/token-usage" else "/api/token-usage?$query"
        return decode(request(conn, "GET", path), TokenUsageDto.serializer())
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

    /**
     * 媒体内容块。
     *
     * 服务端的 `_request_input_to_msgs` 读的是扁平字段
     * （`image_url` / `video_url` / `file_url`），不是老协议的 `source.url`。
     * 早期版本发 `source.url` 会被静默丢弃，图片/文件根本进不了上下文。
     */
    fun mediaPart(kind: String, url: String, filename: String? = null): JsonObject = buildJsonObject {
        put("type", kind)
        when (kind) {
            "image" -> put("image_url", url)
            "video" -> put("video_url", url)
            else -> {
                put("file_url", url)
                if (!filename.isNullOrBlank()) put("filename", filename)
            }
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
