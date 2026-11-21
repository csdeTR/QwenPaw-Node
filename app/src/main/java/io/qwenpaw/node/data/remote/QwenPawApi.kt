package io.qwenpaw.node.data.remote

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.AclActionEntryDto
import io.qwenpaw.node.data.model.AclChannelDto
import io.qwenpaw.node.data.model.AclPendingDto
import io.qwenpaw.node.data.model.AllowNoAuthHostsDto
import io.qwenpaw.node.data.model.ApiErrorDetailDto
import io.qwenpaw.node.data.model.AcpNodeRuntimeDto
import io.qwenpaw.node.data.model.AgentBackendDto
import io.qwenpaw.node.data.model.AgentBackendsDto
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.ui.i18n.Texts
import io.qwenpaw.node.data.model.AgentListDto
import io.qwenpaw.node.data.model.MailAclActionEntryDto
import io.qwenpaw.node.data.model.MailAclAgentsDto
import io.qwenpaw.node.data.model.MailAclPauseDto
import io.qwenpaw.node.data.model.MailAclPendingCountDto
import io.qwenpaw.node.data.model.AgentProfileDto
import io.qwenpaw.node.data.model.AgentRefDto
import io.qwenpaw.node.data.model.AgentStatsSummaryDto
import io.qwenpaw.node.data.model.AgentStatusDto
import io.qwenpaw.node.data.model.ApprovalDto
import io.qwenpaw.node.data.model.ApprovalListDto
import io.qwenpaw.node.data.model.AuthStatusDto
import io.qwenpaw.node.data.model.AuthTokenDto
import io.qwenpaw.node.data.model.AvailableCommandsDto
import io.qwenpaw.node.data.model.BackupDetailDto
import io.qwenpaw.node.data.model.BackupJobSnapshotDto
import io.qwenpaw.node.data.model.BackupMetaDto
import io.qwenpaw.node.data.model.BatchChatResultDto
import io.qwenpaw.node.data.model.BridgeInfoDto
import io.qwenpaw.node.data.model.BridgeBrowserLogsDto
import io.qwenpaw.node.data.model.BridgeUpdateStatusDto
import io.qwenpaw.node.data.model.BrowserCheckDto
import io.qwenpaw.node.data.model.BrowserChromeStatusDto
import io.qwenpaw.node.data.model.BrowserSelfTestDto
import io.qwenpaw.node.data.model.BrowseDirsDto
import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatHistoryDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.model.ChatStatusDto
import io.qwenpaw.node.data.model.ChannelConflictDto
import io.qwenpaw.node.data.model.ChannelHealthDto
import io.qwenpaw.node.data.model.ChannelQrDto
import io.qwenpaw.node.data.model.ChannelRestartDto
import io.qwenpaw.node.data.model.ChannelQrStatusDto
import io.qwenpaw.node.data.model.CheckpointGraphDto
import io.qwenpaw.node.data.model.CheckpointStatusDto
import io.qwenpaw.node.data.model.CreateBackupRequestDto
import io.qwenpaw.node.data.model.CronDispatchTargetsDto
import io.qwenpaw.node.data.model.CronExecutionRecordDto
import io.qwenpaw.node.data.model.CronJobSpecDto
import io.qwenpaw.node.data.model.CronJobStateDto
import io.qwenpaw.node.data.model.CronJobViewDto
import io.qwenpaw.node.data.model.DeleteBackupsResultDto
import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto
import io.qwenpaw.node.data.model.FileGuardDto
import io.qwenpaw.node.data.model.GcRequestDto
import io.qwenpaw.node.data.model.GcResultDto
import io.qwenpaw.node.data.model.GcSettingsDto
import io.qwenpaw.node.data.model.GitBranchDto
import io.qwenpaw.node.data.model.GitCheckoutResultDto
import io.qwenpaw.node.data.model.GitCommitDiffDto
import io.qwenpaw.node.data.model.GitDiffDto
import io.qwenpaw.node.data.model.GitDiscardResultDto
import io.qwenpaw.node.data.model.GitLogEntryDto
import io.qwenpaw.node.data.model.GitRevertResultDto
import io.qwenpaw.node.data.model.GitStatusDto
import io.qwenpaw.node.data.model.HarnessesDto
import io.qwenpaw.node.data.model.HarnessProviderDto
import io.qwenpaw.node.data.model.InboxPageDto
import io.qwenpaw.node.data.model.InboxTraceDto
import io.qwenpaw.node.data.model.LocalModelConfigDto
import io.qwenpaw.node.data.model.LocalModelDto
import io.qwenpaw.node.data.model.LocalModelProgressDto
import io.qwenpaw.node.data.model.LocalModelServerDto
import io.qwenpaw.node.data.model.LocalModelServerUpdateDto
import io.qwenpaw.node.data.model.LocalWhisperStatusDto
import io.qwenpaw.node.data.model.LlmToolDailyDto
import io.qwenpaw.node.data.model.MarketCategoryDto
import io.qwenpaw.node.data.model.MarketProviderDto
import io.qwenpaw.node.data.model.MarketSearchResponseDto
import io.qwenpaw.node.data.model.McpClientDto
import io.qwenpaw.node.data.model.McpClientWriteDto
import io.qwenpaw.node.data.model.McpOAuthStartDto
import io.qwenpaw.node.data.model.McpOAuthStartResultDto
import io.qwenpaw.node.data.model.McpOAuthStatusDto
import io.qwenpaw.node.data.model.McpPolicyDto
import io.qwenpaw.node.data.model.McpToolDto
import io.qwenpaw.node.data.model.SandboxStatusDto
import io.qwenpaw.node.data.model.ServerLanguageDto
import io.qwenpaw.node.data.model.SkillImportResultDto
import io.qwenpaw.node.data.model.ModelsActiveDto
import io.qwenpaw.node.data.model.ModelTemplateDto
import io.qwenpaw.node.data.model.DiscoverModelsResultDto
import io.qwenpaw.node.data.model.ProbeMultimodalDto
import io.qwenpaw.node.data.model.ModelPoolPageDto
import io.qwenpaw.node.data.model.OAuthStartDto
import io.qwenpaw.node.data.model.OAuthStatusDto
import io.qwenpaw.node.data.model.OffloadPolicyDto
import io.qwenpaw.node.data.model.BroadcastResultDto
import io.qwenpaw.node.data.model.HubInstallTaskDto
import io.qwenpaw.node.data.model.PawAppDto
import io.qwenpaw.node.data.model.PawAppsDto
import io.qwenpaw.node.data.model.PluginCatalogDto
import io.qwenpaw.node.data.model.PluginConfigWriteDto
import io.qwenpaw.node.data.model.PluginEnabledWriteDto
import io.qwenpaw.node.data.model.PluginInstallResultDto
import io.qwenpaw.node.data.model.PluginListItemDto
import io.qwenpaw.node.data.model.PluginMarketResponseDto
import io.qwenpaw.node.data.model.BuiltinNoticeDto
import io.qwenpaw.node.data.model.BuiltinSourceDto
import io.qwenpaw.node.data.model.PoolDownloadJobDto
import io.qwenpaw.node.data.model.PoolSkillDetailDto
import io.qwenpaw.node.data.model.PoolSkillDto
import io.qwenpaw.node.data.model.SkillWorkspaceDto
import io.qwenpaw.node.data.model.PortabilityJobDto
import io.qwenpaw.node.data.model.PortabilitySelectionDto
import io.qwenpaw.node.data.model.PortabilitySourceDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ProjectDirDto
import io.qwenpaw.node.data.model.PushMessagesDto
import io.qwenpaw.node.data.model.ResetCheckpointsDto
import io.qwenpaw.node.data.model.RestoreBackupRequestDto
import io.qwenpaw.node.data.model.RestoreBackupResponseDto
import io.qwenpaw.node.data.model.RestoreCheckpointRequestDto
import io.qwenpaw.node.data.model.RestoreResultDto
import io.qwenpaw.node.data.model.SaveSkillResultDto
import io.qwenpaw.node.data.model.SearchPageDto
import io.qwenpaw.node.data.model.SkillConflictDto
import io.qwenpaw.node.data.model.SkillDetailDto
import io.qwenpaw.node.data.model.SshConfigDto
import io.qwenpaw.node.data.model.SshCredentialDto
import io.qwenpaw.node.data.model.TerminalDto
import io.qwenpaw.node.data.model.TerminalOutputDto
import io.qwenpaw.node.data.model.TerminalStatusDto
import io.qwenpaw.node.data.model.SkillDto
import io.qwenpaw.node.data.model.SlashCommandDto
import io.qwenpaw.node.data.model.SnapshotRequestDto
import io.qwenpaw.node.data.model.SnapshotResultDto
import io.qwenpaw.node.data.model.TestConnectionDto
import io.qwenpaw.node.data.model.ThinkingViewDto
import io.qwenpaw.node.data.model.TokenUsageDto
import io.qwenpaw.node.data.model.TokenUsageRecordDto
import io.qwenpaw.node.data.model.ToolCallInfoDto
import io.qwenpaw.node.data.model.ToolCallListDto
import io.qwenpaw.node.data.model.ToolCallOutputDto
import io.qwenpaw.node.data.model.ToolDto
import io.qwenpaw.node.data.model.ToolGuardRuleDto
import io.qwenpaw.node.data.model.TranscribeResultDto
import io.qwenpaw.node.data.model.TranscriptionProvidersDto
import io.qwenpaw.node.data.model.TranscriptionProviderTypeDto
import io.qwenpaw.node.data.model.UploadLimitDto
import io.qwenpaw.node.data.model.UploadResultDto
import io.qwenpaw.node.data.model.UserTimezoneDto
import io.qwenpaw.node.data.model.VerifyTokenDto
import io.qwenpaw.node.data.model.VersionDto
import io.qwenpaw.node.data.model.WorkspaceFileContentDto
import io.qwenpaw.node.data.model.WorkspaceMemoryContentDto
import io.qwenpaw.node.data.model.WorkspaceMemoryFileDto
import io.qwenpaw.node.data.model.WorkspaceSaveResultDto
import io.qwenpaw.node.data.model.WorkspaceTreeDto
import io.qwenpaw.node.data.model.WorkspaceUploadResultDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
 * 上传时目标目录已存在同名文件。
 *
 * 服务端在未指定 `conflict` 策略时会一次性列出全部冲突名（`409` 的 `detail` 是对象，
 * 不是字符串），因此单独抛一个带名单的异常，界面才能让用户选择覆盖还是跳过。
 */
class UploadConflictException(val names: List<String>) : IOException("upload conflict")

/** 上传同名文件的处理策略，取值与服务端 `conflict` 查询参数一致。 */
enum class UploadConflictPolicy(val param: String) {
    OVERWRITE("overwrite"),
    SKIP("skip"),
}

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
        // 服务端会把列表字段显式写成 null（例如 reasoning_effort_options），
        // 对带默认值的非空字段按默认值处理，避免一个 null 让整页解析失败。
        coerceInputValues = true
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

    fun authStatus(
        baseUrl: String,
        basicAuth: BasicAuth? = null,
        /** 验证阶段传探测客户端，避免主客户端的长读超时拖住坏地址。 */
        http: OkHttpClient? = null,
    ): AuthStatusDto =
        decode(
            request(
                Connection(baseUrl, null, basicAuth = basicAuth),
                "GET",
                "/api/auth/status",
                http = http ?: client,
            ),
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
     * 只发一个短超时的校验请求，不碰业务端点。
     *
     * 返回 true 表示 token 有效；false 表示服务端明确拒绝（401）。
     * 连不上时抛异常，由调用方按网络问题处理。
     */
    fun verifyToken(conn: Connection): Boolean =
        runCatching { verifyTokenDetail(conn).valid }.getOrElse { e ->
            // 只有明确 401 才是「失效」；其余异常（含连不上）交给调用方按网络问题处理。
            if (e is ApiException && e.code == 401) false else throw e
        }

    /**
     * 校验 token 并读出令牌所属用户。
     *
     * **反代分支**：`/api/auth/verify` 只从 `Authorization` 头里取 token，看不到
     * `?token=`。反向代理开启 `auth_basic` 时 `Authorization` 让给了 Basic，
     * 这条端点便永远读不到 token，于是返回 401——旧实现据此把反代实例上的
     * 有效令牌一律判成「失效」。这种连接改打一个业务端点（`GET /api/agents`），
     * 由服务端鉴权中间件按 `?token=` 判定，同样能回答「令牌还可用吗」。
     *
     * 返回的 `username` 以服务端响应体为准；拿不到时（反代分支、服务端未启用
     * 登录）为 null，界面只能显示令牌自身的载荷，不能当身份依据。
     */
    fun verifyTokenDetail(conn: Connection, http: OkHttpClient = probeClient()): VerifyTokenDto {
        if (conn.basicAuth != null) {
            agents(conn)
            return VerifyTokenDto(valid = true, username = null)
        }
        val request = baseBuilder(conn, connUrl(conn, "/api/auth/verify")).get().build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) {
                throw failureOf(401, text, response.header("WWW-Authenticate"), conn.token != null)
            }
            // 403/404：服务端未启用登录或没有该端点，这个断言无法回答，按有效处理，
            // 避免把用户赶去重填一遍本来就对的凭据。
            if (!response.isSuccessful) return VerifyTokenDto(valid = true, username = null)
            decode(text, VerifyTokenDto.serializer())
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
    fun pluginBridgeInfo(
        conn: Connection,
        /** 验证阶段传当前地址的探测客户端；其余调用方用默认探测档位。 */
        http: OkHttpClient = probeClient(),
    ): BridgeInfoDto? {
        val url = connUrl(conn, BRIDGE_INFO_PATH)
        val request = baseBuilder(conn, url).get().build()
        return http.newCall(request).execute().use { response ->
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
     * 问一次插件自己有没有新版本。
     *
     * 返回 `null` 表示**该插件没有这条端点**（404）：旧版本插件没有更新检查，
     * 这不是错误，客户端只是不提示。其余失败照常抛出，由调用方决定是否静默。
     *
     * 路径放在 `/update/` 下而不是 `/status`：核心 `plugins.py` 注册了
     * `/api/plugins/{id}/status` 且先于插件 router 匹配，写在 `/status` 下会被核心接走。
     */
    fun bridgeUpdateStatus(
        conn: Connection,
        http: OkHttpClient = probeClient(),
    ): BridgeUpdateStatusDto? {
        val url = connUrl(conn, BRIDGE_UPDATE_STATUS_PATH)
        val request = baseBuilder(conn, url).get().build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw failureOf(response.code, text, response.header("WWW-Authenticate"), conn.token != null)
            }
            decode(text, BridgeUpdateStatusDto.serializer())
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

    // ---------- portability（经配套插件） ----------

    fun portabilitySources(conn: Connection): List<PortabilitySourceDto> = decode(
        request(conn, "GET", "$BRIDGE_PORTABILITY_PATH/sources"),
        kotlinx.serialization.builtins.ListSerializer(PortabilitySourceDto.serializer()),
    )

    fun portabilityCreateJob(conn: Connection, sources: List<String>): PortabilityJobDto {
        val body = buildJsonObject {
            putJsonArray("sources") { sources.forEach { add(it) } }
        }
        return decode(
            request(conn, "POST", "$BRIDGE_PORTABILITY_PATH/jobs", body),
            PortabilityJobDto.serializer(),
        )
    }

    /** `GET .../jobs/current`。没有可恢复任务时服务端返回 JSON `null`。 */
    fun portabilityCurrentJob(conn: Connection): PortabilityJobDto? {
        val text = request(conn, "GET", "$BRIDGE_PORTABILITY_PATH/jobs/current")
        if (text.isBlank() || text.trim() == "null") return null
        return decode(text, PortabilityJobDto.serializer())
    }

    fun portabilityJob(conn: Connection, jobId: String): PortabilityJobDto = decode(
        request(conn, "GET", "$BRIDGE_PORTABILITY_PATH/jobs/${encodeSegment(jobId)}"),
        PortabilityJobDto.serializer(),
    )

    fun portabilityStartJob(
        conn: Connection,
        jobId: String,
        selections: Map<String, PortabilitySelectionDto>,
        allowPluginExecution: Boolean,
    ): PortabilityJobDto = portabilityAction(
        conn,
        jobId,
        "start",
        selections,
        allowPluginExecution,
    )

    fun portabilityRetryJob(
        conn: Connection,
        jobId: String,
        selections: Map<String, PortabilitySelectionDto>,
        allowPluginExecution: Boolean,
    ): PortabilityJobDto = portabilityAction(
        conn,
        jobId,
        "retry",
        selections,
        allowPluginExecution,
    )

    private fun portabilityAction(
        conn: Connection,
        jobId: String,
        action: String,
        selections: Map<String, PortabilitySelectionDto>,
        allowPluginExecution: Boolean,
    ): PortabilityJobDto {
        val selectionObject = buildJsonObject {
            selections.forEach { (source, selection) ->
                put(source, json.encodeToJsonElement(PortabilitySelectionDto.serializer(), selection))
            }
        }
        val body = buildJsonObject {
            put("selections", selectionObject)
            put("allow_plugin_execution", allowPluginExecution)
        }
        return decode(
            request(
                conn,
                "POST",
                "$BRIDGE_PORTABILITY_PATH/jobs/${encodeSegment(jobId)}/$action",
                body,
            ),
            PortabilityJobDto.serializer(),
        )
    }

    fun portabilityCancelJob(conn: Connection, jobId: String): PortabilityJobDto = decode(
        request(conn, "POST", "$BRIDGE_PORTABILITY_PATH/jobs/${encodeSegment(jobId)}/cancel", buildJsonObject { }),
        PortabilityJobDto.serializer(),
    )

    // ---------- App 中心 ----------

    /** `GET /api/pawapps`。已安装的 PawApp 列表；服务端未安装时是空数组。 */
    fun pawApps(conn: Connection): PawAppsDto = decode(
        request(conn, "GET", "/api/pawapps"),
        PawAppsDto.serializer(),
    )

    // ---------- 市场安装与技能导入 ----------

    /** `POST /api/plugins/upload`。同名目录已存在时服务端回 409，需 `force=true` 重试。 */
    fun uploadPlugin(
        conn: Connection,
        source: SafZipSource,
        force: Boolean = false,
        onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> },
    ): PluginInstallResultDto {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", source.fileName, source.newBody(onProgress))
            .build()
        val url = connUrl(conn, "/api/plugins/upload").newBuilder()
            .addQueryParameter("force", force.toString())
            .build()
        return decode(
            execute(baseBuilder(conn, url).post(body).build()),
            PluginInstallResultDto.serializer(),
        )
    }

    /** `POST /api/plugins/install`。`source` 只接受 http(s) zip 或服务端本地目录。 */
    fun installPlugin(
        conn: Connection,
        source: String,
        force: Boolean = false,
    ): PluginInstallResultDto {
        val body = buildJsonObject {
            put("source", source)
            put("force", force)
        }
        return decode(
            request(conn, "POST", "/api/plugins/install", body),
            PluginInstallResultDto.serializer(),
        )
    }

    /**
     * `POST /api/skills/upload` 或 `/api/skills/pool/upload-zip`。
     *
     * 409 的 `detail` 就是 `{imported, count, conflicts}`，这里直接解析成结果返回，
     * 让界面能列出冲突并引导改名，而不是只看到一句「请求冲突」。
     */
    fun uploadSkillZip(
        conn: Connection,
        toPool: Boolean,
        source: SafZipSource,
        enable: Boolean = true,
        targetName: String = "",
        renameMap: Map<String, String>? = null,
        onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> },
    ): SkillImportResultDto {
        val path = if (toPool) "/api/skills/pool/upload-zip" else "/api/skills/upload"
        val url = workspaceUrl(
            conn,
            path,
            mapOf(
                "enable" to enable.toString(),
                "target_name" to targetName.takeIf { it.isNotBlank() },
                "rename_map" to renameMap?.takeIf { it.isNotEmpty() }?.let { map ->
                    buildJsonObject {
                        map.forEach { (key, value) -> put(key, value) }
                    }.toString()
                },
            ),
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", source.fileName, source.newBody(onProgress))
            .build()
        val request = baseBuilder(conn, url).post(body).build()
        return executeSkillResult(request)
    }

    /** `POST /api/skills/hub/install/start`，异步任务。 */
    fun hubInstallStart(
        conn: Connection,
        bundleUrl: String,
        providerKey: String? = null,
        version: String = "",
        enable: Boolean = true,
        targetName: String = "",
    ): HubInstallTaskDto {
        val body = buildJsonObject {
            providerKey?.takeIf { it.isNotBlank() }?.let { put("provider_key", it) }
            put("bundle_url", bundleUrl)
            put("version", version)
            put("enable", enable)
            put("target_name", targetName)
        }
        return decode(
            request(conn, "POST", "/api/skills/hub/install/start", body),
            HubInstallTaskDto.serializer(),
        )
    }

    fun hubInstallStatus(conn: Connection, taskId: String): HubInstallTaskDto = decode(
        request(conn, "GET", "/api/skills/hub/install/status/${encodeSegment(taskId)}"),
        HubInstallTaskDto.serializer(),
    )

    fun hubInstallCancel(conn: Connection, taskId: String) {
        request(conn, "POST", "/api/skills/hub/install/cancel/${encodeSegment(taskId)}", buildJsonObject { })
    }

    /** `POST /api/skills/pool/download`。全有或全无；409 的 detail 带冲突工作区。 */
    fun broadcastSkill(
        conn: Connection,
        skillName: String,
        targets: List<String>,
        allWorkspaces: Boolean = false,
        overwrite: Boolean = false,
    ): BroadcastResultDto {
        val body = buildJsonObject {
            put("skill_name", skillName)
            putJsonArray("targets") {
                targets.forEach { add(buildJsonObject { put("workspace_id", it) }) }
            }
            put("all_workspaces", allWorkspaces)
            put("overwrite", overwrite)
            put("preview_only", false)
        }
        val request = baseBuilder(conn, connUrl(conn, "/api/skills/pool/download"))
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return decode(text, BroadcastResultDto.serializer())
            if (response.code == 409) {
                val detail = runCatching {
                    json.parseToJsonElement(text).jsonObject["detail"]
                }.getOrNull()
                if (detail is JsonObject) {
                    return decode(detail.toString(), BroadcastResultDto.serializer())
                }
            }
            throw failureOf(response.code, text, response.header("WWW-Authenticate"), tokenSentOf(request))
        }
    }

    private fun executeSkillResult(request: Request): SkillImportResultDto =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                return decode(text, SkillImportResultDto.serializer())
            }
            if (response.code == 409) {
                val detail = runCatching {
                    json.parseToJsonElement(text).jsonObject["detail"]
                }.getOrNull()
                if (detail is JsonObject) {
                    return decode(detail.toString(), SkillImportResultDto.serializer())
                }
            }
            throw failureOf(response.code, text, response.header("WWW-Authenticate"), tokenSentOf(request))
        }

    // ---------- 智能体 ----------
    fun agents(conn: Connection): List<AgentDto> =
        decode(request(conn, "GET", "/api/agents"), AgentListDto.serializer()).agents

    /** `POST /api/agents`。`id` 留空由服务端生成。 */
    fun createAgent(
        conn: Connection,
        name: String,
        description: String = "",
        id: String? = null,
    ): AgentRefDto {
        val body = buildJsonObject {
            put("name", name)
            if (description.isNotBlank()) put("description", description)
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { put("id", it) }
        }
        return decode(
            request(conn, "POST", "/api/agents", body),
            AgentRefDto.serializer(),
        )
    }

    /** `POST /api/agents/{id}/copy`。只复制配置，不复制会话与媒体。 */
    fun copyAgent(
        conn: Connection,
        sourceId: String,
        name: String? = null,
        copySkills: Boolean = false,
        copyJobs: Boolean = false,
    ): AgentRefDto {
        val body = buildJsonObject {
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
            put("copy_md_files", true)
            put("copy_skills", copySkills)
            put("copy_jobs", copyJobs)
        }
        return decode(
            request(conn, "POST", "/api/agents/${encodeSegment(sourceId)}/copy", body),
            AgentRefDto.serializer(),
        )
    }

    /** `DELETE /api/agents/{id}`。default 不可删。 */
    fun deleteAgent(conn: Connection, agentId: String) {
        request(conn, "DELETE", "/api/agents/${encodeSegment(agentId)}")
    }

    /** `PATCH /api/agents/{id}/toggle`。default 不可禁用。 */
    fun setAgentEnabled(conn: Connection, agentId: String, enabled: Boolean) {
        val body = buildJsonObject { put("enabled", enabled) }
        request(conn, "PATCH", "/api/agents/${encodeSegment(agentId)}/toggle", body)
    }

    /** `PATCH /api/agents/{id}/pin`。default 不可取消置顶。 */
    fun setAgentPinned(conn: Connection, agentId: String, pinned: Boolean) {
        val body = buildJsonObject { put("pinned", pinned) }
        request(conn, "PATCH", "/api/agents/${encodeSegment(agentId)}/pin", body)
    }

    /** `PUT /api/agents/order`。提交完整列表；服务端校验 default 第一、置顶在前。 */
    fun reorderAgents(conn: Connection, agentIds: List<String>) {
        val body = buildJsonObject {
            putJsonArray("agent_ids") { agentIds.forEach { add(it) } }
        }
        request(conn, "PUT", "/api/agents/order", body)
    }

    /** `GET /api/agents/{id}/agent-status`。 */
    fun agentStatus(conn: Connection, agentId: String): AgentStatusDto = decode(
        request(conn, "GET", "/api/agents/${encodeSegment(agentId)}/agent-status"),
        AgentStatusDto.serializer(),
    )

    /**
     * 修改名称与描述。
     *
     * `PUT /api/agents/{id}` 要求完整配置，因此先读原始对象、只替换两项再写回；
     * 与 [setApprovalLevel] 同一条路径。
     */
    fun updateAgentProfile(
        conn: Connection,
        agentId: String,
        name: String,
        description: String,
    ): AgentProfileDto {
        val path = "/api/agents/${encodeSegment(agentId)}"
        val obj = json.parseToJsonElement(request(conn, "GET", path)).jsonObject
        val body = buildJsonObject {
            obj.forEach { (k, v) ->
                if (k != "name" && k != "description") put(k, v)
            }
            put("name", name)
            put("description", description)
        }
        return decode(request(conn, "PUT", path, body), AgentProfileDto.serializer())
    }

    /** `GET /api/agents/backends`。 */
    fun agentsBackends(conn: Connection): List<AgentBackendDto> = decode(
        request(conn, "GET", "/api/agents/backends"),
        AgentBackendsDto.serializer(),
    ).backends

    /** `PATCH /api/agents/{id}/backend-settings`，第三方后端写模型与推理档位。 */
    fun setAgentBackendSettings(
        conn: Connection,
        agentId: String,
        model: String?,
        reasoningEffort: String?,
    ): String = request(
        conn,
        "PATCH",
        "/api/agents/${encodeSegment(agentId)}/backend-settings",
        buildJsonObject {
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
        },
    )

    /** 切换智能体后端；复用整读整写，保留未建模字段。 */
    fun updateAgentBackend(conn: Connection, agentId: String, backend: String) {
        val path = "/api/agents/${encodeSegment(agentId)}"
        val obj = json.parseToJsonElement(request(conn, "GET", path)).jsonObject
        val body = buildJsonObject {
            obj.forEach { (key, value) -> if (key != "backend") put(key, value) }
            put("backend", backend)
        }
        request(conn, "PUT", path, body)
    }

    // ---------- ACP ----------

    /** `GET /api/config/acp`。整个 ACP 配置，含 node_path 与四个内置 agent。 */
    fun acpConfig(conn: Connection): JsonObject =
        json.parseToJsonElement(request(conn, "GET", "/api/config/acp")).jsonObject

    fun saveAcpConfig(conn: Connection, config: JsonObject): JsonObject =
        json.parseToJsonElement(
            request(conn, "PUT", "/api/config/acp", config),
        ).jsonObject

    /** `GET /api/config/acp/node-runtime`。 */
    fun acpNodeRuntime(conn: Connection): AcpNodeRuntimeDto = decode(
        request(conn, "GET", "/api/config/acp/node-runtime"),
        AcpNodeRuntimeDto.serializer(),
    )

    /** `PUT /api/config/acp/node-runtime`。路径不可用时服务端回 400，detail 是对象。 */
    fun saveAcpNodeRuntime(conn: Connection, nodePath: String): AcpNodeRuntimeDto {
        val body = buildJsonObject { put("node_path", nodePath) }
        return decode(
            request(conn, "PUT", "/api/config/acp/node-runtime", body),
            AcpNodeRuntimeDto.serializer(),
        )
    }

    /** `GET /api/config/acp/{agent_name}`。返回原始对象，避免七个字段之外的项被抹掉。 */
    fun acpAgentConfig(conn: Connection, agentName: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/config/acp/${encodeSegment(agentName)}"),
        ).jsonObject

    fun saveAcpAgentConfig(
        conn: Connection,
        agentName: String,
        config: JsonObject,
    ): JsonObject =
        json.parseToJsonElement(
            request(conn, "PUT", "/api/config/acp/${encodeSegment(agentName)}", config),
        ).jsonObject

    // ---------- Harness ----------

    /** `GET /api/harnesses`。 */
    fun harnesses(conn: Connection): List<HarnessProviderDto> = decode(
        request(conn, "GET", "/api/harnesses"),
        HarnessesDto.serializer(),
    ).providers

    /** `GET /api/harnesses/{id}/models`。各 provider 的返回形状不同，保留原始 JSON。 */
    fun harnessModels(conn: Connection, providerId: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/harnesses/${encodeSegment(providerId)}/models"),
        ).jsonObject

    fun harnessMcp(conn: Connection, providerId: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/harnesses/${encodeSegment(providerId)}/mcp"),
        ).jsonObject

    fun harnessSkills(conn: Connection, providerId: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/harnesses/${encodeSegment(providerId)}/skills"),
        ).jsonObject

    /** `POST /api/harnesses/{id}/status`，用未保存的设置探测一次。 */
    fun harnessStatus(
        conn: Connection,
        providerId: String,
        settings: JsonObject = JsonObject(emptyMap()),
    ): HarnessProviderDto {
        val body = buildJsonObject { put("settings", settings) }
        return decode(
            request(conn, "POST", "/api/harnesses/${encodeSegment(providerId)}/status", body),
            HarnessProviderDto.serializer(),
        )
    }

    /**
     * `POST /api/harnesses/{id}/login`。
     *
     * codex 支持设备码，返回里带用户码与验证地址；qoder 忽略 `device_code`，
     * 返回 `{type:"external", command}`。调用方按字段是否存在分别呈现。
     */
    fun harnessLogin(
        conn: Connection,
        providerId: String,
        deviceCode: Boolean = true,
    ): JsonObject {
        val body = buildJsonObject {
            put("device_code", deviceCode)
            put("settings", JsonObject(emptyMap()))
        }
        return json.parseToJsonElement(
            request(conn, "POST", "/api/harnesses/${encodeSegment(providerId)}/login", body),
        ).jsonObject
    }

    /** `POST /api/harnesses/{id}/logout`。 */
    fun harnessLogout(conn: Connection, providerId: String) {
        val body = buildJsonObject { put("settings", JsonObject(emptyMap())) }
        request(conn, "POST", "/api/harnesses/${encodeSegment(providerId)}/logout", body)
    }

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

    /**
     * 指定作用域的默认模型，对应 `GET /api/models/active?scope=`。
     *
     * 三个 scope 的语义不同，不能互相替代：
     * - `global`：服务端全局默认；
     * - `agent`：该智能体**配置里显式写下**的模型，没写过时 `active_llm` 为 `null`；
     * - `effective`：agent 优先、否则回落全局，即实际生效的那个。
     *
     * 因此「当前智能体默认」一行读 `agent`，为空时界面写「跟随全局」而不是把全局值抄过来。
     */
    fun activeModels(conn: Connection, scope: String, agentId: String? = null): ModelsActiveDto {
        // 编码交给 HttpUrl：智能体 id 里可能出现空格或中文。
        val builder = connUrl(conn, "/api/models/active").newBuilder().addQueryParameter("scope", scope)
        // 只有 scope=agent 才带 agent_id：服务端在缺该参数时回 400
        // 「agent_id is required when scope is 'agent'」，而 global / effective 带上它无意义。
        if (scope == "agent" && agentId != null) builder.addQueryParameter("agent_id", agentId)
        return decode(execute(baseBuilder(conn, builder.build()).get().build()), ModelsActiveDto.serializer())
    }

    /**
     * 设置默认模型，对应 `PUT /api/models/active`。
     *
     * body 必须带 `scope`（`global` / `agent`）——服务端没有「只写 provider 与 model」的形态。
     * `scope=agent` 缺 `agent_id` 回 400，供应商不存在回 404，两条都是服务端原文。
     */
    fun setActiveModel(
        conn: Connection,
        providerId: String,
        model: String,
        scope: String,
        agentId: String? = null,
        modelType: String = "chat",
    ): ModelsActiveDto {
        val body = buildJsonObject {
            put("provider_id", providerId)
            put("model", model)
            put("scope", scope)
            put("model_type", modelType)
            if (scope == "agent" && agentId != null) put("agent_id", agentId)
        }
        return decode(request(conn, "PUT", "/api/models/active", body), ModelsActiveDto.serializer())
    }

    /**
     * 供应商连通性测试，对应 `POST /api/models/{provider_id}/test`。
     *
     * **不带 body**：服务端把它声明为 `Optional[TestProviderRequest]`，省略即可。
     * 失败不是 HTTP 错误——实测返回 200 + `success=false`，调用方要看 `success`。
     * 路径带的是**供应商** id；模型级测试是 `/{provider_id}/models/test`，本期不接。
     */
    /**
     * 模型级连通性测试：`POST /api/models/{provider_id}/models/test`。
     *
     * body 必填 `{model_id}`。供应商级 `/{provider_id}/test` 不再提供界面入口：
     * 用户在模型条目上看到的结果才对应实际要用的那个模型。
     */
    fun testModel(conn: Connection, providerId: String, modelId: String): TestConnectionDto = decode(
        request(
            conn,
            "POST",
            "/api/models/${encodeSegment(providerId)}/models/test",
            buildJsonObject { put("model_id", modelId) },
        ),
        TestConnectionDto.serializer(),
    )

    // ---------- 模型与提供商管理 ----------

    fun modelTemplates(conn: Connection): List<ModelTemplateDto> = decode(
        request(conn, "GET", "/api/models/model-templates"),
        kotlinx.serialization.builtins.ListSerializer(ModelTemplateDto.serializer()),
    )

    fun createCustomProvider(conn: Connection, body: JsonObject): String =
        request(conn, "POST", "/api/models/custom-providers", body)

    fun deleteCustomProvider(conn: Connection, providerId: String) {
        request(conn, "DELETE", "/api/models/custom-providers/${encodeSegment(providerId)}")
    }

    fun configureProvider(conn: Connection, providerId: String, body: JsonObject): String =
        request(conn, "PUT", "/api/models/${encodeSegment(providerId)}/config", body)

    fun discoverProviderModels(conn: Connection, providerId: String, body: JsonObject?): DiscoverModelsResultDto =
        decode(
            request(conn, "POST", "/api/models/${encodeSegment(providerId)}/discover", body),
            DiscoverModelsResultDto.serializer(),
        )

    fun addModel(conn: Connection, providerId: String, body: JsonObject): String =
        request(conn, "POST", "/api/models/${encodeSegment(providerId)}/models", body)

    fun removeModel(conn: Connection, providerId: String, modelId: String) {
        request(conn, "DELETE", "/api/models/${encodeSegment(providerId)}/models/$modelId")
    }

    fun configureModel(
        conn: Connection,
        providerId: String,
        modelId: String,
        body: JsonObject,
    ): String = request(
        conn,
        "PUT",
        "/api/models/${encodeSegment(providerId)}/models/$modelId/config",
        body,
    )

    fun setModelVisibility(
        conn: Connection,
        providerId: String,
        modelId: String,
        hidden: Boolean,
    ) {
        request(
            conn,
            "PUT",
            "/api/models/${encodeSegment(providerId)}/models/$modelId/visibility",
            buildJsonObject { put("hidden", hidden) },
        )
    }

    fun probeModelMultimodal(
        conn: Connection,
        providerId: String,
        modelId: String,
    ): ProbeMultimodalDto = decode(
        request(
            conn,
            "POST",
            "/api/models/${encodeSegment(providerId)}/models/$modelId/probe-multimodal",
        ),
        ProbeMultimodalDto.serializer(),
    )

    fun modelPool(
        conn: Connection,
        providerId: String,
        tab: String = "all",
        search: String = "",
        family: String = "all",
        offset: Int = 0,
        limit: Int = 30,
    ): ModelPoolPageDto = decode(
        getUrl(
            conn,
            workspaceUrl(
                conn,
                "/api/models/${encodeSegment(providerId)}/pool",
                mapOf(
                    "tab" to tab,
                    "search" to search.takeIf { it.isNotBlank() },
                    "family" to family.takeIf { it != "all" },
                    "offset" to offset.toString(),
                    "limit" to limit.toString(),
                ),
            ),
        ),
        ModelPoolPageDto.serializer(),
    )

    fun selectAllPoolModels(conn: Connection, providerId: String, selected: Boolean) {
        request(
            conn,
            "PUT",
            "/api/models/${encodeSegment(providerId)}/pool/selection",
            buildJsonObject { put("selected", selected) },
        )
    }

    fun selectPoolModel(
        conn: Connection,
        providerId: String,
        modelId: String,
        selected: Boolean,
    ) {
        request(
            conn,
            "PUT",
            "/api/models/${encodeSegment(providerId)}/models/$modelId/pool",
            buildJsonObject { put("selected", selected) },
        )
    }

    fun oauthStart(conn: Connection, providerId: String): OAuthStartDto = decode(
        request(conn, "POST", "/api/providers/${encodeSegment(providerId)}/oauth/start"),
        OAuthStartDto.serializer(),
    )

    fun oauthStatus(conn: Connection, providerId: String, state: String): OAuthStatusDto = decode(
        getUrl(
            conn,
            workspaceUrl(
                conn,
                "/api/providers/${encodeSegment(providerId)}/oauth/status",
                mapOf("state" to state),
            ),
        ),
        OAuthStatusDto.serializer(),
    )

    /**
     * 智能体模型设置：`GET /api/agents/{id}` 返回完整配置，这里取原始对象。
     *
     * 只读其中的 `fallback_models` / `fallback_policy` / `subagent_model` /
     * `thinking_level` / `thinking_budget` / `decision_model`，不复制整份 Agent DTO。
     */
    fun agentModelSettings(conn: Connection, agentId: String): JsonObject =
        Json.parseToJsonElement(
            request(conn, "GET", "/api/agents/${encodeSegment(agentId)}"),
        ).jsonObject

    /** `PATCH /api/agents/{id}/model-settings`，提交完整快照，返回更新后的配置。 */
    fun updateAgentModelSettings(
        conn: Connection,
        agentId: String,
        body: JsonObject,
    ): JsonObject = Json.parseToJsonElement(
        request(
            conn,
            "PATCH",
            "/api/agents/${encodeSegment(agentId)}/model-settings",
            body,
        ),
    ).jsonObject

    /** 智能体级决策模型：`PATCH /api/agents/{agentId}/model-settings`。 */
    fun setAgentDecisionModel(conn: Connection, agentId: String, providerId: String, model: String) {
        val body = buildJsonObject {
            putJsonObject("decision_model") {
                put("provider_id", providerId)
                put("model", model)
            }
        }
        request(
            conn,
            "PATCH",
            "/api/agents/${encodeSegment(agentId)}/model-settings",
            body,
        )
    }

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

    /**
     * token 用量汇总，对应 `GET /api/token-usage`。
     *
     * **全局**口径：不受 `X-Agent-Id` 过滤，与服务端的智能体统计是两套数。
     */
    fun tokenUsage(conn: Connection, startDate: String? = null, endDate: String? = null): TokenUsageDto =
        decode(
            request(conn, "GET", "/api/token-usage${dateRangeQuery(startDate, endDate)}"),
            TokenUsageDto.serializer(),
        )

    /**
     * 原始用量记录，对应 `GET /api/token-usage/details`。
     *
     * 一行是「日期 × 智能体 × 供应商 × 模型」，行数等于有调用的天数，不是区间天数
     * （实测 90 天区间只有 4 行）。留给调用方按模型聚合。
     */
    fun tokenUsageDetails(
        conn: Connection,
        startDate: String? = null,
        endDate: String? = null,
    ): List<TokenUsageRecordDto> = decode(
        request(conn, "GET", "/api/token-usage/details${dateRangeQuery(startDate, endDate)}"),
        kotlinx.serialization.builtins.ListSerializer(TokenUsageRecordDto.serializer()),
    )

    /**
     * 智能体统计汇总，对应 `GET /api/agent-stats`。
     *
     * **按 `X-Agent-Id` 过滤**，只有当前智能体的数据；与 [tokenUsage] 的全局口径不同，
     * 界面上两个区块必须各自标注范围。
     */
    fun agentStats(
        conn: Connection,
        startDate: String? = null,
        endDate: String? = null,
    ): AgentStatsSummaryDto = decode(
        request(conn, "GET", "/api/agent-stats${dateRangeQuery(startDate, endDate)}"),
        AgentStatsSummaryDto.serializer(),
    )

    /**
     * 全局调用趋势，对应 `GET /api/agent-stats/llm-tool-trend`。返回裸数组、跨智能体汇总。
     *
     * 实测服务端已把整个区间补齐零值（93 天返回 93 条），客户端只做幂等归一化。
     */
    fun agentLlmToolTrend(
        conn: Connection,
        startDate: String? = null,
        endDate: String? = null,
    ): List<LlmToolDailyDto> = decode(
        request(conn, "GET", "/api/agent-stats/llm-tool-trend${dateRangeQuery(startDate, endDate)}"),
        kotlinx.serialization.builtins.ListSerializer(LlmToolDailyDto.serializer()),
    )

    /** 统计类端点的日期范围查询串。两个都为空时返回空串，不拼一个空的 `?`。 */
    private fun dateRangeQuery(startDate: String?, endDate: String?): String {
        val parts = buildList {
            if (startDate != null) add("start_date=$startDate")
            if (endDate != null) add("end_date=$endDate")
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
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

    /**
     * 整体替换环境变量。
     *
     * `QWENPAW_AUTH_ENABLED` 在 0.7.0 轮实测只能走 PUT；PUT 会删掉未提交的键，
     * 因此调用方必须先把当前全量值合并进来。
     */
    fun putEnvVars(conn: Connection, values: Map<String, String>): List<EnvVarDto> {
        val body = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }
        return decode(
            request(conn, "PUT", "/api/envs", body),
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

    // ---------- 技能 ----------

    /** 工作区技能列表。**已启用的技能也在列表里**，靠 `enabled` 区分。 */
    fun skills(conn: Connection): List<SkillDto> =
        decode(
            request(conn, "GET", "/api/skills"),
            kotlinx.serialization.builtins.ListSerializer(SkillDto.serializer()),
        )

    fun skill(conn: Connection, name: String): SkillDetailDto =
        decode(
            request(conn, "GET", "/api/skills/${encodeSegment(name)}"),
            SkillDetailDto.serializer(),
        )

    /**
     * 启用技能。
     *
     * 服务端会重新扫描技能目录，命中安全策略时回 **422** 且 body 是
     * `{type:"security_scan_failed", ...}`，不是普通校验错误。调用方按
     * `ApiException.code == 422` 单独给文案。
     */
    fun enableSkill(conn: Connection, name: String): String =
        request(conn, "POST", "/api/skills/${encodeSegment(name)}/enable")

    fun disableSkill(conn: Connection, name: String): String =
        request(conn, "POST", "/api/skills/${encodeSegment(name)}/disable")

    /**
     * 原地保存 `SKILL.md` 正文。
     *
     * `mode` 为 `noop` 表示内容与服务端一致，没有写入。**不做改名**：
     * 改名要传 `source_name` 且会触发 409 冲突，本版不接。
     */
    fun saveSkill(conn: Connection, name: String, content: String): SaveSkillResultDto {
        val body = buildJsonObject {
            put("name", name)
            put("content", content)
        }
        return decode(
            request(conn, "PUT", "/api/skills/save", body),
            SaveSkillResultDto.serializer(),
        )
    }

    /**
     * 新建技能，返回服务端最终使用的名字。
     *
     * 重名回 409 且 `detail` 是对象，`ApiException.detail` 此时是被序列化过的字符串，
     * 调用方用 [skillConflictOf] 取建议名。
     */
    fun createSkill(conn: Connection, name: String, content: String): String {
        val body = buildJsonObject {
            put("name", name)
            put("content", content)
            put("enable", true)
        }
        val text = request(conn, "POST", "/api/skills", body)
        return runCatching {
            json.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive?.content
        }.getOrNull() ?: name
    }

    /**
     * 删除技能。
     *
     * 服务端自己先停用再删，**不需要客户端先调 [disableSkill]**（实测确认）。
     * 删除没有回收站，调用方要给二次确认。
     */
    fun deleteSkill(conn: Connection, name: String) {
        request(conn, "DELETE", "/api/skills/${encodeSegment(name)}")
    }

    /** `GET /api/skills/{name}/config`。返回 `{config:{...}}` 里的对象。 */
    fun skillConfig(conn: Connection, name: String): JsonObject =
        runCatching {
            json.parseToJsonElement(
                request(conn, "GET", "/api/skills/${encodeSegment(name)}/config"),
            ).jsonObject["config"]?.jsonObject
        }.getOrNull() ?: JsonObject(emptyMap())

    /** `PUT /api/skills/{name}/config`。整段替换该技能的配置对象。 */
    fun saveSkillConfig(conn: Connection, name: String, config: JsonObject) {
        request(
            conn,
            "PUT",
            "/api/skills/${encodeSegment(name)}/config",
            buildJsonObject { put("config", config) },
        )
    }

    fun clearSkillConfig(conn: Connection, name: String) {
        request(conn, "DELETE", "/api/skills/${encodeSegment(name)}/config")
    }

    /** `PUT /api/skills/{name}/preload`，body 是 `{preload}`。 */
    fun setSkillPreload(conn: Connection, name: String, preload: Boolean) {
        request(
            conn,
            "PUT",
            "/api/skills/${encodeSegment(name)}/preload",
            buildJsonObject { put("preload", preload) },
        )
    }

    /** `PUT /api/skills/{name}/tags`，body 是**裸字符串数组**。 */
    fun setSkillTags(conn: Connection, name: String, tags: List<String>) {
        val body = buildJsonArray { tags.forEach { add(it) } }
        requestRaw(conn, "PUT", "/api/skills/${encodeSegment(name)}/tags", body.toString())
    }

    /** `PUT /api/skills/{name}/channels`，body 同样是裸字符串数组。 */
    fun setSkillChannels(conn: Connection, name: String, channels: List<String>) {
        val body = buildJsonArray { channels.forEach { add(it) } }
        requestRaw(conn, "PUT", "/api/skills/${encodeSegment(name)}/channels", body.toString())
    }

    /** `GET /api/skills/{name}/files/{path}`。没有目录列举端点，只能按路径单取。 */
    fun skillFile(conn: Connection, name: String, path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        val fullPath = "/api/skills/${encodeSegment(name)}/files/$segments"
        val text = request(conn, "GET", fullPath)
        return runCatching {
            json.parseToJsonElement(text).jsonObject["content"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
    }

    fun skillWorkspaces(conn: Connection): List<SkillWorkspaceDto> = decode(
        request(conn, "GET", "/api/skills/workspaces"),
        kotlinx.serialization.builtins.ListSerializer(SkillWorkspaceDto.serializer()),
    )

    fun poolBuiltinSources(conn: Connection): List<BuiltinSourceDto> = decode(
        request(conn, "GET", "/api/skills/pool/builtin-sources"),
        kotlinx.serialization.builtins.ListSerializer(BuiltinSourceDto.serializer()),
    )

    fun poolBuiltinNotice(conn: Connection): BuiltinNoticeDto = decode(
        request(conn, "GET", "/api/skills/pool/builtin-notice"),
        BuiltinNoticeDto.serializer(),
    )

    fun refreshPoolSkills(conn: Connection): List<PoolSkillDto> = decode(
        request(conn, "POST", "/api/skills/pool/refresh"),
        kotlinx.serialization.builtins.ListSerializer(PoolSkillDto.serializer()),
    )

    /** 新建池技能。重名 409 的 detail 是 `{reason,suggested_name}`。 */
    fun createPoolSkill(
        conn: Connection,
        name: String,
        content: String,
        config: JsonObject?,
    ): String {
        val body = buildJsonObject {
            put("name", name)
            put("content", content)
            config?.let { put("config", it) }
        }
        val text = request(conn, "POST", "/api/skills/pool/create", body)
        return runCatching {
            json.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive?.content
        }.getOrNull() ?: name
    }

    /** 保存/另存池技能。[sourceName] 与 [name] 不同表示改名，冲突时带 overwrite。 */
    fun savePoolSkill(
        conn: Connection,
        name: String,
        content: String,
        sourceName: String?,
        config: JsonObject?,
        overwrite: Boolean,
    ): String {
        val body = buildJsonObject {
            put("name", name)
            put("content", content)
            sourceName?.let { put("source_name", it) }
            config?.let { put("config", it) }
            put("overwrite", overwrite)
        }
        val text = request(conn, "PUT", "/api/skills/pool/save", body)
        return runCatching {
            json.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive?.content
        }.getOrNull() ?: name
    }

    fun deletePoolSkill(conn: Connection, name: String) {
        request(conn, "DELETE", "/api/skills/pool/${encodeSegment(name)}")
    }

    /** 批量删除池技能，body 是裸字符串数组。 */
    fun batchDeletePoolSkills(conn: Connection, names: List<String>) {
        val body = buildJsonArray { names.forEach { add(it) } }
        requestRaw(conn, "POST", "/api/skills/pool/batch-delete", body.toString())
    }

    /** `PUT /api/skills/pool/{name}/tags`，body 是裸字符串数组。 */
    fun setPoolSkillTags(conn: Connection, name: String, tags: List<String>) {
        val body = buildJsonArray { tags.forEach { add(it) } }
        requestRaw(conn, "PUT", "/api/skills/pool/${encodeSegment(name)}/tags", body.toString())
    }

    /** `PUT /api/skills/pool/{name}/automation`，原子提交 auto_update 与 auto_sync。 */
    fun setPoolSkillAutomation(
        conn: Connection,
        name: String,
        autoUpdate: Boolean?,
        autoSyncEnabled: Boolean?,
        autoSyncTargets: List<String>?,
    ) {
        val body = buildJsonObject {
            autoUpdate?.let { put("auto_update", it) }
            if (autoSyncEnabled != null) {
                putJsonObject("auto_sync") {
                    put("enabled", autoSyncEnabled)
                    autoSyncTargets?.let { targets ->
                        putJsonArray("targets") { targets.forEach { add(it) } }
                    }
                }
            }
        }
        request(conn, "PUT", "/api/skills/pool/${encodeSegment(name)}/automation", body)
    }

    /** `POST /api/skills/pool/import-builtin`。冲突 409 时 detail 带 `conflicts`。 */
    fun importBuiltinSkills(
        conn: Connection,
        imports: List<Pair<String, String>>,
        overwrite: Boolean,
    ): String {
        val body = buildJsonObject {
            putJsonArray("imports") {
                imports.forEach { (skillName, language) ->
                    addJsonObject {
                        put("skill_name", skillName)
                        put("language", language)
                    }
                }
            }
            put("overwrite_conflicts", overwrite)
        }
        return request(conn, "POST", "/api/skills/pool/import-builtin", body)
    }

    /** `POST /api/skills/pool/{name}/update-builtin`。 */
    fun updateBuiltinSkill(conn: Connection, name: String, language: String) {
        request(
            conn,
            "POST",
            "/api/skills/pool/${encodeSegment(name)}/update-builtin",
            buildJsonObject { put("language", language) },
        )
    }

    /** `POST /api/skills/pool/downloads` 异步分发，返回任务句柄。 */
    fun startPoolDownload(
        conn: Connection,
        skillName: String,
        targets: List<String>,
        allWorkspaces: Boolean,
        overwrite: Boolean,
    ): PoolDownloadJobDto {
        val body = buildJsonObject {
            put("skill_name", skillName)
            putJsonArray("targets") {
                targets.forEach { addJsonObject { put("workspace_id", it) } }
            }
            put("all_workspaces", allWorkspaces)
            put("overwrite", overwrite)
            put("preview_only", false)
        }
        return decode(
            request(conn, "POST", "/api/skills/pool/downloads", body),
            PoolDownloadJobDto.serializer(),
        )
    }

    fun poolDownloadStatus(conn: Connection, jobId: String): PoolDownloadJobDto = decode(
        request(conn, "GET", "/api/skills/pool/downloads/${encodeSegment(jobId)}"),
        PoolDownloadJobDto.serializer(),
    )

    fun cancelPoolDownload(conn: Connection, jobId: String): PoolDownloadJobDto = decode(
        request(conn, "DELETE", "/api/skills/pool/downloads/${encodeSegment(jobId)}"),
        PoolDownloadJobDto.serializer(),
    )

    /** 冲突预检：走同步端点 `preview_only=true`。409 的 detail 与广播冲突同形。 */
    fun previewPoolDownload(
        conn: Connection,
        skillName: String,
        targets: List<String>,
        allWorkspaces: Boolean,
    ): String {
        val body = buildJsonObject {
            put("skill_name", skillName)
            putJsonArray("targets") {
                targets.forEach { addJsonObject { put("workspace_id", it) } }
            }
            put("all_workspaces", allWorkspaces)
            put("overwrite", false)
            put("preview_only", true)
        }
        return try {
            request(conn, "POST", "/api/skills/pool/download", body)
        } catch (e: ApiException) {
            if (e.code == 409) e.detail.orEmpty() else throw e
        }
    }

    /** `POST /api/skills/pool/upload`：把一个工作区技能复制进技能池。 */
    fun uploadPoolSkill(
        conn: Connection,
        workspaceId: String,
        skillName: String,
        overwrite: Boolean,
    ): String {
        val body = buildJsonObject {
            put("workspace_id", workspaceId)
            put("skill_name", skillName)
            put("overwrite", overwrite)
        }
        return request(conn, "POST", "/api/skills/pool/upload", body)
    }

    /** 技能池列表。池内技能没有 `enabled`，本版只读。 */
    fun poolSkills(conn: Connection): List<PoolSkillDto> =
        decode(
            request(conn, "GET", "/api/skills/pool"),
            kotlinx.serialization.builtins.ListSerializer(PoolSkillDto.serializer()),
        )

    fun poolSkill(conn: Connection, name: String): PoolSkillDetailDto =
        decode(
            request(conn, "GET", "/api/skills/pool/${encodeSegment(name)}"),
            PoolSkillDetailDto.serializer(),
        )

    /**
     * 从 409 的 `detail` 中取出重名建议。
     *
     * `extractDetail` 已把对象序列化成字符串，这里解析回来；解析失败返回 null，
     * 让调用方按普通 [ApiException] 处理。
     */
    fun skillConflictOf(e: ApiException): SkillConflictDto? {
        val raw = e.detail ?: return null
        return runCatching {
            json.decodeFromString(SkillConflictDto.serializer(), raw)
        }.getOrNull()?.takeIf { it.suggestedName.isNotBlank() }
    }

    /**
     * 把 409/404 的结构化 `detail` 解析成保守模型。
     *
     * 服务端不同端点在同一状态码下回不同形状；解析失败返回 null，调用方展示
     * 服务端原文，不把「没读懂」当成「没有冲突」。
     */
    fun apiErrorDetailOf(e: ApiException): ApiErrorDetailDto? {
        val raw = e.detail ?: return null
        if (!raw.trimStart().startsWith("{")) return null
        return runCatching {
            json.decodeFromString(ApiErrorDetailDto.serializer(), raw)
        }.getOrNull()
    }

    // ---------- MCP ----------

    fun mcpClients(conn: Connection): List<McpClientDto> =
        decode(
            request(conn, "GET", "/api/mcp"),
            kotlinx.serialization.builtins.ListSerializer(McpClientDto.serializer()),
        )

    /** 切换启用状态。**端点没有请求体**，服务端自行取反，结果以返回值为准。 */
    fun toggleMcpClient(conn: Connection, key: String): McpClientDto =
        decode(
            request(conn, "PATCH", "/api/mcp/toggle/$key"),
            McpClientDto.serializer(),
        )

    /**
     * 某个 MCP 客户端的工具列表。
     *
     * **会实时连 MCP 服务端**，连不上回 502；客户端本身被禁用时服务端直接回 `[]`，
     * 所以空列表要结合 `McpClientDto.enabled` 才能判断是哪种情况。
     */
    fun mcpTools(conn: Connection, key: String): List<McpToolDto> =
        decode(
            request(conn, "GET", "/api/mcp/tools/$key"),
            kotlinx.serialization.builtins.ListSerializer(McpToolDto.serializer()),
        )

    /** 保存工具白名单。`tools` 传 `null` 表示取消白名单、全部启用。 */
    fun saveMcpTools(conn: Connection, key: String, tools: List<String>?): List<McpToolDto> {
        val body = buildJsonObject {
            put("tools", tools?.let { list -> JsonArray(list.map { JsonPrimitive(it) }) } ?: JsonNull)
        }
        return decode(
            request(conn, "PUT", "/api/mcp/tools/$key", body),
            kotlinx.serialization.builtins.ListSerializer(McpToolDto.serializer()),
        )
    }

    /** 访问策略。`GET` 不连 MCP 服务端，可以随时读。 */
    fun mcpPolicy(conn: Connection, key: String): McpPolicyDto =
        decode(
            request(conn, "GET", "/api/mcp/policy/$key"),
            McpPolicyDto.serializer(),
        )

    /**
     * 整份回写访问策略。
     *
     * 服务端按 payload 重建托管规则，因此 `client_overrides` 与 `tool_overrides`
     * 必须原样带回，否则会被清掉。序列化用 `encodeDefaults = true`，
     * `subject_value` 之类的空串也会写进去——服务端要求这些键存在。
     */
    fun saveMcpPolicy(conn: Connection, key: String, policy: McpPolicyDto): McpPolicyDto =
        decode(
            requestRaw(conn, "PUT", "/api/mcp/policy/$key", json.encodeToString(policy)),
            McpPolicyDto.serializer(),
        )

    /** 单个 MCP 客户端详情。key 走 `{client_key:path}`，允许包含 `/`，不能再编码一层。 */
    fun mcpClient(conn: Connection, key: String): McpClientDto =
        decode(
            request(conn, "GET", "/api/mcp/$key"),
            McpClientDto.serializer(),
        )

    /** 新建 MCP 客户端。端点的 body 是双层结构 `{client_key, client}`。 */
    fun createMcpClient(conn: Connection, key: String, client: McpClientWriteDto): McpClientDto {
        val body = buildJsonObject {
            put("client_key", key)
            put(
                "client",
                json.parseToJsonElement(
                    json.encodeToString(McpClientWriteDto.serializer(), client),
                ),
            )
        }
        return decode(
            request(conn, "POST", "/api/mcp", body),
            McpClientDto.serializer(),
        )
    }

    /** 部分更新；未写的字段由服务端保持原值。 */
    fun updateMcpClient(conn: Connection, key: String, updates: McpClientWriteDto): McpClientDto =
        decode(
            requestRaw(
                conn,
                "PUT",
                "/api/mcp/$key",
                json.encodeToString(McpClientWriteDto.serializer(), updates),
            ),
            McpClientDto.serializer(),
        )

    /** 删除客户端，同时删除它的凭据与 OAuth 令牌。 */
    fun deleteMcpClient(conn: Connection, key: String) {
        request(conn, "DELETE", "/api/mcp/$key")
    }

    /** 启动 MCP OAuth 2.1 PKCE 流程；`url` 必须是客户端的 MCP 服务端地址。 */
    fun mcpOAuthStart(
        conn: Connection,
        key: String,
        requestDto: McpOAuthStartDto,
    ): McpOAuthStartResultDto {
        val body = buildJsonObject {
            put("url", requestDto.url)
            if (requestDto.scope.isNotBlank()) put("scope", requestDto.scope)
            if (requestDto.clientId.isNotBlank()) put("client_id", requestDto.clientId)
            if (requestDto.authEndpoint.isNotBlank()) put("auth_endpoint", requestDto.authEndpoint)
            if (requestDto.tokenEndpoint.isNotBlank()) put("token_endpoint", requestDto.tokenEndpoint)
        }
        return decode(
            request(conn, "POST", "/api/mcp/oauth/start/$key", body),
            McpOAuthStartResultDto.serializer(),
        )
    }

    fun mcpOAuthStatus(conn: Connection, key: String): McpOAuthStatusDto =
        decode(
            request(conn, "GET", "/api/mcp/oauth/status/$key"),
            McpOAuthStatusDto.serializer(),
        )

    /** 撤销 OAuth 授权并清掉本地令牌。 */
    fun mcpOAuthRevoke(conn: Connection, key: String) {
        request(conn, "DELETE", "/api/mcp/oauth/$key")
    }

    // ---------- 市场与插件目录 ----------

    fun marketProviders(conn: Connection): List<MarketProviderDto> =
        decode(
            request(conn, "GET", "/api/market/providers"),
            kotlinx.serialization.builtins.ListSerializer(MarketProviderDto.serializer()),
        )

    fun marketCategories(conn: Connection, lang: String = "zh"): List<MarketCategoryDto> =
        decode(
            request(conn, "GET", "/api/market/categories?lang=$lang"),
            kotlinx.serialization.builtins.ListSerializer(MarketCategoryDto.serializer()),
        )

    /**
     * 搜索技能市场。
     *
     * `providerPages` 只能包含 `available == true` 的来源：服务端对未注册的 key 直接回 400。
     * `limit` 服务端上限 50。实测三个来源一起查约 20 秒，调用方要有「搜索中」状态。
     */
    fun marketSearch(
        conn: Connection,
        query: String,
        providerPages: Map<String, Int>,
        limit: Int,
        lang: String = "zh",
        category: String? = null,
    ): MarketSearchResponseDto {
        val body = buildJsonObject {
            put("query", query)
            put(
                "provider_pages",
                JsonObject(providerPages.mapValues { (_, page) -> JsonPrimitive(page) }),
            )
            put("limit", limit)
            put("lang", lang)
            category?.takeIf { it.isNotBlank() }?.let { put("category", it) }
        }
        return decode(
            request(conn, "POST", "/api/market/search", body),
            MarketSearchResponseDto.serializer(),
        )
    }

    /**
     * 官方插件目录。
     *
     * **CDN 拉不到时服务端仍回 200**，此时 `plugins` 为空且 `error` 非空。
     * 调用方必须先看 `error`，否则会把「取不到」显示成「没有插件」。
     */
    fun pluginCatalog(conn: Connection): PluginCatalogDto =
        decode(
            request(conn, "GET", "/api/plugins/catalog"),
            PluginCatalogDto.serializer(),
        )

    // ---------- 插件管理 ----------

    /** 已安装插件列表。2.3.0 起元素多 `status` 与 `diagnostics`。 */
    fun plugins(conn: Connection): List<PluginListItemDto> =
        decode(
            request(conn, "GET", "/api/plugins"),
            kotlinx.serialization.builtins.ListSerializer(PluginListItemDto.serializer()),
        )

    /** 单个插件详情；2.3.0.post1 才有独立详情路由，旧版本回 404。 */
    fun pluginDetail(conn: Connection, pluginId: String): PluginListItemDto =
        decode(
            request(conn, "GET", "/api/plugins/$pluginId"),
            PluginListItemDto.serializer(),
        )

    /**
     * 整段写入插件配置。
     *
     * **服务端没有读取端点**，这里提交的 `config` 会替换 `config.plugins.<id>`，
     * 因此界面必须先给出「会整段覆盖」的确认。`confirmLegacy` 用于确认
     * legacy 卸载钩子，`interruptTasks` 用于允许中断运行中的任务。
     */
    fun updatePluginConfig(
        conn: Connection,
        pluginId: String,
        config: JsonObject,
        confirmLegacy: Boolean = false,
        interruptTasks: Boolean = false,
    ) {
        val body = buildJsonObject {
            put("config", config)
            put("confirm_legacy", confirmLegacy)
            put("interrupt_tasks", interruptTasks)
        }
        request(conn, "PUT", "/api/plugins/$pluginId/config", body)
    }

    /** 启停插件。停用会卸载当前进程里的实例，但不删除文件。 */
    fun setPluginEnabled(
        conn: Connection,
        pluginId: String,
        enabled: Boolean,
        interruptTasks: Boolean = false,
    ) {
        val body = buildJsonObject {
            put("enabled", enabled)
            put("interrupt_tasks", interruptTasks)
        }
        request(conn, "POST", "/api/plugins/$pluginId/enabled", body)
    }

    /** 仅从当前进程卸载，不删除文件。 */
    fun unloadPlugin(conn: Connection, pluginId: String, interruptTasks: Boolean = false) {
        val suffix = if (interruptTasks) "?interrupt_tasks=true" else ""
        request(conn, "POST", "/api/plugins/$pluginId/unload$suffix")
    }

    /** 修复插件依赖；需要依赖升级时服务端回 409，detail 里带 operation。 */
    fun repairPluginDependencies(conn: Connection, pluginId: String) {
        request(conn, "POST", "/api/plugins/$pluginId/repair-dependencies")
    }

    /** 卸载插件并删除文件。 */
    fun deletePlugin(conn: Connection, pluginId: String, interruptTasks: Boolean = false) {
        val suffix = if (interruptTasks) "?interrupt_tasks=true" else ""
        request(conn, "DELETE", "/api/plugins/$pluginId$suffix")
    }

    // ---------- 插件依赖升级 ----------

    /** 依赖升级操作状态。形状由服务端决定，界面按已知键读取，未知键忽略。 */
    fun dependencyUpgrade(conn: Connection, operationId: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/plugins/dependency-upgrades/$operationId"),
        ).jsonObject

    fun applyDependencyUpgrade(conn: Connection, operationId: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "POST", "/api/plugins/dependency-upgrades/$operationId/apply"),
        ).jsonObject

    fun cancelDependencyUpgrade(conn: Connection, operationId: String): JsonObject =
        json.parseToJsonElement(
            request(conn, "DELETE", "/api/plugins/dependency-upgrades/$operationId"),
        ).jsonObject

    /** 在线插件市场。服务端代理上游，失败时回 502。 */
    fun pluginMarketSearch(
        conn: Connection,
        search: String,
        pageNumber: Int = 1,
        pageSize: Int = 20,
    ): PluginMarketResponseDto {
        val url = workspaceUrl(
            conn,
            "/api/plugins/market/search",
            mapOf(
                "page_number" to pageNumber.toString(),
                "page_size" to pageSize.toString(),
                "search" to search.takeIf { it.isNotBlank() },
            ),
        )
        val builder = baseBuilder(conn, url).get()
        return decode(execute(builder.build()), PluginMarketResponseDto.serializer())
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

    /**
     * `PUT /api/workspace/file-content`。
     *
     * 请求体是 `{"content": "..."}`，并发控制靠 `If-Match`。服务端做的是**字符串全等
     * 比较**，[etag] 必须原样回传 `GET` 得到的值，包括 `W/` 前缀与引号。
     * [etag] 为 null 时不发这个头，服务端会跳过并发校验直接覆盖，用于「强制覆盖」。
     */
    fun workspaceSaveFileContent(
        conn: Connection,
        root: String,
        path: String,
        content: String,
        etag: String?,
    ): WorkspaceSaveResultDto {
        val body = buildJsonObject { put("content", content) }.toString().toRequestBody(JSON_MEDIA)
        val builder = baseBuilder(
            conn,
            workspaceUrl(
                conn,
                "/api/workspace/file-content",
                mapOf("root" to root, "path" to path),
            ),
        ).put(body)
        if (etag != null) builder.header("If-Match", etag)
        return decode(execute(builder.build()), WorkspaceSaveResultDto.serializer())
    }

    /**
     * `POST /api/workspace/file-upload`，单个请求只带一个文件。
     *
     * 表单字段名是 `files`（服务端声明为 `list[UploadFile]`，但只放一个也接受）。
     * [fileName] 是落到服务端的文件名，必须与本地暂存文件的名字解耦：暂存用的是
     * `cacheDir` 里的随机名，直接拿 [File.getName] 会把 `upload-123.tmp` 传上去。
     * [conflict] 为 null 时不发策略参数，由服务端判定同名冲突并返回
     * 409，调用方据此抛出 [UploadConflictException]。
     */
    fun workspaceFileUpload(
        conn: Connection,
        root: String,
        path: String,
        file: File,
        fileName: String,
        conflict: UploadConflictPolicy? = null,
    ): WorkspaceUploadResultDto {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", fileName, file.asRequestBody(OCTET_MEDIA))
            .build()
        val url = workspaceUrl(
            conn,
            "/api/workspace/file-upload",
            mapOf(
                "root" to root,
                "path" to path,
                "conflict" to conflict?.param,
            ),
        )
        val request = baseBuilder(conn, url).post(body).build()
        return try {
            decode(execute(request), WorkspaceUploadResultDto.serializer())
        } catch (e: ApiException) {
            if (e.code == 409) uploadConflictOf(e.detail)?.let { throw it }
            throw e
        }
    }

    /** `GET /api/settings/upload-limit`。返回 null 表示服务端不限制。 */
    fun workspaceUploadLimit(conn: Connection): Long? = decode(
        request(conn, "GET", "/api/settings/upload-limit"),
        UploadLimitDto.serializer(),
    ).uploadMaxSizeMb

    // ---------- 语音转写 ----------

    /**
     * `POST /api/workspace/transcribe`，表单字段名是 `file`（**不是** `files`）。
     *
     * 服务端按文件名的后缀判定格式，没有后缀时回落成 `.webm`，所以 [fileName]
     * 必须带 `.wav`。实测另有三条：不接受任何 query 参数；`X-Agent-Id` 不影响结果
     * （同一个 handler，不按智能体区分）；`disabled` 时连扩展名校验都不会走到。
     */
    fun transcribeAudio(
        conn: Connection,
        file: File,
        fileName: String,
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, file.asRequestBody(WAV_MEDIA))
            .build()
        val text = execute(baseBuilder(conn, connUrl(conn, TRANSCRIBE_PATH)).post(body).build())
        return decode(text, TranscribeResultDto.serializer()).text
    }

    /** `GET /api/workspace/transcription-provider-type`。取值见 [TranscriptionProviderTypeDto]。 */
    fun transcriptionProviderType(conn: Connection): TranscriptionProviderTypeDto = decode(
        request(conn, "GET", "/api/workspace/transcription-provider-type"),
        TranscriptionProviderTypeDto.serializer(),
    )

    /** `GET /api/workspace/transcription-providers`。判定「真的能转写」还要看 `configured_provider_id`。 */
    fun transcriptionProviders(conn: Connection): TranscriptionProvidersDto = decode(
        request(conn, "GET", "/api/workspace/transcription-providers"),
        TranscriptionProvidersDto.serializer(),
    )

    /** `GET /api/workspace/local-whisper-status`。`local_whisper` 档位下用它判断依赖是否装齐。 */
    fun localWhisperStatus(conn: Connection): LocalWhisperStatusDto = decode(
        request(conn, "GET", "/api/workspace/local-whisper-status"),
        LocalWhisperStatusDto.serializer(),
    )

    // ---------- 备份 ----------

    /**
     * `GET /api/backups`。
     *
     * 响应是**裸数组**，不是 `{items: [...]}`。列表与当前选中的智能体无关，
     * 但仍走 [baseBuilder]，因此会带上 `X-Agent-Id`。
     */
    fun backups(conn: Connection): List<BackupMetaDto> = decode(
        request(conn, "GET", "/api/backups"),
        ListSerializer(BackupMetaDto.serializer()),
    )

    /** `GET /api/backups/{id}`。 */
    fun backupDetail(conn: Connection, id: String): BackupDetailDto = decode(
        request(conn, "GET", "/api/backups/${encodeSegment(id)}"),
        BackupDetailDto.serializer(),
    )

    /** `POST /api/backups/jobs`。已有任务或恢复进行中时服务端返回 409。 */
    fun startBackupJob(conn: Connection, req: CreateBackupRequestDto): BackupJobSnapshotDto = decode(
        execute(
            baseBuilder(conn, connUrl(conn, "/api/backups/jobs"))
                .post(json.encodeToString(CreateBackupRequestDto.serializer(), req).toRequestBody(JSON_MEDIA))
                .build(),
        ),
        BackupJobSnapshotDto.serializer(),
    )

    /** `GET /api/backups/jobs/active`。空闲时服务端返回 JSON `null`。 */
    fun activeBackupJob(conn: Connection): BackupJobSnapshotDto? {
        val text = request(conn, "GET", "/api/backups/jobs/active")
        if (text.isBlank() || text.trim() == "null") return null
        return decode(text, BackupJobSnapshotDto.serializer())
    }

    /**
     * `GET /api/backups/jobs/{job_id}`。
     *
     * 任务只存在服务端内存里，服务端重启后这里会 404；调用方据此清空任务卡。
     */
    fun backupJob(conn: Connection, jobId: String): BackupJobSnapshotDto = decode(
        request(conn, "GET", "/api/backups/jobs/${encodeSegment(jobId)}"),
        BackupJobSnapshotDto.serializer(),
    )

    /** `POST /api/backups/jobs/{job_id}/cancel`。 */
    fun cancelBackupJob(conn: Connection, jobId: String): BackupJobSnapshotDto = decode(
        request(conn, "POST", "/api/backups/jobs/${encodeSegment(jobId)}/cancel", buildJsonObject { }),
        BackupJobSnapshotDto.serializer(),
    )

    /** SSE 请求：`GET /api/backups/jobs/{job_id}/events`。 */
    fun backupJobEventsRequest(conn: Connection, jobId: String): Request =
        baseBuilder(conn, connUrl(conn, "/api/backups/jobs/${encodeSegment(jobId)}/events"))
            .get()
            .header("Accept", "text/event-stream")
            .build()

    /** `GET /api/backups/{id}/export`，交给 DownloadClient 流式写 SAF。 */
    fun exportBackupRequest(conn: Connection, id: String): Request =
        baseBuilder(conn, connUrl(conn, "/api/backups/${encodeSegment(id)}/export"))
            .get()
            .build()

    /**
     * `POST /api/backups/import`（multipart）。
     *
     * 首次调用带 `file`；确认覆盖时 [body] 传 null、只带 [pendingToken]。
     * 重名 409 抛 [BackupImportConflictException]，签名不可信 400 抛
     * [BackupTrustException]。
     */
    fun importBackup(
        conn: Connection,
        body: RequestBody?,
        fileName: String = "backup.zip",
        pendingToken: String? = null,
        trustMode: String? = null,
    ): BackupMetaDto {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (body != null) multipart.addFormDataPart("file", fileName, body)
        pendingToken?.takeIf { it.isNotBlank() }?.let { multipart.addFormDataPart("pending_token", it) }
        trustMode?.takeIf { it.isNotBlank() }?.let { multipart.addFormDataPart("trust_mode", it) }
        val request = baseBuilder(conn, connUrl(conn, "/api/backups/import"))
            .post(multipart.build())
            .build()
        return decode(executeImport(request), BackupMetaDto.serializer())
    }

    /** `POST /api/backups/delete`。请求体是对象，响应列出成功与失败项。 */
    fun deleteBackups(conn: Connection, ids: List<String>): DeleteBackupsResultDto {
        val body = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(it) } }
        }
        return decode(
            request(conn, "POST", "/api/backups/delete", body),
            DeleteBackupsResultDto.serializer(),
        )
    }

    /**
     * `POST /api/backups/{id}/restore`。
     *
     * 签名不可信时服务端返回 400，`detail` 是对象；这里转成
     * [BackupTrustException]，界面据此提供「信任并恢复」。
     */
    fun restoreBackup(
        conn: Connection,
        id: String,
        req: RestoreBackupRequestDto,
    ): RestoreBackupResponseDto {
        val request = baseBuilder(conn, connUrl(conn, "/api/backups/${encodeSegment(id)}/restore"))
            .post(json.encodeToString(RestoreBackupRequestDto.serializer(), req).toRequestBody(JSON_MEDIA))
            .build()
        return decode(executeTrustAware(request), RestoreBackupResponseDto.serializer())
    }

    // ---------- 检查点 ----------

    /** `GET /api/workspace/checkpoints/status`。工作区不是 git 仓库时返回 400。 */
    fun checkpointStatus(conn: Connection): CheckpointStatusDto = decode(
        request(conn, "GET", "/api/workspace/checkpoints/status"),
        CheckpointStatusDto.serializer(),
    )

    /** `PATCH /api/workspace/checkpoints/auto`，返回服务端归一化后的开关值。 */
    fun setCheckpointAuto(conn: Connection, enabled: Boolean): Boolean {
        val body = buildJsonObject { put("enabled", enabled) }
        return decode(
            request(conn, "PATCH", "/api/workspace/checkpoints/auto", body),
            CheckpointStatusDto.serializer(),
        ).autoEnabled
    }

    /** `GET /api/workspace/checkpoints/graph?limit=`。`limit` 取值 1–1000。 */
    fun checkpointGraph(conn: Connection, limit: Int = 500): CheckpointGraphDto = decode(
        execute(
            baseBuilder(
                conn,
                workspaceUrl(
                    conn,
                    "/api/workspace/checkpoints/graph",
                    mapOf("limit" to limit.coerceIn(1, 1000).toString()),
                ),
            ).get().build(),
        ),
        CheckpointGraphDto.serializer(),
    )

    /** `POST /api/workspace/checkpoints/snapshot`。 */
    fun createCheckpointSnapshot(
        conn: Connection,
        req: SnapshotRequestDto,
    ): SnapshotResultDto = decode(
        execute(
            baseBuilder(conn, connUrl(conn, "/api/workspace/checkpoints/snapshot"))
                .post(json.encodeToString(SnapshotRequestDto.serializer(), req).toRequestBody(JSON_MEDIA))
                .build(),
        ),
        SnapshotResultDto.serializer(),
    )

    /** `POST /api/workspace/checkpoints/restore/preview`。 */
    fun previewCheckpointRestore(
        conn: Connection,
        req: RestoreCheckpointRequestDto,
    ): RestoreResultDto = checkpointRestore(conn, req, preview = true)

    /** `POST /api/workspace/checkpoints/restore`。 */
    fun applyCheckpointRestore(
        conn: Connection,
        req: RestoreCheckpointRequestDto,
    ): RestoreResultDto = checkpointRestore(conn, req, preview = false)

    /** `GET /api/workspace/checkpoints/gc/settings`。 */
    fun checkpointGcSettings(conn: Connection): GcSettingsDto = decode(
        request(conn, "GET", "/api/workspace/checkpoints/gc/settings"),
        GcSettingsDto.serializer(),
    )

    /** `PATCH /api/workspace/checkpoints/gc/settings`。三项都必须带。 */
    fun updateCheckpointGcSettings(conn: Connection, settings: GcSettingsDto): GcSettingsDto {
        val body = buildJsonObject {
            put("gc_keep_count", settings.gcKeepCount)
            put("gc_keep_days", settings.gcKeepDays)
            put("pre_restore_retention_days", settings.preRestoreRetentionDays)
        }
        return decode(
            request(conn, "PATCH", "/api/workspace/checkpoints/gc/settings", body),
            GcSettingsDto.serializer(),
        )
    }

    /** `POST /api/workspace/checkpoints/gc/preview`。 */
    fun previewCheckpointGc(conn: Connection, req: GcRequestDto): GcResultDto = checkpointGc(conn, req, true)

    /** `POST /api/workspace/checkpoints/gc`。 */
    fun applyCheckpointGc(conn: Connection, req: GcRequestDto): GcResultDto = checkpointGc(conn, req, false)

    /** `DELETE /api/workspace/checkpoints`，删除全部会话的检查点。 */
    fun resetCheckpoints(conn: Connection): ResetCheckpointsDto = decode(
        request(conn, "DELETE", "/api/workspace/checkpoints"),
        ResetCheckpointsDto.serializer(),
    )

    // ---------- 运行配置、安全与服务端设置 ----------

    /**
     * `GET /api/workspace/running-config`。
     *
     * 返回原始对象而不是 22 字段 DTO：PUT 要求整份回写，而客户端只改其中几项；
     * 用 DTO 往返会把客户端未识别的嵌套字段一起抹掉。GET 里记忆后端的密钥是
     * `***`，原样回写时服务端会还原旧值。
     */
    fun runningConfig(conn: Connection): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/workspace/running-config"),
        ).jsonObject

    /** `PUT /api/workspace/running-config`，body 必须是完整对象。 */
    fun saveRunningConfig(conn: Connection, config: JsonObject): JsonObject =
        json.parseToJsonElement(
            request(conn, "PUT", "/api/workspace/running-config", config),
        ).jsonObject

    /** `GET /api/config/security/tool-guard`。规则编辑留 Web，客户端只改 `enabled`。 */
    fun toolGuard(conn: Connection): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/config/security/tool-guard"),
        ).jsonObject

    fun saveToolGuard(conn: Connection, config: JsonObject): JsonObject =
        json.parseToJsonElement(
            request(conn, "PUT", "/api/config/security/tool-guard", config),
        ).jsonObject

    /** `GET /api/config/security/tool-guard/builtin-rules`。 */
    fun toolGuardBuiltinRules(conn: Connection): List<ToolGuardRuleDto> = decode(
        request(conn, "GET", "/api/config/security/tool-guard/builtin-rules"),
        kotlinx.serialization.builtins.ListSerializer(ToolGuardRuleDto.serializer()),
    )

    /** `GET /api/config/security/file-guard`。 */
    fun fileGuard(conn: Connection): FileGuardDto = decode(
        request(conn, "GET", "/api/config/security/file-guard"),
        FileGuardDto.serializer(),
    )

    /** `PUT /api/config/security/file-guard`。该端点允许只提交 `enabled`。 */
    fun setFileGuardEnabled(conn: Connection, enabled: Boolean): FileGuardDto {
        val body = buildJsonObject { put("enabled", enabled) }
        return decode(
            request(conn, "PUT", "/api/config/security/file-guard", body),
            FileGuardDto.serializer(),
        )
    }

    /** `GET /api/config/security/sandbox`。 */
    fun sandboxStatus(conn: Connection): SandboxStatusDto = decode(
        request(conn, "GET", "/api/config/security/sandbox"),
        SandboxStatusDto.serializer(),
    )

    /** `PUT /api/config/security/sandbox`。 */
    fun setSandboxEnabled(conn: Connection, enabled: Boolean): SandboxStatusDto {
        val body = buildJsonObject { put("enabled", enabled) }
        return decode(
            request(conn, "PUT", "/api/config/security/sandbox", body),
            SandboxStatusDto.serializer(),
        )
    }

    /** `GET /api/config/security/skill-scanner`。客户端只改 `mode`。 */
    fun skillScanner(conn: Connection): JsonObject =
        json.parseToJsonElement(
            request(conn, "GET", "/api/config/security/skill-scanner"),
        ).jsonObject

    fun saveSkillScanner(conn: Connection, config: JsonObject): JsonObject =
        json.parseToJsonElement(
            request(conn, "PUT", "/api/config/security/skill-scanner", config),
        ).jsonObject

    /** `GET /api/config/security/allow-no-auth-hosts`。 */
    fun allowNoAuthHosts(conn: Connection): AllowNoAuthHostsDto = decode(
        request(conn, "GET", "/api/config/security/allow-no-auth-hosts"),
        AllowNoAuthHostsDto.serializer(),
    )

    /** `PUT …/allow-no-auth-hosts`，body 是整个名单，服务端负责规范化与去重。 */
    fun setAllowNoAuthHosts(conn: Connection, hosts: List<String>): AllowNoAuthHostsDto {
        val body = buildJsonObject {
            put("hosts", buildJsonArray { hosts.forEach { add(it) } })
        }
        return decode(
            request(conn, "PUT", "/api/config/security/allow-no-auth-hosts", body),
            AllowNoAuthHostsDto.serializer(),
        )
    }

    /** `GET /api/settings/language`。这是服务端 Web 控制台的语言，不是客户端语言。 */
    fun serverLanguage(conn: Connection): String = decode(
        request(conn, "GET", "/api/settings/language"),
        ServerLanguageDto.serializer(),
    ).language

    /** `PUT /api/settings/language`。非法值服务端回 400。 */
    fun setServerLanguage(conn: Connection, language: String): String {
        val body = buildJsonObject { put("language", language) }
        return decode(
            request(conn, "PUT", "/api/settings/language", body),
            ServerLanguageDto.serializer(),
        ).language
    }

    // ---------- 终端 ----------

    /** `GET /api/terminals/status`。`enabled:false` 时 `reason` 说明缺少的依赖。 */
    fun terminalStatus(conn: Connection): TerminalStatusDto = decode(
        request(conn, "GET", "/api/terminals/status"),
        TerminalStatusDto.serializer(),
    )

    /** `GET /api/terminals/{group}`。 */
    fun terminals(conn: Connection, group: String): List<TerminalDto> = decode(
        request(conn, "GET", "/api/terminals/$group"),
        ListSerializer(TerminalDto.serializer()),
    )

    /** `POST /api/terminals/{group}`。服务端不接受 body。 */
    fun createTerminal(conn: Connection, group: String): TerminalDto = decode(
        request(conn, "POST", "/api/terminals/$group"),
        TerminalDto.serializer(),
    )

    /**
     * `GET /api/terminals/{group}/{id}/output?after=`。
     *
     * 服务端最多阻塞 20 秒，因此走 [terminalClient] 的 30 秒读超时，而不是默认客户端的
     * 10 分钟。轮询成功不写网络日志：一次终端会话会产生大量长轮询，逐条记录只会淹没日志。
     */
    fun terminalOutput(
        conn: Connection,
        group: String,
        terminalId: String,
        after: Int,
    ): TerminalOutputDto {
        val url = connUrl(conn, "/api/terminals/$group/$terminalId/output?after=$after")
        val request = baseBuilder(conn, url).get().build()
        return terminalClientShared.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                logFailure(request, response.code, text)
                throw failureOf(
                    response.code,
                    text,
                    response.header("WWW-Authenticate"),
                    tokenSentOf(request),
                )
            }
            decode(text, TerminalOutputDto.serializer())
        }
    }

    /** `POST /api/terminals/{group}/{id}/input`。单帧上限 16384 字符。 */
    fun terminalInput(conn: Connection, group: String, terminalId: String, data: String) {
        request(
            conn,
            "POST",
            "/api/terminals/$group/$terminalId/input",
            buildJsonObject { put("data", data) },
        )
    }

    /** `POST /api/terminals/{group}/{id}/resize`。 */
    fun terminalResize(
        conn: Connection,
        group: String,
        terminalId: String,
        rows: Int,
        cols: Int,
    ) {
        request(
            conn,
            "POST",
            "/api/terminals/$group/$terminalId/resize",
            buildJsonObject {
                put("rows", rows)
                put("cols", cols)
            },
        )
    }

    /** `PATCH /api/terminals/{group}/{id}`。 */
    fun renameTerminal(
        conn: Connection,
        group: String,
        terminalId: String,
        title: String,
    ): TerminalDto = decode(
        request(
            conn,
            "PATCH",
            "/api/terminals/$group/$terminalId",
            buildJsonObject { put("title", title) },
        ),
        TerminalDto.serializer(),
    )

    /** `DELETE /api/terminals/{group}/{id}`。 */
    fun closeTerminal(conn: Connection, group: String, terminalId: String) {
        request(conn, "DELETE", "/api/terminals/$group/$terminalId")
    }

    // ---------- Git ----------

    /** `GET /api/workspace/git/status`。注意该端点可能在首次调用时初始化仓库。 */
    fun gitStatus(conn: Connection): GitStatusDto = decode(
        request(conn, "GET", "/api/workspace/git/status"),
        GitStatusDto.serializer(),
    )

    /** `GET /api/workspace/git/diff`。未跟踪文件由服务端用 `--no-index` 现算。 */
    fun gitDiff(
        conn: Connection,
        path: String? = null,
        staged: Boolean = false,
        untracked: Boolean = false,
    ): GitDiffDto = decode(
        getUrl(
            conn,
            workspaceUrl(
                conn,
                "/api/workspace/git/diff",
                mapOf(
                    "path" to path,
                    "staged" to if (staged) "true" else null,
                    "untracked" to if (untracked) "true" else null,
                ),
            ),
        ),
        GitDiffDto.serializer(),
    )

    /** `POST /api/workspace/git/stage`。空列表等于全部。 */
    fun gitStage(conn: Connection, paths: List<String>) {
        val body = buildJsonObject {
            putJsonArray("paths") { paths.forEach { add(it) } }
        }
        request(conn, "POST", "/api/workspace/git/stage", body)
    }

    /** `POST /api/workspace/git/unstage`。空列表等于全部。 */
    fun gitUnstage(conn: Connection, paths: List<String>) {
        val body = buildJsonObject {
            putJsonArray("paths") { paths.forEach { add(it) } }
        }
        request(conn, "POST", "/api/workspace/git/unstage", body)
    }

    /** `POST /api/workspace/git/commit`。空白消息服务端回 400。 */
    fun gitCommit(conn: Connection, message: String) {
        request(
            conn,
            "POST",
            "/api/workspace/git/commit",
            buildJsonObject { put("message", message) },
        )
    }

    /** `GET /api/workspace/git/log?limit=`。 */
    fun gitLog(conn: Connection, limit: Int = 20): List<GitLogEntryDto> = decode(
        getUrl(
            conn,
            workspaceUrl(
                conn,
                "/api/workspace/git/log",
                mapOf("limit" to limit.toString()),
            ),
        ),
        ListSerializer(GitLogEntryDto.serializer()),
    )

    /** `GET /api/workspace/git/branches`。只读展示，不做切换。 */
    fun gitBranches(conn: Connection): List<GitBranchDto> = decode(
        request(conn, "GET", "/api/workspace/git/branches"),
        ListSerializer(GitBranchDto.serializer()),
    )

    /** `GET /api/workspace/git/commit-diff?commit_hash=`。 */
    fun gitCommitDiff(conn: Connection, hash: String): GitCommitDiffDto = decode(
        getUrl(
            conn,
            workspaceUrl(
                conn,
                "/api/workspace/git/commit-diff",
                mapOf("commit_hash" to hash),
            ),
        ),
        GitCommitDiffDto.serializer(),
    )

    /** `POST /api/workspace/git/checkout`。`create=true` 时服务端执行 `checkout -b`。 */
    fun gitCheckout(conn: Connection, branch: String, create: Boolean): GitCheckoutResultDto =
        decode(
            request(
                conn,
                "POST",
                "/api/workspace/git/checkout",
                buildJsonObject {
                    put("branch", branch)
                    put("create", create)
                },
            ),
            GitCheckoutResultDto.serializer(),
        )

    /** `POST /api/workspace/git/discard`。会同时 restore 已跟踪文件与 clean 未跟踪文件。 */
    fun gitDiscard(conn: Connection, paths: List<String>): GitDiscardResultDto = decode(
        request(
            conn,
            "POST",
            "/api/workspace/git/discard",
            buildJsonObject {
                putJsonArray("paths") { paths.forEach { add(it) } }
            },
        ),
        GitDiscardResultDto.serializer(),
    )

    /** `POST /api/workspace/git/revert`。服务端会生成一条新提交。 */
    fun gitRevert(conn: Connection, hash: String): GitRevertResultDto = decode(
        request(
            conn,
            "POST",
            "/api/workspace/git/revert",
            buildJsonObject { put("commit_hash", hash) },
        ),
        GitRevertResultDto.serializer(),
    )

    // ---------- 插件 SSH ----------

    /** 插件 `/ssh/config` 的脱敏目标；未配置时插件回 404。 */
    fun bridgeSshConfig(conn: Connection): SshConfigDto = decode(
        request(conn, "GET", BRIDGE_SSH_CONFIG_PATH),
        SshConfigDto.serializer(),
    )

    /**
     * 插件 `/ssh/credential`。响应含私钥或登录密码，**不写日志**。
     *
     * 错误分支只透传插件给的脱敏 detail；插件侧保证 detail 不带密钥内容。
     */
    fun bridgeSshCredential(conn: Connection, trusted: Boolean = false): SshCredentialDto = decode(
        execute(
            baseBuilder(conn, connUrl(conn, BRIDGE_SSH_CREDENTIAL_PATH))
                .apply { if (trusted) header(TRUSTED_HOST_HEADER, "1") }
                .get()
                .build(),
        ),
        SshCredentialDto.serializer(),
    )

    /** SSH WebSocket 的地址；basicAuth 与 token 冲突时 token 走 `?token=`。 */
    fun bridgeSshUrl(conn: Connection): HttpUrl = connUrl(conn, BRIDGE_SSH_PATH)

    /** SSH WebSocket 的请求：带 X-Agent-Id、Bearer/Basic 或 `?token=`。 */
    fun bridgeSshRequest(conn: Connection, trusted: Boolean = false): Request =
        baseBuilder(conn, bridgeSshUrl(conn))
            .apply { if (trusted) header(TRUSTED_HOST_HEADER, "1") }
            .build()

    private fun checkpointRestore(
        conn: Connection,
        req: RestoreCheckpointRequestDto,
        preview: Boolean,
    ): RestoreResultDto {
        val path = if (preview) {
            "/api/workspace/checkpoints/restore/preview"
        } else {
            "/api/workspace/checkpoints/restore"
        }
        return decode(
            execute(
                baseBuilder(conn, connUrl(conn, path))
                    .post(json.encodeToString(RestoreCheckpointRequestDto.serializer(), req).toRequestBody(JSON_MEDIA))
                    .build(),
            ),
            RestoreResultDto.serializer(),
        )
    }

    private fun checkpointGc(conn: Connection, req: GcRequestDto, preview: Boolean): GcResultDto {
        val path = if (preview) {
            "/api/workspace/checkpoints/gc/preview"
        } else {
            "/api/workspace/checkpoints/gc"
        }
        return decode(
            execute(
                baseBuilder(conn, connUrl(conn, path))
                    .post(json.encodeToString(GcRequestDto.serializer(), req).toRequestBody(JSON_MEDIA))
                    .build(),
            ),
            GcResultDto.serializer(),
        )
    }

    // ---------- 频道 ----------

    /**
     * 频道相关的地址。
     *
     * 频道名逐段交给 [HttpUrl.Builder.addPathSegment]，不要先 `encodeSegment`：
     * 那会把 `%` 再编码一次（见过 `plugin%3Ademo` 变成 `plugin%253Ademo`）。
     */
    private fun channelUrl(
        conn: Connection,
        channel: String,
        vararg suffix: String,
    ): HttpUrl {
        val builder = resolve(conn.baseUrl, "/api/config/channels").newBuilder()
        builder.addPathSegment(channel)
        suffix.forEach { builder.addPathSegment(it) }
        return applyToken(conn, builder.build())
    }

    /**
     * `GET /api/config/channels`。
     *
     * 返回**以频道名为键的对象**，未保存过配置的频道给 `{enabled:false, bot_prefix:"", isBuiltin:true}`。
     * 保留原始 JsonObject：整份频道配置有几十个字段，本版只读 `enabled` 与 `isBuiltin`，
     * 其余字段留给写回时原样带回。
     */
    fun channels(conn: Connection): Map<String, JsonObject> =
        json.parseToJsonElement(request(conn, "GET", "/api/config/channels"))
            .jsonObject
            .mapValues { (_, value) -> value.jsonObject }

    /** `GET /api/config/channels/{channel}`。写回前读当前配置，避免整段替换冲掉其它字段。 */
    fun channel(conn: Connection, name: String): JsonObject =
        json.parseToJsonElement(getUrl(conn, channelUrl(conn, name))).jsonObject

    /**
     * `GET /api/config/channels/schemas`。
     *
     * 2.3.0 起频道实现是插件，这一层才有 `label`、`qrcode_available`、`availability`
     * 与 `repair`；2.2.2 的同名端点只返回插件频道的字段说明，缺这些键。
     * 保留原始对象由 [io.qwenpaw.node.data.channel.channelCapability] 解析。
     */
    fun channelSchemas(conn: Connection): Map<String, JsonObject> =
        json.parseToJsonElement(request(conn, "GET", "/api/config/channels/schemas"))
            .jsonObject
            .mapValues { (_, value) -> value.jsonObject }

    /** `POST …/conflict-check`。只做提示，服务端没有强制覆盖参数。 */
    fun channelConflictCheck(
        conn: Connection,
        name: String,
        body: JsonObject,
    ): ChannelConflictDto = decode(
        request(
            conn,
            "POST",
            "/api/config/channels/${encodeSegment(name)}/conflict-check",
            body,
        ),
        ChannelConflictDto.serializer(),
    )

    fun channelHealth(conn: Connection, name: String): ChannelHealthDto = decode(
        request(conn, "GET", "/api/config/channels/${encodeSegment(name)}/health"),
        ChannelHealthDto.serializer(),
    )

    fun restartChannel(conn: Connection, name: String): ChannelRestartDto = decode(
        request(conn, "POST", "/api/config/channels/${encodeSegment(name)}/restart"),
        ChannelRestartDto.serializer(),
    )

    /** `GET …/qrcode`。不支持的频道服务端回 404。 */
    fun channelQrcode(conn: Connection, name: String): ChannelQrDto = decode(
        getUrl(conn, channelUrl(conn, name, "qrcode")),
        ChannelQrDto.serializer(),
    )

    /** `GET …/qrcode/status?token=`。`token` 必填，缺失时服务端回 422。 */
    fun channelQrStatus(conn: Connection, name: String, pollToken: String): ChannelQrStatusDto {
        val url = channelUrl(conn, name, "qrcode", "status").newBuilder()
            .addQueryParameter("token", pollToken)
            .build()
        return decode(getUrl(conn, url), ChannelQrStatusDto.serializer())
    }

    /**
     * `PUT /api/config/channels/{channel}`。
     *
     * **整段替换**：服务端用 `config_class(**body)` 重建该频道的配置，缺省字段回落默认值。
     * 调用方必须先把当前配置读出来合并，否则 `enabled` 与策略字段会被冲掉。
     */
    fun updateChannel(conn: Connection, name: String, body: JsonObject): String =
        execute(
            baseBuilder(conn, channelUrl(conn, name))
                .put(body.toString().toRequestBody(JSON_MEDIA))
                .build(),
        )

    /**
     * `DELETE …/qrcode?token=`。
     *
     * 2.3.0 起二维码按会话托管（TTL 300 秒），主动回收能立刻释放上游 worker。
     * 2.2.2 没有这条路由，调用方按尽力而为处理。
     */
    fun closeChannelQrcode(conn: Connection, name: String, pollToken: String) {
        val url = channelUrl(conn, name, "qrcode").newBuilder()
            .addQueryParameter("token", pollToken)
            .build()
        execute(baseBuilder(conn, url).delete().build())
    }

    // ---------- 本地模型 ----------

    /** `GET /api/local-models/server`。未安装 llama.cpp 时仍是 200，看 `installed` 与 `message`。 */
    fun localModelServer(conn: Connection): LocalModelServerDto = decode(
        request(conn, "GET", "/api/local-models/server"),
        LocalModelServerDto.serializer(),
    )

    /** `GET /api/local-models/models`。服务端按内存推荐，内存不足时返回空数组。 */
    fun localModels(conn: Connection): List<LocalModelDto> = decode(
        request(conn, "GET", "/api/local-models/models"),
        ListSerializer(LocalModelDto.serializer()),
    )

    /** `POST /api/local-models/models/download`。立即返回，进度靠轮询；进行中再触发是 409。 */
    fun startLocalModelDownload(conn: Connection, modelName: String) {
        request(
            conn,
            "POST",
            "/api/local-models/models/download",
            buildJsonObject {
                put("model_name", modelName)
                put("source", "auto")
            },
        )
    }

    fun localModelDownloadProgress(conn: Connection): LocalModelProgressDto = decode(
        request(conn, "GET", "/api/local-models/models/download"),
        LocalModelProgressDto.serializer(),
    )

    /** `DELETE …/models/download`。只把状态置为 `canceling`，要轮询到 `cancelled` 才算结束。 */
    fun cancelLocalModelDownload(conn: Connection) {
        request(conn, "DELETE", "/api/local-models/models/download")
    }

    /** `POST /api/local-models/server/download`。安装 llama.cpp 自身，不可安装时回 409/400。 */
    fun startServerDownload(conn: Connection) {
        request(conn, "POST", "/api/local-models/server/download")
    }

    fun serverDownloadProgress(conn: Connection): LocalModelProgressDto = decode(
        request(conn, "GET", "/api/local-models/server/download"),
        LocalModelProgressDto.serializer(),
    )

    fun cancelServerDownload(conn: Connection) {
        request(conn, "DELETE", "/api/local-models/server/download")
    }

    /** `GET /api/local-models/config`。 */
    fun localModelConfig(conn: Connection): LocalModelConfigDto = decode(
        request(conn, "GET", "/api/local-models/config"),
        LocalModelConfigDto.serializer(),
    )

    /**
     * `PUT /api/local-models/config`，省略字段表示不改。
     *
     * `port` 有两种特殊语义：不传=保持原值，传 `null`=改回自动端口。
     * 因此单独用 [clearPort] 表达后者，不能靠 `port = null` 兼作「未改」。
     */
    fun setLocalModelConfig(
        conn: Connection,
        config: LocalModelConfigDto,
        clearPort: Boolean = false,
    ) {
        val body = buildJsonObject {
            config.maxContextLength?.let { put("max_context_length", it) }
            if (clearPort) put("port", JsonNull) else config.port?.let { put("port", it) }
            config.generateKwargs?.let { put("generate_kwargs", it) }
        }
        request(conn, "PUT", "/api/local-models/config", body)
    }

    /** `DELETE /api/local-models/models/{model_id:path}`。运行中的模型回 409。 */
    fun deleteLocalModel(conn: Connection, modelId: String) {
        request(conn, "DELETE", "/api/local-models/models/$modelId")
    }

    /** `GET /api/local-models/server/update`。 */
    fun localModelServerUpdate(conn: Connection): LocalModelServerUpdateDto = decode(
        request(conn, "GET", "/api/local-models/server/update"),
        LocalModelServerUpdateDto.serializer(),
    )

    /** `POST /api/local-models/server`，用已下载的模型启动运行时。 */
    fun startLocalModelServer(conn: Connection, modelId: String) {
        val body = buildJsonObject { put("model_id", modelId) }
        request(conn, "POST", "/api/local-models/server", body)
    }

    /** `DELETE /api/local-models/server`，停止运行时。 */
    fun stopLocalModelServer(conn: Connection) {
        request(conn, "DELETE", "/api/local-models/server")
    }

    // ---------- 浏览器诊断 ----------

    /** `GET /api/browser/chrome/status`。服务端低于 2.3.0 时回 404。 */
    fun browserChromeStatus(conn: Connection): BrowserChromeStatusDto = decode(
        request(conn, "GET", "/api/browser/chrome/status"),
        BrowserChromeStatusDto.serializer(),
    )

    /** `POST /api/browser/chrome/self-test`；结果同时写进服务端的 last_self_test。 */
    fun browserSelfTest(conn: Connection): BrowserSelfTestDto = decode(
        request(conn, "POST", "/api/browser/chrome/self-test"),
        BrowserSelfTestDto.serializer(),
    )

    /** Bridge 0.5.0 的 NM host 日志（插件侧已脱敏）。 */
    fun bridgeBrowserLogs(conn: Connection, lines: Int = 200): BridgeBrowserLogsDto {
        val url = workspaceUrl(
            conn,
            "/api/plugins/qwenpaw-node-bridge/browser/logs",
            mapOf("lines" to lines.toString()),
        )
        return decode(getUrl(conn, url), BridgeBrowserLogsDto.serializer())
    }

    /** Bridge 0.5.0 的修复端点；当前只接受 `setup`。 */
    fun bridgeBrowserRepair(conn: Connection) {
        val body = buildJsonObject { put("action", "setup") }
        request(conn, "POST", "/api/plugins/qwenpaw-node-bridge/browser/repair", body)
    }

    // ---------- 内部实现 ----------

    /**
     * 导入需要读 409 与 400 的完整 body。
     *
     * 不能走 [execute]：它只把 `detail` 截断成字符串，丢失 `existing` /
     * `pending_token`，而这两个字段决定用户能不能一键完成覆盖。
     */
    private fun executeImport(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return@use text
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val detail = root?.get("detail")
            if (detail is kotlinx.serialization.json.JsonPrimitive &&
                detail.content == "backup_conflict"
            ) {
                val existing = root["existing"]?.let {
                    runCatching {
                        json.decodeFromJsonElement(BackupMetaDto.serializer(), it)
                    }.getOrNull()
                }
                val token = runCatching {
                    root["pending_token"]?.jsonPrimitive?.content
                }.getOrNull()
                if (existing != null && !token.isNullOrBlank()) {
                    throw BackupImportConflictException(existing, token)
                }
            }
            trustExceptionOf(text)?.let { throw it }
            logFailure(request, response.code, text)
            throw failureOf(
                response.code,
                text,
                response.header("WWW-Authenticate"),
                tokenSentOf(request),
            )
        }

    /** 与 [execute] 同构，但把 400 的签名错误转成 [BackupTrustException]。 */
    private fun executeTrustAware(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return@use text
            trustExceptionOf(text)?.let { throw it }
            logFailure(request, response.code, text)
            throw failureOf(
                response.code,
                text,
                response.header("WWW-Authenticate"),
                tokenSentOf(request),
            )
        }

    /**
     * 从 400 的 `detail` 对象里取签名错误。
     *
     * 只有 `detail` 是对象且带 `code` 时才成立：其余 400（例如参数非法）
     * 的 `detail` 是字符串，必须继续按普通 [ApiException] 处理。
     */
    private fun trustExceptionOf(body: String): BackupTrustException? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val detail = root["detail"] as? JsonObject ?: return null
        val code = detail["code"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        } ?: return null
        val message = detail["message"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        } ?: code
        return BackupTrustException(code, message)
    }

    private fun tokenSentOf(request: Request): Boolean =
        request.header("Authorization")?.startsWith("Bearer ") == true ||
            request.url.queryParameter("token") != null

    private fun logFailure(request: Request, code: Int, body: String) {
        io.qwenpaw.node.log.RunLog.d(
            io.qwenpaw.node.log.LogKind.NETWORK,
            "API",
            "${request.method} ${request.url.encodedPath} → $code ${extractDetail(body, code)}",
        )
    }

    /** 路径段编码：备份 id 与 job id 由服务端生成，仍按段编码以避开 `?` 与 `#`。 */
    private fun encodeSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * 从 409 的 `detail` 中取出同名文件列表。
     *
     * `extractDetail` 已把 `detail` 序列化成字符串，这里再解析回对象。
     * 解析失败（例如原文被截断）返回 null，让调用方按普通 [ApiException] 处理。
     */
    private fun uploadConflictOf(detail: String?): UploadConflictException? {
        val raw = detail ?: return null
        val names = runCatching {
            json.parseToJsonElement(raw).jsonObject["files"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
        }.getOrNull() ?: return null
        return UploadConflictException(names)
    }

    // ---------- 访问控制 ----------

    fun aclOverview(conn: Connection): Map<String, AclChannelDto> = decode(
        request(conn, "GET", "/api/access-control"),
        MapSerializer(String.serializer(), AclChannelDto.serializer()),
    )

    fun aclPending(conn: Connection): List<AclPendingDto> = decode(
        request(conn, "GET", "/api/access-control/pending/all"),
        ListSerializer(AclPendingDto.serializer()),
    )

    fun mailAclOverview(conn: Connection): Map<String, AclChannelDto> = decode(
        request(conn, "GET", "/api/mail-access-control"),
        MapSerializer(String.serializer(), AclChannelDto.serializer()),
    )

    fun mailAclPending(conn: Connection): List<AclPendingDto> = decode(
        request(conn, "GET", "/api/mail-access-control/pending/all"),
        ListSerializer(AclPendingDto.serializer()),
    )

    fun mailAclPendingCount(conn: Connection): Int = decode(
        request(conn, "GET", "/api/mail-access-control/pending/count"),
        MailAclPendingCountDto.serializer(),
    ).count

    fun aclAction(
        conn: Connection,
        action: String,
        entries: List<AclActionEntryDto>,
    ) {
        request(conn, "POST", "/api/access-control/$action", aclBody(entries))
    }

    fun aclRemark(conn: Connection, channel: String, userId: String, remark: String) {
        request(
            conn,
            "POST",
            "/api/access-control/pending/remark",
            buildJsonObject {
                put("channel", channel)
                put("user_id", userId)
                put("remark", remark)
            },
        )
    }

    fun mailAclAction(
        conn: Connection,
        action: String,
        entries: List<MailAclActionEntryDto>,
    ) {
        request(conn, "POST", "/api/mail-access-control/$action", mailAclBody(entries))
    }

    fun mailAclRemark(conn: Connection, agentId: String, address: String, remark: String) {
        request(
            conn,
            "POST",
            "/api/mail-access-control/pending/remark",
            buildJsonObject {
                put("agent_id", agentId)
                put("address", address)
                put("remark", remark)
            },
        )
    }

    /** `POST /api/access-control/remark`：更新白/黑名单条目的备注（不是待审批）。 */
    fun aclListRemark(conn: Connection, channel: String, userId: String, remark: String) {
        request(
            conn,
            "POST",
            "/api/access-control/remark",
            buildJsonObject {
                put("channel", channel)
                put("user_id", userId)
                put("remark", remark)
            },
        )
    }

    /** `POST /api/access-control/username`：更新白/黑名单条目的用户名。 */
    fun aclUsername(conn: Connection, channel: String, userId: String, username: String) {
        request(
            conn,
            "POST",
            "/api/access-control/username",
            buildJsonObject {
                put("channel", channel)
                put("user_id", userId)
                put("username", username)
            },
        )
    }

    /** `POST /api/mail-access-control/remark`：更新邮件白/黑名单条目的备注。 */
    fun mailAclListRemark(conn: Connection, agentId: String, address: String, remark: String) {
        request(
            conn,
            "POST",
            "/api/mail-access-control/remark",
            buildJsonObject {
                put("agent_id", agentId)
                put("address", address)
                put("remark", remark)
            },
        )
    }

    fun mailAclAgents(conn: Connection): List<String> = decode(
        request(conn, "GET", "/api/mail-access-control/agents"),
        MailAclAgentsDto.serializer(),
    ).agents

    fun mailAclPauses(conn: Connection): List<MailAclPauseDto> = decode(
        request(conn, "GET", "/api/mail-access-control/processing-pauses"),
        ListSerializer(MailAclPauseDto.serializer()),
    )

    fun mailAclResume(conn: Connection, agentId: String, pauseId: String) {
        request(
            conn,
            "POST",
            "/api/mail-access-control/processing/${encodeSegment(agentId)}/resume",
            buildJsonObject { put("pause_id", pauseId) },
        )
    }

    private fun aclBody(entries: List<AclActionEntryDto>): JsonObject = buildJsonObject {
        putJsonArray("entries") {
            entries.forEach { entry ->
                addJsonObject {
                    put("channel", entry.channel)
                    put("user_id", entry.userId)
                    put("remark", entry.remark)
                    put("username", entry.username)
                }
            }
        }
    }

    private fun mailAclBody(entries: List<MailAclActionEntryDto>): JsonObject = buildJsonObject {
        putJsonArray("entries") {
            entries.forEach { entry ->
                addJsonObject {
                    put("agent_id", entry.agentId)
                    put("address", entry.address)
                    entry.remark?.let { put("remark", it) }
                    entry.displayName?.let { put("display_name", it) }
                }
            }
        }
    }

    private fun request(
        conn: Connection,
        method: String,
        path: String,
        body: JsonObject? = null,
        http: OkHttpClient = client,
    ): String = requestRaw(conn, method, path, body?.toString(), http)

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
        http: OkHttpClient = client,
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
            else -> error(Texts.get(R.string.api_unsupported_method, method))
        }
        return execute(builder.build(), http)
    }

    /** 用已经拼好的地址发 GET，供查询参数较多的端点使用。 */
    private fun getUrl(conn: Connection, url: HttpUrl): String =
        execute(baseBuilder(conn, url).get().build())

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

    private fun execute(request: Request, http: OkHttpClient = client): String {
        // 「带过凭据」指的是 QwenPaw 自己的凭据（Bearer 或 `token=`），
        // 反代 Basic 不算：反代放行后服务端仍可能说没登录。
        val tokenSent = request.header("Authorization")?.startsWith("Bearer ") == true ||
            request.url.queryParameter("token") != null
        return http.newCall(request).execute().use { response ->
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
            else -> error(Texts.get(R.string.api_unsupported_method, method))
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
        if (text.isBlank()) throw ApiException(204, Texts.get(R.string.api_empty_response))
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
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
        private val WAV_MEDIA = "audio/wav".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA)

        /** 语音转写端点。整段上传后返回全文，没有流式形态。 */
        const val TRANSCRIBE_PATH = "/api/workspace/transcribe"

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
        internal val LOGIN_NOT_REACHED: String
            get() = Texts.get(R.string.drest_login_not_reached)

        /**
         * 登录接口 401 的说明。
         *
         * 用户此时还没拿到过 token，说「登录已失效」会把人引向一个不存在的失效状态。
         */
        internal val BAD_CREDENTIALS: String
            get() = Texts.get(R.string.drest_bad_credentials)

        /**
         * 配套插件的自述端点。
         *
         * **不能**用 `/status` 或 `/files` 下任意子路径：核心的 `plugins.py` 已注册同前缀下的
         * 这两条路由且先于插件 router 匹配，实测在未装插件的实例上会返回带参数化
         * detail 的 404（`Plugin '…' not found.`），请求根本到不了插件。
         * `/info`、`/search`、`/events` 是核心没有的子路径，可以安全使用。
         */
        const val BRIDGE_INFO_PATH = "/api/plugins/qwenpaw-node-bridge/info"

        /** 插件更新状态端点。核心没有注册该子路径，不会被 `plugins.py` 抢先匹配。 */
        const val BRIDGE_UPDATE_STATUS_PATH =
            "/api/plugins/qwenpaw-node-bridge/update/status"

        /** 插件全文检索端点。中文查询由插件侧走 `LIKE`，英文走 FTS5。 */
        const val BRIDGE_SEARCH_PATH = "/api/plugins/qwenpaw-node-bridge/search"

        /** 插件事件流端点（SSE）。 */
        const val BRIDGE_EVENTS_PATH = "/api/plugins/qwenpaw-node-bridge/events"

        /** portability 导入转发端点。 */
        const val BRIDGE_PORTABILITY_PATH = "/api/plugins/qwenpaw-node-bridge/portability"

        /** 插件 SSH 中继与脱敏配置端点。 */
        const val BRIDGE_SSH_PATH = "/api/plugins/qwenpaw-node-bridge/ssh"
        const val BRIDGE_SSH_CONFIG_PATH = "/api/plugins/qwenpaw-node-bridge/ssh/config"
        const val BRIDGE_SSH_CREDENTIAL_PATH = "/api/plugins/qwenpaw-node-bridge/ssh/credential"
        /** App 在用户确认过的受信任地址上带的标记头；插件在未开 Web 认证时据此放行。 */
        const val TRUSTED_HOST_HEADER = "X-QwenPaw-Trusted-Host"

        /**
         * 终端长轮询专用客户端。
         *
         * 服务端 `output` 最多阻塞 20 秒，读超时必须大于它；这份客户端也不挂网络日志，
         * 否则一次终端会话会产生大量没有信息量的轮询记录。
         */
        private val terminalClientShared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

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
            buildProbeClient(io.qwenpaw.node.data.AddressProbePlan.DEFAULT)
        }

        private val probeClients =
            java.util.concurrent.ConcurrentHashMap<io.qwenpaw.node.data.ProbePolicy, OkHttpClient>()

        /**
         * 按超时档位取探测客户端。
         *
         * 局域网与公网用不同档位，但同一档位复用同一实例：每次新建都会多一个
         * 连接池与线程池，而验证按钮可能被反复点击。
         */
        fun probeClient(
            policy: io.qwenpaw.node.data.ProbePolicy =
                io.qwenpaw.node.data.AddressProbePlan.DEFAULT,
        ): OkHttpClient = if (policy == io.qwenpaw.node.data.AddressProbePlan.DEFAULT) {
            probeClientShared
        } else {
            probeClients.getOrPut(policy) { buildProbeClient(policy) }
        }

        private fun buildProbeClient(policy: io.qwenpaw.node.data.ProbePolicy): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(policy.connectMillis, TimeUnit.MILLISECONDS)
                .readTimeout(policy.readMillis, TimeUnit.MILLISECONDS)
                .callTimeout(policy.callMillis, TimeUnit.MILLISECONDS)
                // 探测要的是「这个地址现在通不通」，不要在内部重试掩盖问题。
                .retryOnConnectionFailure(false)
                // 也不复用连接：QwenPaw（uvicorn）的 keep-alive 只有 5 秒，
                // 两次验证的间隔通常远超它，池里的连接早已被对端关闭。
                // 复用它只会读到 EOF，把「地址是通的」误报成「网络中断」；
                // 每次探测重新握手，一次失败就真的是失败。
                .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                .addNetworkInterceptor(io.qwenpaw.node.log.LoggingInterceptor(io.qwenpaw.node.log.LogKind.NETWORK))
                .build()

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
                ?: throw IllegalArgumentException(Texts.get(R.string.api_invalid_address, baseUrl))
            return url.newBuilder()
                .addPathSegments(path.removePrefix("/").substringBefore('?'))
                .query(path.substringAfter('?', "").ifBlank { null })
                .build()
        }
    }
}
