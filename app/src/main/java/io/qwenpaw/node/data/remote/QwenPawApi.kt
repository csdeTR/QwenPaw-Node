package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.model.AgentListDto
import io.qwenpaw.node.data.model.AgentProfileDto
import io.qwenpaw.node.data.model.ApprovalDto
import io.qwenpaw.node.data.model.ApprovalListDto
import io.qwenpaw.node.data.model.AuthStatusDto
import io.qwenpaw.node.data.model.AuthTokenDto
import io.qwenpaw.node.data.model.AvailableCommandsDto
import io.qwenpaw.node.data.model.BatchChatResultDto
import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatHistoryDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.model.ChatStatusDto
import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto
import io.qwenpaw.node.data.model.InboxPageDto
import io.qwenpaw.node.data.model.InboxTraceDto
import io.qwenpaw.node.data.model.ModelPoolDto
import io.qwenpaw.node.data.model.ModelsActiveDto
import io.qwenpaw.node.data.model.OffloadPolicyDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ProjectDirDto
import io.qwenpaw.node.data.model.PushMessagesDto
import io.qwenpaw.node.data.model.SlashCommandDto
import io.qwenpaw.node.data.model.ThinkingViewDto
import io.qwenpaw.node.data.model.TokenUsageDto
import io.qwenpaw.node.data.model.ToolDto
import io.qwenpaw.node.data.model.UploadResultDto
import io.qwenpaw.node.data.model.VersionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    /**
     * 给用户看的补充说明。
     *
     * 有些状态码的默认文案在特定调用场景下会误导：登录接口的 404 被统一映射成
     * 「会话不存在或已被删除」，用户会去翻会话数据，而真正的原因是请求没落到登录端点。
     * 这类场景由调用方就近给一句准确的话，[ErrorText.of] 优先用它。
     */
    val userHint: String? = null,
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

    /**
     * 用指定客户端查版本。
     *
     * 探测走 [probeClient] 的短超时，而不是主客户端的 10 分钟读超时。
     */
    fun versionWith(baseUrl: String, probe: OkHttpClient): VersionDto {
        val url = resolve(baseUrl, "/api/version")
        val request = Request.Builder().url(url).get().build()
        return probe.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, extractDetail(text, response.code))
            }
            decode(text, VersionDto.serializer())
        }
    }

    fun authStatus(baseUrl: String): AuthStatusDto =
        decode(request(Connection(baseUrl, null), "GET", "/api/auth/status"), AuthStatusDto.serializer())

    fun login(baseUrl: String, username: String, password: String, permanent: Boolean): AuthTokenDto {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("expires_in", if (permanent) 0 else 2592000)
        }
        return try {
            decode(
                request(Connection(baseUrl, null), "POST", "/api/auth/login", body),
                AuthTokenDto.serializer(),
            )
        } catch (e: ApiException) {
            // 只有在 authStatus 说「需要登录」时才会走到这里，说明服务端确实有登录接口。
            // 此时 404 的含义只能是「这个请求没落到登录端点」：最常见的原因是
            // 地址写成了 http，被反向代理 301 到 https——OkHttp 跟随 301 会把 POST
            // 改写成 GET 并丢掉请求体，登录端点于是不认识这个请求。
            if (e.code == 404) {
                throw ApiException(404, e.message, LOGIN_NOT_REACHED)
            }
            throw e
        }
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

    /**
     * 新建会话。
     *
     * [placeholder] 为 true 时在 `meta` 里写入 `console_placeholder_name`，
     * 与服务端 Web 端先分配会话再接消息的做法对齐。
     *
     * 这一步决定首轮是否会自动生成标题。服务端 `console.py` 的处理是：
     * 只有当 `chat.meta["console_placeholder_name"] == chat.name` 时才把会话名
     * 改成首条消息的文本，之后才满足 `chat.name == name` 从而派发
     * `generate_and_update_title`。客户端此前只传 `name` 不传 `meta`，
     * 于是两处判断都落空——标题从来没生成过，会话一直停在前 20 个字的截断名。
     */
    fun createChat(
        conn: Connection,
        name: String,
        sessionId: String,
        userId: String,
        placeholder: Boolean = false,
    ): ChatSpecDto = decode(
        request(conn, "POST", "/api/chats", createChatBody(name, sessionId, userId, placeholder)),
        ChatSpecDto.serializer(),
    )

    /**
     * 新建会话的请求体。
     *
     * 抽成独立函数只为可测：`meta` 里那一层是否带上 `console_placeholder_name`，
     * 决定了首轮结束后服务端会不会自动生成标题，而这一点只能靠报文断言。
     */
    fun createChatBody(
        name: String,
        sessionId: String,
        userId: String,
        placeholder: Boolean,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("session_id", sessionId)
        put("user_id", userId)
        put("channel", "console")
        if (placeholder) {
            put("meta", buildJsonObject { put("console_placeholder_name", name) })
        }
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

    /**
     * 新建分组。
     *
     * 服务端只接受 `name`，`order` 由服务端自己排在末尾；返回体是完整的分组对象。
     */
    fun createChatGroup(conn: Connection, name: String): ChatGroupDto {
        val body = buildJsonObject { put("name", name) }
        return decode(request(conn, "POST", "/api/chats/groups", body), ChatGroupDto.serializer())
    }

    /**
     * 改名或置顶分组。
     *
     * 两个字段都可选，但至少要传一个，否则服务端会返回 400。
     * **内置分组（`default` / `cron` / `subagents`）不可改名或置顶**，
     * 调用前应自行判断 `kind`，不要指望服务端给可读的报错。
     */
    fun updateChatGroup(
        conn: Connection,
        groupId: String,
        name: String? = null,
        pinned: Boolean? = null,
    ): ChatGroupDto {
        val body = buildJsonObject {
            name?.let { put("name", it) }
            pinned?.let { put("pinned", it) }
        }
        return decode(
            request(conn, "PUT", "/api/chats/groups/$groupId", body),
            ChatGroupDto.serializer(),
        )
    }

    /**
     * 删除分组。组内会话会被服务端重新归位到默认分组。
     *
     * 同样只对自定义分组有效。
     */
    fun deleteChatGroup(conn: Connection, groupId: String) {
        request(conn, "DELETE", "/api/chats/groups/$groupId")
    }

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

    /** 为会话选择模型。null 不是有效入参，恢复默认要走 [activeModel] 再显式设置。 */
    fun setChatModel(conn: Connection, chatId: String, providerId: String, model: String): ThinkingViewDto {
        val body = buildJsonObject {
            put("provider_id", providerId)
            put("model", model)
        }
        return decode(request(conn, "PUT", "/api/chats/$chatId/model", body), ThinkingViewDto.serializer())
    }

    /** 全局默认模型。用于「恢复默认」——服务端不接受 null，必须显式设回这个值。 */
    fun activeModel(conn: Connection): ModelsActiveDto =
        decode(request(conn, "GET", "/api/models/active"), ModelsActiveDto.serializer())

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

    // ---------- 头像 ----------

    /**
     * 服务端头像清单。
     *
     * 服务端 `avatar_owner()` 固定返回 `"console"`，只有用户侧一份头像，
     * 没有按智能体区分。实测两个版本均可用，未设置时返回
     * `{"selected":null,"history":[]}`。
     */
    fun avatarProfile(conn: Connection): AvatarProfileDto =
        decode(request(conn, "GET", "/api/profile/avatars"), AvatarProfileDto.serializer())

    /** 某张头像的图片字节。服务端对该响应设了 `private, no-store`，不做协商缓存。 */
    fun avatarImage(conn: Connection, id: String): ByteArray =
        requestBytes(conn, "GET", "/api/profile/avatars/$id")

    // ---------- 智能体与工作区 ----------

    /** 智能体配置（含审批模式）。 */
    fun agent(conn: Connection, agentId: String): AgentProfileDto =
        decode(request(conn, "GET", "/api/agents/$agentId"), AgentProfileDto.serializer())

    /**
     * 修改审批模式。
     *
     * 服务端要求 PUT 的 body 是**完整的**智能体配置，因此先读出当前对象、
     * 只替换 `approval_level`，再把其余字段原样写回，避免客户端未识别的字段被抹掉。
     */
    fun setApprovalLevel(conn: Connection, agentId: String, level: String): AgentProfileDto {
        val current = request(conn, "GET", "/api/agents/$agentId")
        val obj = json.parseToJsonElement(current).jsonObject
        val body = buildJsonObject {
            obj.forEach { (k, v) -> if (k != "approval_level") put(k, v) }
            put("approval_level", level)
        }
        return decode(
            request(conn, "PUT", "/api/agents/$agentId", body),
            AgentProfileDto.serializer(),
        )
    }

    /** 当前工作区目录。 */
    fun projectDirectory(conn: Connection): ProjectDirDto =
        decode(
            request(conn, "GET", "/api/workspace/project-directory"),
            ProjectDirDto.serializer(),
        )

    /** 切换工作区目录。传 null 恢复默认；路径不存在时服务端返回 400。 */
    fun setProjectDirectory(conn: Connection, path: String?): ProjectDirDto {
        val body = buildJsonObject {
            if (path == null) put("path", kotlinx.serialization.json.JsonNull) else put("path", path)
        }
        return decode(
            request(conn, "PUT", "/api/workspace/project-directory", body),
            ProjectDirDto.serializer(),
        )
    }

    /**
     * 当前智能体可用的斜杠命令。
     *
     * 命令注册表挂在智能体上，端点走 `X-Agent-Id` 归属（`baseBuilder` 已统一注入）。
     * 注册表为空时返回空数组，不是 404。
     */
    fun availableCommands(conn: Connection): List<SlashCommandDto> =
        decode(
            request(conn, "GET", "/api/workspace/commands/available"),
            AvailableCommandsDto.serializer(),
        ).all

    /** token 用量汇总。 */
    fun tokenUsage(conn: Connection, startDate: String? = null, endDate: String? = null): TokenUsageDto {
        val query = buildList {
            if (startDate != null) add("start_date=$startDate")
            if (endDate != null) add("end_date=$endDate")
        }.joinToString("&")
        val path = if (query.isBlank()) "/api/token-usage" else "/api/token-usage?$query"
        return decode(request(conn, "GET", path), TokenUsageDto.serializer())
    }

    // ---------- 收件箱 ----------

    /**
     * 收件箱事件一页。
     *
     * 响应没有 `has_more`，只有 `total`；分页结束判定交给 `PagedList.ofOffset`。
     */
    fun inboxEvents(
        conn: Connection,
        limit: Int = 50,
        offset: Int = 0,
        unreadOnly: Boolean = false,
    ): InboxPageDto {
        val query = buildList {
            add("limit=$limit")
            add("offset=$offset")
            if (unreadOnly) add("unread_only=true")
        }.joinToString("&")
        return decode(
            request(conn, "GET", "/api/console/inbox/events?$query"),
            InboxPageDto.serializer(),
        )
    }

    /**
     * 标记已读。
     *
     * `all` 为 true 时服务端忽略 `event_ids`，整批标记；返回实际更新条数。
     */
    fun markInboxRead(conn: Connection, eventIds: List<String> = emptyList(), all: Boolean = false): Int {
        val body = buildJsonObject {
            if (all) {
                put("all", true)
            } else {
                putJsonArray("event_ids") { eventIds.forEach { add(it) } }
            }
        }
        val raw = request(conn, "POST", "/api/console/inbox/read", body)
        return json.parseToJsonElement(raw).jsonObject["updated"]?.toString()?.toIntOrNull() ?: 0
    }

    /** 删除单条事件。事件不存在时服务端返回 404。 */
    fun deleteInboxEvent(conn: Connection, eventId: String) {
        request(conn, "DELETE", "/api/console/inbox/events/$eventId")
    }

    /** 该次运行的完整轨迹。轨迹不存在时服务端返回 404。 */
    fun inboxTrace(conn: Connection, runId: String): InboxTraceDto =
        decode(
            request(conn, "GET", "/api/console/inbox/traces/$runId"),
            InboxTraceDto.serializer(),
        )

    // ---------- 环境变量 ----------

    /**
     * 已显式配置的环境变量。
     *
     * **返回值是明文**：服务端只在落盘时加密，读取时透明解密。展示层必须默认遮罩。
     */
    fun envVars(conn: Connection): List<EnvVarDto> =
        decode(
            request(conn, "GET", "/api/envs"),
            kotlinx.serialization.builtins.ListSerializer(EnvVarDto.serializer()),
        )

    /**
     * 合并保存若干变量，不删除未提交的项。
     *
     * 端点是集合级的 `PATCH /api/envs`，body 是扁平对象 `{"KEY":"value"}`，
     * 不是 `{"key":..,"value":..}` 的数组。
     */
    fun patchEnvVars(conn: Connection, values: Map<String, String>): List<EnvVarDto> {
        val body = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }
        return decode(
            request(conn, "PATCH", "/api/envs", body),
            kotlinx.serialization.builtins.ListSerializer(EnvVarDto.serializer()),
        )
    }

    /** 删除一个自定义变量。已登记的内置变量会返回 400，必须走 [resetEnvVar]。 */
    fun deleteEnvVar(conn: Connection, key: String): List<EnvVarDto> =
        decode(
            request(conn, "DELETE", "/api/envs/$key"),
            kotlinx.serialization.builtins.ListSerializer(EnvVarDto.serializer()),
        )

    /** 把内置变量恢复为继承值。 */
    fun resetEnvVar(conn: Connection, key: String): List<EnvVarDto> =
        decode(
            request(conn, "POST", "/api/envs/$key/reset"),
            kotlinx.serialization.builtins.ListSerializer(EnvVarDto.serializer()),
        )

    /** 环境变量规格。客户端据此判断内置变量（只能重置）与只读项。 */
    fun envCatalog(conn: Connection): List<EnvCatalogDto> =
        decode(
            request(conn, "GET", "/api/envs/catalog"),
            kotlinx.serialization.builtins.ListSerializer(EnvCatalogDto.serializer()),
        )

    // ---------- 工具 ----------

    /** 工具列表。返回数组，不是 `{tools:[...]}`。 */
    fun tools(conn: Connection): List<ToolDto> =
        decode(
            request(conn, "GET", "/api/tools"),
            kotlinx.serialization.builtins.ListSerializer(ToolDto.serializer()),
        )

    /** 切换启用状态。**端点没有请求体**，服务端自行取反。 */
    fun toggleTool(conn: Connection, name: String): ToolDto =
        decode(
            request(conn, "PATCH", "/api/tools/$name/toggle"),
            ToolDto.serializer(),
        )

    /** 设置异步执行。 */
    fun setToolAsync(conn: Connection, name: String, enabled: Boolean): ToolDto {
        val body = buildJsonObject { put("async_execution", enabled) }
        return decode(
            request(conn, "PATCH", "/api/tools/$name/async-execution", body),
            ToolDto.serializer(),
        )
    }

    /** 读取某个工具的配置。返回的是裸配置字典，密码字段已被服务端打码。 */
    fun toolConfig(conn: Connection, name: String): Map<String, JsonElement> {
        val raw = request(conn, "GET", "/api/tools/$name/config")
        return json.parseToJsonElement(raw).jsonObject
    }

    /** 保存工具配置。body 是 `{"config": {...}}`，不是扁平字典。 */
    fun saveToolConfig(conn: Connection, name: String, values: Map<String, JsonElement>) {
        val body = buildJsonObject { put("config", JsonObject(values)) }
        request(conn, "POST", "/api/tools/$name/config", body)
    }

    // ---------- 后台策略 ----------

    fun offloadPolicy(conn: Connection): OffloadPolicyDto =
        decode(
            request(conn, "GET", "/api/settings/offload-policy"),
            OffloadPolicyDto.serializer(),
        )

    /** 设置默认动作，取值 `keep_foreground` 或 `offload`。 */
    fun setOffloadPolicy(conn: Connection, action: String): OffloadPolicyDto {
        val body = buildJsonObject { put("default_action", action) }
        return decode(
            request(conn, "PUT", "/api/settings/offload-policy", body),
            OffloadPolicyDto.serializer(),
        )
    }

    fun deleteChat(conn: Connection, chatId: String) {
        request(conn, "DELETE", "/api/chats/$chatId")
    }

    // ---------- 批量会话操作 ----------

    /**
     * 批量删除。
     *
     * 这个端点与其它批量接口不同：body 直接是 id 数组，不是 `{chat_ids: [...]}`，
     * 所以走 [requestRaw] 直接发原始 JSON。
     */
    fun batchDeleteChats(conn: Connection, chatIds: List<String>) {
        val body = buildJsonArray { chatIds.forEach { add(it) } }.toString()
        requestRaw(conn, "POST", "/api/chats/batch-delete", body)
    }

    /** 批量归档。运行中的会话会被服务端跳过。 */
    fun batchArchiveChats(conn: Connection, chatIds: List<String>): BatchChatResultDto =
        decode(
            request(conn, "POST", "/api/chats/actions/batch-archive", idsBody(chatIds)),
            BatchChatResultDto.serializer(),
        )

    /** 批量取消归档。 */
    fun batchUnarchiveChats(conn: Connection, chatIds: List<String>): BatchChatResultDto =
        decode(
            request(conn, "POST", "/api/chats/actions/batch-unarchive", idsBody(chatIds)),
            BatchChatResultDto.serializer(),
        )

    private fun idsBody(chatIds: List<String>): JsonObject = buildJsonObject {
        put("chat_ids", buildJsonArray { chatIds.forEach { add(it) } })
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
    ): String = requestRaw(conn, method, path, body?.toString())

    /**
     * 发一个请求，body 是已经序列化好的 JSON 字符串。
     *
     * 少数端点（如批量删除）的请求体是裸数组而非对象，无法用 [JsonObject] 表达，
     * 单独开这个入口。
     */
    private fun requestRaw(
        conn: Connection,
        method: String,
        path: String,
        body: String? = null,
    ): String {
        val url = resolve(conn.baseUrl, path)
        val builder = baseBuilder(conn, url)
        val requestBody = body?.toRequestBody(JSON_MEDIA)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: EMPTY_BODY)
            "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
            "PATCH" -> builder.patch(requestBody ?: EMPTY_BODY)
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
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = extractDetail(text, response.code)
                // 拦截器只看到状态码，看不出服务端为什么拒绝；这里补上 detail。
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.NETWORK,
                    "API",
                    "${request.method} ${request.url.encodedPath} → ${response.code} $detail",
                )
                throw ApiException(response.code, detail)
            }
            text
        }
    }

    /**
     * 与 [execute] 同构，但返回原始字节。
     *
     * 图片端点不能走字符串通道：`String` 解码会按字符集转换，二进制图会被破坏。
     */
    private fun requestBytes(conn: Connection, method: String, path: String): ByteArray {
        val builder = baseBuilder(conn, resolve(conn.baseUrl, path))
        when (method) {
            "GET" -> builder.get()
            else -> error("不支持的方法 $method")
        }
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = extractDetail(response.body?.string().orEmpty(), response.code)
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.NETWORK,
                    "API",
                    "${method} ${response.request.url.encodedPath} → ${response.code} $detail",
                )
                throw ApiException(response.code, detail)
            }
            response.body?.bytes() ?: ByteArray(0)
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

        /**
         * 登录接口 404 的说明。
         *
         * 这一条要回答的是「我该改什么」，而不是「哪里错了」——前者用户能动手，
         * 后者只会把人引向会话数据。
         */
        internal const val LOGIN_NOT_REACHED =
            "登录接口返回 404。若服务端只接受 https，http 上的登录请求会被跳转改写成 GET 并丢失请求体；" +
                "请把服务器地址改为 https:// 开头"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 流式对话可能持续很久，读超时给足；其余请求单次不会超过这个值。
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // REST 与 SSE 共用这份配置，挂一次即可覆盖两条链路。
            // 用 network interceptor 而不是 application interceptor：只有前者能看到
            // 每一跳的状态码，包括被跟随的 3xx——301 把 POST 改写成 GET 这类问题
            // 正是靠那一跳才能定位。
            .addNetworkInterceptor(io.qwenpaw.node.log.LoggingInterceptor(io.qwenpaw.node.log.LogKind.NETWORK))
            .build()

        /**
         * 探测专用客户端。
         *
         * 「验证连接」只需要判断地址是不是活的，因此超时远短于主客户端，
         * 并用 `callTimeout` 兜住整个调用的总耗时——只设 connectTimeout 是不够的，
         * 连上但对端不回包（隧道/VPN 半开连接）会一直等到 readTimeout。
         *
         * 缓存成单例：每次新建都意味着一个新连接池与新线程池，
         * 而探测可能被用户反复点击。
         */
        private val probeClientShared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                // 探测要的是「这个地址现在通不通」，不要在内部重试掩盖问题。
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor(io.qwenpaw.node.log.LoggingInterceptor(io.qwenpaw.node.log.LogKind.NETWORK))
                .build()
        }

        fun probeClient(): OkHttpClient = probeClientShared

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
