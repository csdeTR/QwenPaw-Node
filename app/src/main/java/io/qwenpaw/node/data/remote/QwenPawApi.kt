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
import io.qwenpaw.node.data.model.BridgeInfoDto
import io.qwenpaw.node.data.model.BrowseDirsDto
import io.qwenpaw.node.data.model.SearchPageDto
import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatHistoryDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.model.ChatStatusDto
import io.qwenpaw.node.data.model.CronDispatchTargetsDto
import io.qwenpaw.node.data.model.CronExecutionRecordDto
import io.qwenpaw.node.data.model.CronJobSpecDto
import io.qwenpaw.node.data.model.CronJobStateDto
import io.qwenpaw.node.data.model.CronJobViewDto
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
import io.qwenpaw.node.data.model.ToolCallInfoDto
import io.qwenpaw.node.data.model.ToolCallListDto
import io.qwenpaw.node.data.model.ToolCallOutputDto
import io.qwenpaw.node.data.model.ToolDto
import io.qwenpaw.node.data.model.UploadResultDto
import io.qwenpaw.node.data.model.UserTimezoneDto
import io.qwenpaw.node.data.model.VersionDto
import io.qwenpaw.node.data.model.WorkspaceFileContentDto
import io.qwenpaw.node.data.model.WorkspaceMemoryContentDto
import io.qwenpaw.node.data.model.WorkspaceMemoryFileDto
import io.qwenpaw.node.data.model.WorkspaceTreeDto
import kotlinx.serialization.builtins.ListSerializer
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
    /**
     * 反向代理的 HTTP Basic 凭据。
     *
     * nginx / openresty 的 `auth_basic` 与 QwenPaw 自身的登录争用同一个
     * `Authorization` 头，一个请求只能带一个。因此两者同时存在时把
     * `Authorization` 让给 Basic，QwenPaw 的 token 改走 `?token=` 查询参数
     * （服务端 `AuthMiddleware._extract_token` 支持这条路）。
     */
    val basicAuth: BasicAuth? = null,
)

/**
 * 反向代理的 HTTP Basic 凭据。
 *
 * 只用于反代这一层，与 [Connection.token]、`InstanceConfig.username/password`
 * 是两套完全不同的身份：前者证明「可以访问这台反代」，后者证明「可以操作这个
 * QwenPaw 实例」。
 */
data class BasicAuth(val user: String, val password: String) {
    /** `user:password` 的 Base64，即 `Authorization: Basic <value>` 的值。 */
    val headerValue: String
        get() = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
}

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
    /**
     * 服务端响应体里的 `detail` 原文。
     *
     * 与 [message] 的区别：[message] 可能被调用方改写成人话，只有原文才能用来
     * 判断 401 的成因（`Invalid username or password` 与
     * `Invalid or expired token` 是两种完全不同的处置）。
     */
    val detail: String? = null,
    /**
     * 响应头 `WWW-Authenticate`。
     *
     * nginx / openresty 的 `auth_basic` 会带 `Basic realm="..."`，
     * 而 QwenPaw 服务端自身从不发这个头。有了它才能把「反代拦下」与
     * 「服务端拒绝密码」分开，否则两者都表现为 401。
     */
    val wwwAuthenticate: String? = null,
    /** 这次请求是否带了 token。用于区分「没登录」与「登录过期」。 */
    val tokenSent: Boolean = false,
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

    fun version(baseUrl: String, basicAuth: BasicAuth? = null): VersionDto =
        decode(
            request(Connection(baseUrl, null, basicAuth = basicAuth), "GET", "/api/version"),
            VersionDto.serializer(),
        )

    /**
     * 用指定客户端查版本。
     *
     * 探测走 [probeClient] 的短超时，而不是主客户端的 10 分钟读超时。
     */
    fun versionWith(
        baseUrl: String,
        probe: OkHttpClient,
        /** 反向代理的 Basic 凭据：`/api/version` 是公开路径，但反代可能先拦下它。 */
        basicAuth: BasicAuth? = null,
    ): VersionDto {
        val url = resolve(baseUrl, "/api/version")
        val request = Request.Builder().url(url).get().apply {
            basicAuth?.let { header("Authorization", it.headerValue) }
        }.build()
        return probe.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw failureOf(
                    response.code,
                    text,
                    response.header("WWW-Authenticate"),
                    basicAuth == null,
                )
            }
            decode(text, VersionDto.serializer())
        }
    }

    fun authStatus(baseUrl: String, basicAuth: BasicAuth? = null): AuthStatusDto =
        decode(
            request(Connection(baseUrl, null, basicAuth = basicAuth), "GET", "/api/auth/status"),
            AuthStatusDto.serializer(),
        )

    /**
     * 登录换 token。
     *
     * [basicAuth] 必须跟着走：反向代理开启 `auth_basic` 时，`/api/auth/login`
     * 与其他路径一样被反代先拦下。少带这一层，用户会看到「用户名或密码不正确」，
     * 而实际上是请求根本没到服务端。
     */
    fun login(
        baseUrl: String,
        username: String,
        password: String,
        permanent: Boolean,
        basicAuth: BasicAuth? = null,
    ): AuthTokenDto {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("expires_in", if (permanent) 0 else 2592000)
        }
        return try {
            decode(
                request(Connection(baseUrl, null, basicAuth = basicAuth), "POST", "/api/auth/login", body),
                AuthTokenDto.serializer(),
            )
        } catch (e: ApiException) {
            // 只有在 authStatus 说「需要登录」时才会走到这里，说明服务端确实有登录接口。
            // 此时 404 的含义只能是「这个请求没落到登录端点」：最常见的原因是
            // 地址写成了 http，被反向代理 301 到 https——OkHttp 跟随 301 会把 POST
            // 改写成 GET 并丢掉请求体，登录端点于是不认识这个请求。
            // 401 则是服务端在拒绝这组账号密码，**不是**「登录已失效」：
            // 用户此时还没登录过，没有可失效的东西。
            if (e.code == 404) {
                throw ApiException(404, e.message, LOGIN_NOT_REACHED, e.detail)
            }
            if (e.code == 401) {
                // 带 `WWW-Authenticate: Basic` 的是反向代理，不是服务端在核对账号：
                // 说成「用户名或密码不正确」会让用户去改一个本来就对的密码。
                if (ErrorText.classify401(e.detail ?: e.message, e.wwwAuthenticate, true) ==
                    ErrorText.AuthFailure.PROXY_BASIC
                ) {
                    throw e
                }
                throw ApiException(401, e.message, BAD_CREDENTIALS, e.detail, e.wwwAuthenticate, false)
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

    /**
     * 校验当前 token 是否仍有效。
     *
     * 用途是区分「token 失效」与「网络不通」：前者要重新登录，后者只需重试。
     * 只发一个短超时的 `GET /api/auth/verify`，不碰业务端点。
     *
     * 返回 true 表示 token 有效；false 表示服务端明确拒绝（401）。
     * 连不上时抛异常，由调用方按网络问题处理。
     */
    fun verifyToken(conn: Connection): Boolean {
        val url = connUrl(conn, "/api/auth/verify")
        val request = baseBuilder(conn, url).get().build()
        return probeClient().newCall(request).execute().use { response ->
            // 200 = 有效；401 = 失效。其余状态码（例如服务端未启用登录时的 403）
            // 说明「这个断言无法回答」，按有效处理，避免把用户赶去重填密码。
            response.code != 401
        }
    }

    /**
     * 吊销一个 token。传 null 表示吊销**当前**这一个（退出登录）。
     *
     * 服务端未启用登录时返回 403，调用方应按「该实例没开登录」提示，
     * 而不是当成失败——本来就没有可吊销的东西。
     */
    fun revokeToken(conn: Connection, token: String? = null) {
        val body = buildJsonObject {
            token?.takeIf { it.isNotBlank() }?.let { put("token", it) }
        }
        request(conn, "POST", "/api/auth/revoke-token", body)
    }

    /** 吊销全部 token。会让**所有**设备上的登录立刻失效，属于破坏性操作。 */
    fun revokeAllTokens(conn: Connection) {
        request(conn, "POST", "/api/auth/revoke-all-tokens", buildJsonObject { })
    }

    // ---------- 配套插件 ----------

    /**
     * 探测配套插件是否已安装。
     *
     * 只打插件自己的端点，不查核心的 `/api/plugins` 列表：那条列表在 loader 未初始化时
     * 走磁盘扫描，会把「装了但没加载」报成 `enabled=true, loaded=false`，还需要额外
     * 一条判定规则；而 `/info` 能返回 200，本身就证明插件路由已经挂上了。
     *
     * 返回值语义：
     * - `null` 表示**未检测到**（404）或连不上；
     * - `null` 也表示契约版本不兼容（`protocol_version != 1`），旧客户端不该继续使用；
     * - 401 会抛出，调用方应记为「待重探」而不是「未安装」——插件路由不在鉴权公开
     *   路径里，验证阶段还没有 token。
     */
    fun pluginBridgeInfo(conn: Connection): BridgeInfoDto? {
        val url = connUrl(conn, BRIDGE_INFO_PATH)
        val request = baseBuilder(conn, url).get().build()
        return probeClient().newCall(request).execute().use { response ->
            if (response.code == 404) return null
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw failureOf(response.code, text, response.header("WWW-Authenticate"), conn.token != null)
            }
            val info = decode(text, BridgeInfoDto.serializer())
            info.takeIf { it.protocolVersion == BridgeInfoDto.PROTOCOL }
        }
    }

    /**
     * 插件事件流的请求对象。
     *
     * 执行时必须用 [streamClient]：SSE 是长连接，`probeClientShared` 的
     * `readTimeout` 与 `callTimeout` 只有几秒，空闲期没有任何字节，
     * 会被误判成失败掐掉。
     */
    fun bridgeEventsRequest(conn: Connection): Request =
        baseBuilder(conn, connUrl(conn, BRIDGE_EVENTS_PATH))
            .header("Accept", "text/event-stream")
            .get()
            .build()

    /**
     * 会话全文检索，走配套插件 `GET …/qwenpaw-node-bridge/search`。
     *
     * 与 [pluginBridgeInfo] 一样用 `probeClient()`：这个端点的耗时是几十到几百毫秒，
     * 不该拖住主客户端的连接池。404 返回空页——插件没装时这就是终态，不是错误；
     * 401/403 原样抛给上层，由调用方按「待重探」处理。
     */
    fun pluginSearch(conn: Connection, q: String, limit: Int, cursor: Int): SearchPageDto {
        val url = connUrl(conn, BRIDGE_SEARCH_PATH).newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("cursor", cursor.toString())
            .build()
        val request = baseBuilder(conn, url).get().build()
        return probeClient().newCall(request).execute().use { response ->
            if (response.code == 404) return SearchPageDto()
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw failureOf(response.code, text, response.header("WWW-Authenticate"), conn.token != null)
            }
            decode(text, SearchPageDto.serializer())
        }
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

    /**
     * 重排分组。
     *
     * body 是 `{group_ids: [...]}`，**不是**数组本身——与批量删除的裸数组不同。
     * 服务端按传入顺序回写 `order`，返回完整列表。内置分组（`default` / `cron` /
     * `subagents`）同样要出现在数组里：漏掉哪个，服务端就把它当作「未提及」，
     * 后续的相对位置会变得不可预测。
     */
    fun reorderChatGroups(conn: Connection, groupIds: List<String>): List<ChatGroupDto> {
        val body = buildJsonObject {
            putJsonArray("group_ids") { groupIds.forEach { add(it) } }
        }
        return decode(
            request(conn, "PUT", "/api/chats/groups/order", body),
            kotlinx.serialization.builtins.ListSerializer(ChatGroupDto.serializer()),
        )
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
     * 浏览服务端上的目录，用于「智能体工作区」弹窗按目录逐层选择。
     *
     * `show_hidden` 固定为 true：不传时服务端返回的 `dirs` 可能是空的，
     * 用户会看到「这个目录没有子目录」而不是真正的列表。
     */
    fun browseDirs(
        conn: Connection,
        path: String,
        showHidden: Boolean = true,
    ): BrowseDirsDto = decode(
        execute(
            baseBuilder(
                conn,
                workspaceUrl(
                    conn,
                    "/api/workspace/project-directory/browse-dirs",
                    mapOf(
                        "path" to path,
                        "show_hidden" to showHidden.toString(),
                    ),
                ),
            ).get().build(),
        ),
        BrowseDirsDto.serializer(),
    )

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
     *
     * `agentId` 与 `sourceType` 对应服务端的 `agent_id` / `source_type`。
     * 两者都由服务端过滤后再统计 `total` 与 `unread_count`，因此筛选生效时
     * 未读数只覆盖筛选范围，不是全局未读。
     */
    fun inboxEvents(
        conn: Connection,
        limit: Int = 50,
        offset: Int = 0,
        unreadOnly: Boolean = false,
        agentId: String? = null,
        sourceType: String? = null,
    ): InboxPageDto {
        val url = inboxEventsUrl(
            connUrl(conn, INBOX_EVENTS_PATH),
            limit = limit,
            offset = offset,
            unreadOnly = unreadOnly,
            agentId = agentId,
            sourceType = sourceType,
        )
        return decode(execute(baseBuilder(conn, url).get().build()), InboxPageDto.serializer())
    }

    /**
     * 收件箱查询的 URL 构造。
     *
     * 单独成函数是为了能直接单测参数拼装：空值不加参数，编码交给 OkHttp
     * （智能体 id 里可能出现空格或中文）。
     */
    internal fun inboxEventsUrl(
        base: HttpUrl,
        limit: Int,
        offset: Int,
        unreadOnly: Boolean,
        agentId: String?,
        sourceType: String?,
    ): HttpUrl = base.newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .apply {
            if (unreadOnly) addQueryParameter("unread_only", "true")
            agentId?.takeIf { it.isNotBlank() }?.let { addQueryParameter("agent_id", it) }
            sourceType?.takeIf { it.isNotBlank() }?.let { addQueryParameter("source_type", it) }
        }
        .build()

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

    // ---------- 定时任务 ----------

    fun cronJobs(conn: Connection): List<CronJobSpecDto> =
        decode(
            request(conn, "GET", "/api/cron/jobs"),
            kotlinx.serialization.builtins.ListSerializer(CronJobSpecDto.serializer()),
        )

    fun cronJob(conn: Connection, id: String): CronJobViewDto =
        decode(request(conn, "GET", "/api/cron/jobs/$id"), CronJobViewDto.serializer())

    /** 新建。服务端忽略客户端传入的 `id`，以响应里的 id 为准。 */
    fun createCronJob(conn: Connection, spec: CronJobSpecDto): CronJobSpecDto =
        decode(
            request(conn, "POST", "/api/cron/jobs", cronBody(spec)),
            CronJobSpecDto.serializer(),
        )

    /** 整体替换。`spec.id` 必须与路径一致，否则 400。 */
    fun updateCronJob(conn: Connection, id: String, spec: CronJobSpecDto): CronJobSpecDto =
        decode(
            request(conn, "PUT", "/api/cron/jobs/$id", cronBody(spec)),
            CronJobSpecDto.serializer(),
        )

    fun deleteCronJob(conn: Connection, id: String) {
        request(conn, "DELETE", "/api/cron/jobs/$id")
    }

    fun pauseCronJob(conn: Connection, id: String) {
        request(conn, "POST", "/api/cron/jobs/$id/pause")
    }

    fun resumeCronJob(conn: Connection, id: String) {
        request(conn, "POST", "/api/cron/jobs/$id/resume")
    }

    /** 导入任务的审阅闸门。通过后任务仍是禁用状态，需要用户再手动启用。 */
    fun promoteCronJob(conn: Connection, id: String): CronJobSpecDto =
        decode(request(conn, "POST", "/api/cron/jobs/$id/promote"), CronJobSpecDto.serializer())

    /** 立即执行一次，不检查 `enabled`。 */
    fun runCronJob(conn: Connection, id: String) {
        request(conn, "POST", "/api/cron/jobs/$id/run")
    }

    fun cronJobState(conn: Connection, id: String): CronJobStateDto =
        decode(request(conn, "GET", "/api/cron/jobs/$id/state"), CronJobStateDto.serializer())

    fun cronJobHistory(conn: Connection, id: String): List<CronExecutionRecordDto> =
        decode(
            request(conn, "GET", "/api/cron/jobs/$id/history"),
            kotlinx.serialization.builtins.ListSerializer(CronExecutionRecordDto.serializer()),
        )

    fun cronDispatchTargets(
        conn: Connection,
        channel: String? = null,
        keyword: String? = null,
        limit: Int = 500,
    ): CronDispatchTargetsDto {
        val query = buildList {
            add("limit=$limit")
            channel?.takeIf { it.isNotBlank() }?.let { add("channel=$it") }
            keyword?.takeIf { it.isNotBlank() }?.let { add("keyword=$it") }
        }.joinToString("&")
        return decode(
            request(conn, "GET", "/api/cron/dispatch-targets?$query"),
            CronDispatchTargetsDto.serializer(),
        )
    }

    /** 服务端配置的时区，只读。值为空或无法解析时返回 null。 */
    fun userTimezone(conn: Connection): String? =
        decode(
            request(conn, "GET", "/api/config/user-timezone"),
            UserTimezoneDto.serializer(),
        ).timezone?.takeIf { it.isNotBlank() }

    private fun cronBody(spec: CronJobSpecDto): JsonObject =
        json.encodeToJsonElement(CronJobSpecDto.serializer(), spec).jsonObject

    // ---------- 工具调用控制 ----------

    fun toolCalls(conn: Connection, sessionId: String): ToolCallListDto =
        decode(
            request(conn, "GET", "/api/tool-calls/$sessionId"),
            ToolCallListDto.serializer(),
        )

    fun toolCall(conn: Connection, sessionId: String, toolCallId: String): ToolCallInfoDto =
        decode(
            request(conn, "GET", "/api/tool-calls/$sessionId/$toolCallId"),
            ToolCallInfoDto.serializer(),
        )

    /**
     * 取消执行。服务端返回 202，响应体为空或只有一个确认对象。
     *
     * 与 [stopChat] 一样不解析返回值：`decode` 遇到空体会抛「服务端返回了空响应」。
     * 409 表示工具状态已变化（已结束或已达上限），由调用方转成中文提示。
     */
    fun cancelToolCall(
        conn: Connection,
        sessionId: String,
        toolCallId: String,
        force: Boolean = false,
    ) {
        val body = buildJsonObject { put("force", force) }
        request(conn, "POST", "/api/tool-calls/$sessionId/$toolCallId/cancel", body)
    }

    fun offloadToolCall(conn: Connection, sessionId: String, toolCallId: String) {
        request(conn, "POST", "/api/tool-calls/$sessionId/$toolCallId/offload")
    }

    /**
     * 延长时限。`target` 取 `offload`（推迟转后台）或 `kill`（延长执行超时）。
     *
     * `noDeadline` 为真表示不再自动转后台 / 不设中止时限，与 `seconds` 互斥；
     * 服务端对 `kill` 的上限另有校验（`max_internal_timeout_secs`）。
     */
    fun extendToolDeadline(
        conn: Connection,
        sessionId: String,
        toolCallId: String,
        seconds: Double? = null,
        noDeadline: Boolean = false,
        target: String = "offload",
    ) {
        val body = buildJsonObject {
            seconds?.let { put("seconds", it) }
            put("no_deadline", noDeadline)
            put("target", target)
        }
        request(conn, "POST", "/api/tool-calls/$sessionId/$toolCallId/extend-deadline", body)
    }

    fun toolCallOutput(conn: Connection, sessionId: String, toolCallId: String): ToolCallOutputDto =
        decode(
            request(conn, "GET", "/api/tool-calls/$sessionId/$toolCallId/output"),
            ToolCallOutputDto.serializer(),
        )

    /** 实时输出的 SSE 请求。服务端不回放，晚订阅只能拿到结束帧。 */
    fun toolCallStreamRequest(conn: Connection, sessionId: String, toolCallId: String): Request =
        baseBuilder(conn, connUrl(conn, "/api/tool-calls/$sessionId/$toolCallId/stream"))
            .header("Accept", "text/event-stream")
            .build()

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
        val url = connUrl(conn, "/api/console/upload")
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
        baseBuilder(conn, connUrl(conn, "/api/console/chat"))
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .build()

    // ---------- 工作区文件 ----------

    /**
     * `GET /api/workspace/tree`。
     *
     * `root` 只接受 `workspace` / `project` / `project:<path>`。未绑定项目目录时
     * 服务端回落到默认工作区，不返回错误（2.2.2.post1 与 2.3.0b2 实测一致）。
     * `cursor` 原样回传，`has_more=false` 即到底。
     */
    fun workspaceTree(
        conn: Connection,
        root: String,
        path: String? = null,
        cursor: String? = null,
        limit: Int = WORKSPACE_TREE_LIMIT,
    ): WorkspaceTreeDto = decode(
        execute(
            baseBuilder(
                conn,
                workspaceUrl(
                    conn,
                    "/api/workspace/tree",
                    mapOf(
                        "root" to root,
                        "path" to path?.takeIf { it.isNotBlank() },
                        "cursor" to cursor,
                        "limit" to limit.toString(),
                    ),
                ),
            ).get().build(),
        ),
        WorkspaceTreeDto.serializer(),
    )

    /**
     * `GET /api/workspace/file-content`。
     *
     * `limit` 是字节上限，服务端会把 `next_offset` 落到 UTF-8 边界；返回值可能略大于
     * 请求值，因此续读必须用响应里的 `next_offset`，不要自己加 `limit`。
     */
    fun workspaceFileContent(
        conn: Connection,
        root: String,
        path: String,
        offset: Long,
        limit: Int = WORKSPACE_CONTENT_LIMIT,
    ): WorkspaceFileContentDto = decode(
        execute(
            baseBuilder(
                conn,
                workspaceUrl(
                    conn,
                    "/api/workspace/file-content",
                    mapOf(
                        "root" to root,
                        "path" to path,
                        "offset" to offset.toString(),
                        "limit" to limit.toString(),
                    ),
                ),
            ).get().build(),
        ),
        WorkspaceFileContentDto.serializer(),
    )

    /** `GET /api/workspace/memory`。响应是裸数组，不是 `{items: []}`。 */
    fun workspaceMemory(conn: Connection): List<WorkspaceMemoryFileDto> = decode(
        request(conn, "GET", "/api/workspace/memory"),
        ListSerializer(WorkspaceMemoryFileDto.serializer()),
    )

    /**
     * `GET /api/workspace/memory/{md_path}`。
     *
     * [mdPath] 是列表里的 `filename`，可含子目录；按路径段编码，避免文件名里的
     * `?`、`#` 被当成查询或片段。
     */
    fun workspaceMemoryFile(conn: Connection, mdPath: String): String = decode(
        execute(baseBuilder(conn, memoryUrl(conn, mdPath)).get().build()),
        WorkspaceMemoryContentDto.serializer(),
    ).content

    /**
     * 构造单文件下载请求（不执行）。
     *
     * 服务端会回 `Accept-Ranges: bytes`，但带 `Range` 仍返回 200 与完整内容，
     * 因此调用方不能做续传；中断后只能整文件重下。
     */
    fun workspaceFileDownloadRequest(conn: Connection, root: String, path: String): Request =
        baseBuilder(
            conn,
            workspaceUrl(conn, "/api/workspace/file-download", mapOf("root" to root, "path" to path)),
        ).get().build()

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
        val url = connUrl(conn, path)
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

    /**
     * 请求地址，必要时把 token 挪到查询参数上。
     *
     * 只有「反代 Basic 与 QwenPaw token 同时存在」时才改写：`Authorization`
     * 一个请求只能有一个值，让给 Basic 之后 token 只能走 `?token=`。
     * 服务端 `AuthMiddleware._extract_token` 在 Bearer 缺失时读这个参数，
     * 因此是官方支持的通路。代价是 token 会出现在反代的 access log 里，
     * 这一点写在 `docs/protocol.md` 第 1 节。
     */
    private fun connUrl(conn: Connection, path: String): HttpUrl =
        applyToken(conn, resolve(conn.baseUrl, path))

    /** 带查询参数的地址。值为 null 的参数不下发，避免产生 `path=` 这类空值。 */
    private fun workspaceUrl(conn: Connection, path: String, params: Map<String, String?>): HttpUrl {
        val builder = resolve(conn.baseUrl, path).newBuilder()
        params.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }
        return applyToken(conn, builder.build())
    }

    /** 记忆端点把相对路径放在 path 段上，逐段编码。 */
    private fun memoryUrl(conn: Connection, mdPath: String): HttpUrl {
        val builder = resolve(conn.baseUrl, "/api/workspace/memory").newBuilder()
        mdPath.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return applyToken(conn, builder.build())
    }

    /**
     * 反代 Basic 与 QwenPaw token 同时存在时，把 token 挪到查询参数上。
     *
     * `Authorization` 一个请求只能有一个值，让给 Basic 之后 token 只能走 `?token=`。
     * 服务端 `AuthMiddleware._extract_token` 在 Bearer 缺失时读这个参数，
     * 因此是官方支持的通路。代价是 token 会出现在反代的 access log 里，
     * 这一点写在 `docs/protocol.md` 第 1 节。
     */
    private fun applyToken(conn: Connection, url: HttpUrl): HttpUrl {
        val token = conn.token?.takeIf { it.isNotBlank() }
        if (conn.basicAuth == null || token == null) return url
        return url.newBuilder().addQueryParameter("token", token).build()
    }

    private fun baseBuilder(conn: Connection, url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url).header("X-Agent-Id", conn.agentId)
        conn.basicAuth?.let { builder.header("Authorization", it.headerValue) }
            ?: conn.token?.takeIf { it.isNotBlank() }
                ?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request): String {
        // 「带过凭据」指的是 QwenPaw 自己的凭据（Bearer 或 `token=`），
        // 反代 Basic 不算：反代放行后服务端仍可能说没登录。
        val tokenSent = request.header("Authorization")?.startsWith("Bearer ") == true ||
            request.url.queryParameter("token") != null
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 拦截器只看到状态码，看不出服务端为什么拒绝；这里补上 detail。
                val detail = extractDetail(text, response.code)
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.NETWORK,
                    "API",
                    "${request.method} ${request.url.encodedPath} → ${response.code} $detail",
                )
                throw failureOf(response.code, text, response.header("WWW-Authenticate"), tokenSent)
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
        val builder = baseBuilder(conn, connUrl(conn, path))
        when (method) {
            "GET" -> builder.get()
            else -> error("不支持的方法 $method")
        }
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val detail = extractDetail(body, response.code)
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.NETWORK,
                    "API",
                    "${method} ${response.request.url.encodedPath} → ${response.code} $detail",
                )
                throw failureOf(
                    response.code,
                    body,
                    response.header("WWW-Authenticate"),
                    builder.build().header("Authorization")?.startsWith("Bearer ") == true,
                )
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * 构造一次失败。
     *
     * [detail] 与 [message] 分开传：`extractDetail` 会把原文截断并去掉引号，
     * 而判定 401 成因需要原文里那两个固定短语。这里让 [ApiException.detail]
     * 保留截断后的原文，足够做短语匹配。
     */
    private fun failureOf(
        code: Int,
        body: String,
        wwwAuthenticate: String?,
        tokenSent: Boolean,
    ): ApiException = ApiException(
        code = code,
        message = extractDetail(body, code),
        detail = extractDetail(body, code),
        wwwAuthenticate = wwwAuthenticate,
        tokenSent = tokenSent,
    )

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

        /** 收件箱事件列表端点。 */
        private const val INBOX_EVENTS_PATH = "/api/console/inbox/events"

        /** 工作区目录每页条数。服务端没有上限说明，200 与实测一致。 */
        private const val WORKSPACE_TREE_LIMIT = 200

        /** 文本预览单次读取的字节上限；服务端会按 UTF-8 边界微调。 */
        private const val WORKSPACE_CONTENT_LIMIT = 64 * 1024

        /**
         * 登录接口 404 的说明。
         *
         * 这一条要回答的是「我该改什么」，而不是「哪里错了」——前者用户能动手，
         * 后者只会把人引向会话数据。
         */
        internal const val LOGIN_NOT_REACHED =
            "登录接口返回 404。若服务端只接受 https，http 上的登录请求会被跳转改写成 GET 并丢失请求体；" +
                "请把服务器地址改为 https:// 开头"

        /**
         * 登录接口 401 的说明。
         *
         * 用户此时还没拿到过 token，说「登录已失效」会把人引向一个不存在的失效状态。
         */
        internal const val BAD_CREDENTIALS = "用户名或密码不正确"

        /**
         * 配套插件的自述端点。
         *
         * **不能**用 `/status` 或 `/files` 下任意子路径：核心的 `plugins.py` 已注册同前缀下的
         * 这两条路由且先于插件 router 匹配，实测在未装插件的实例上会返回带参数化
         * detail 的 404（`Plugin '…' not found.`），请求根本到不了插件。
         * `/info`、`/search`、`/events` 是核心没有的子路径，可以安全使用。
         */
        const val BRIDGE_INFO_PATH = "/api/plugins/qwenpaw-node-bridge/info"

        /** 插件全文检索端点。中文查询由插件侧走 `LIKE`，英文走 FTS5。 */
        const val BRIDGE_SEARCH_PATH = "/api/plugins/qwenpaw-node-bridge/search"

        /** 插件事件流端点（SSE）。 */
        const val BRIDGE_EVENTS_PATH = "/api/plugins/qwenpaw-node-bridge/events"

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
                // 也不复用连接：QwenPaw（uvicorn）的 keep-alive 只有 5 秒，
                // 两次验证的间隔通常远超它，池里的连接早已被对端关闭。
                // 复用它只会读到 EOF，把「地址是通的」误报成「网络中断」；
                // 每次探测重新握手，一次失败就真的是失败。
                .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                .addNetworkInterceptor(io.qwenpaw.node.log.LoggingInterceptor(io.qwenpaw.node.log.LogKind.NETWORK))
                .build()
        }

        fun probeClient(): OkHttpClient = probeClientShared

        /**
         * 事件流专用客户端。
         *
         * 在 [probeClientShared] 基础上关掉两个超时：SSE 空闲期不产生任何字节，
         * 5 秒读超时与 8 秒总超时都会把正常连接当失败终止。
         */
        private val streamClientShared: OkHttpClient by lazy {
            probeClientShared.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        fun streamClient(): OkHttpClient = streamClientShared

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
