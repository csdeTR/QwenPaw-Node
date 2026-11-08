package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * QwenPaw 服务端协议模型。
 *
 * 字段命名以 QwenPaw 2.2.1 实际返回（`/api/console/chat` SSE 与 `/api/chats`）为准，
 * 未使用的字段一律允许忽略，避免服务端小版本升级导致反序列化失败。
 */

/** 会话摘要，对应 `GET /api/chats` 的元素。 */
@Serializable
data class ChatSpecDto(
    val id: String,
    val name: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("user_id") val userId: String? = null,
    val channel: String = "console",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_finished_at") val lastFinishedAt: String? = null,
    val status: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    @SerialName("group_id") val groupId: String? = null,
    val source: String? = null,
)

/** 会话历史，对应 `GET /api/chats/{chat_id}`。 */
@Serializable
data class ChatHistoryDto(
    val messages: List<MessageDto> = emptyList(),
    val status: String? = null,
)

/**
 * 单条消息。`type` 取值实测包含：message / reasoning / plugin_call / plugin_call_output。
 */
@Serializable
data class MessageDto(
    val id: String? = null,
    val type: String = "message",
    val role: String = "assistant",
    val content: List<ContentBlockDto> = emptyList(),
    val status: String? = null,
    val metadata: MessageMetadataDto? = null,
)

@Serializable
data class MessageMetadataDto(
    val timestamp: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("original_id") val originalId: String? = null,
    /** 发送者名称。助手侧是智能体显示名（如 `Default Agent`），用户侧通常是 `user`。 */
    @SerialName("original_name") val originalName: String? = null,
    /** 用量与上下文信息。服务端把整轮数据挂在这一层，键名为 `qwenpaw_turn_usage`。 */
    val metadata: TurnUsageMetaDto? = null,
)

/** `metadata` 里的一层包装。目前只有 `qwenpaw_turn_usage` 一个键。 */
@Serializable
data class TurnUsageMetaDto(
    @SerialName("qwenpaw_turn_usage") val turnUsage: TurnUsageWrapperDto? = null,
)

/**
 * 一轮的用量包装。
 *
 * 只取 `usage`。`context_usage` 不在这里建模：窗口占用是流式帧里单独推送的
 * 实时值，历史接口里的快照对界面没有用处，建模只会多一层不读的字段。
 */
@Serializable
data class TurnUsageWrapperDto(
    val usage: TurnUsageStatsDto? = null,
)

/**
 * 单轮用量。
 *
 * 与聚合统计的 [TokenUsageDto] 是两回事：那个是 `GET /api/token-usage` 的累计值，
 * 字段名都带 `total_` 前缀。这里只描述一轮。
 */
@Serializable
data class TurnUsageStatsDto(
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    /**
     * 缓存命中率（百分数）。
     *
     * 该轮没有可观测的缓存命中时（服务端 `cache_observed=false`、
     * `cache_eligible_input_tokens=0`），服务端返回 `null` 而不是 0。
     * 不能声明成非空类型：非空字段收到显式 null 时序列化库直接抛异常，
     * 整份历史响应都会解析失败。
     */
    @SerialName("cache_hit_rate") val cacheHitRate: Double? = null,
    @SerialName("context_size") val contextSize: Long = 0,
)

/**
 * 内容块。
 *
 * - `type = "text"`：普通文本或思考文本
 * - `type = "data"`：工具调用参数 / 工具输出，具体载荷在 [data] 内
 * - `type = "image"`：图片，URL 在 [imageUrl]（历史接口会内联成 data URI）
 * - `type = "file"`：文件附件，URL 在 [fileUrl]
 *
 * 注意：请求侧服务端读的是 `image_url` / `file_url` / `video_url` 这类扁平字段，
 * 而不是 `source.url`。`source` 仅作为老协议的兜底保留。
 */
@Serializable
data class ContentBlockDto(
    val type: String = "text",
    val text: String? = null,
    val delta: Boolean = false,
    val index: Int? = null,
    val data: JsonElement? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    val filename: String? = null,
    val source: ContentSourceDto? = null,
)

/** 媒体内容块的来源（老协议 `source.url` 形式）。 */
@Serializable
data class ContentSourceDto(
    val type: String? = null,
    val url: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

/** 待审批请求，对应 `GET /api/approval/list` 与 `push-messages` 的 `pending_approvals`。 */
@Serializable
data class ApprovalDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("root_session_id") val rootSessionId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("tool_name") val toolName: String = "",
    val severity: String? = null,
    @SerialName("findings_count") val findingsCount: Int? = null,
    @SerialName("result_summary") val resultSummary: String? = null,
    val reasoning: String = "",
    @SerialName("tool_params") val toolParams: JsonElement? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("timeout_seconds") val timeoutSeconds: Double? = null,
)

/** 推送轮询响应，对应 `GET /api/console/push-messages`。 */
@Serializable
data class PushMessagesDto(
    val messages: List<JsonElement> = emptyList(),
    @SerialName("pending_approvals") val pendingApprovals: List<ApprovalDto> = emptyList(),
)

@Serializable
data class ApprovalListDto(
    @SerialName("pending_approvals") val pendingApprovals: List<ApprovalDto> = emptyList(),
    val count: Int = 0,
)

/** 智能体信息，对应 `GET /api/agents` 的元素。 */
@Serializable
data class AgentDto(
    val id: String,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    @SerialName("startup_status") val startupStatus: String? = null,
    @SerialName("active_model") val activeModel: ActiveModelDto? = null,
)

@Serializable
data class ActiveModelDto(
    @SerialName("provider_id") val providerId: String? = null,
    val model: String? = null,
)

/**
 * 全局默认模型，对应 `GET /api/models/active`。
 *
 * 实测服务端**拒绝**用 `null` 恢复会话的默认模型（`{"detail":"Model provider is unavailable"}`），
 * 客户端不能靠传空值回退：必须先取此端点里的 provider 与 model，再显式写到会话上。
 */
@Serializable
data class ModelsActiveDto(
    @SerialName("active_llm") val activeLlm: ActiveModelDto? = null,
    @SerialName("effective_max_input_length") val effectiveMaxInputLength: Long? = null,
)

@Serializable
data class AgentListDto(
    val agents: List<AgentDto> = emptyList(),
)

/** 登录/注册响应。 */
@Serializable
data class AuthTokenDto(
    val token: String,
    val username: String? = null,
)

/** 鉴权状态，对应 `GET /api/auth/status`。 */
@Serializable
data class AuthStatusDto(
    val enabled: Boolean = false,
    @SerialName("has_users") val hasUsers: Boolean = false,
)

/** 版本信息，对应 `GET /api/version`。 */
@Serializable
data class VersionDto(
    val version: String = "",
)

/**
 * 配套插件（QwenPaw Node Bridge）的自述，对应
 * `GET /api/plugins/qwenpaw-node-bridge/info`。
 *
 * [protocolVersion] 是两端契约的版本号，客户端只接受 `1`：更大的值说明契约已变更，
 * 旧客户端不该继续使用该插件的端点。功能开关一律看 [capabilities] 里的名字，
 * **不看** [pluginVersion]——比版本号推断能力会让两端各维护一张对照表。
 */
@Serializable
data class BridgeInfoDto(
    @SerialName("plugin_version") val pluginVersion: String = "",
    @SerialName("protocol_version") val protocolVersion: Int = 0,
    @SerialName("server_version") val serverVersion: String = "",
    val capabilities: List<String> = emptyList(),
) {
    /** 该能力是否被插件声明。 */
    fun supports(name: String): Boolean = capabilities.contains(name)

    companion object {
        /** 当前客户端能对话的契约版本。 */
        const val PROTOCOL = 1
    }
}

/**
 * 一条全文检索命中，对应配套插件 `GET …/qwenpaw-node-bridge/search` 的 `items[]`。
 *
 * [sessionId] 是 `history.db` 的 `session_id`，**不是** `/api/chats` 里的对话 `id`。
 * `mobile-*` 形态下两者一一对应，Web 端的历史会话会出现多个对话共用一个
 * `session_id`，因此要先到对话列表里换出对话 `id` 再打开，
 * 见 [io.qwenpaw.node.ui.chat.ChatViewModel.openChatAtMessage]。
 *
 * [msgId] 是 `history.db` 的 `dedup_key`，也是服务端消息对象
 * `metadata.original_id` 的值。**不能用消息的 `id`**：历史接口每次请求都会重新生成
 * `id`，同一段内容两次请求拿到的 `id` 不同，只有 `original_id` 稳定。
 */
@Serializable
data class SearchHitDto(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("msg_id") val msgId: String = "",
    val role: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    /** 命中片段，命中词用 `[` `]` 包裹，由界面转成高亮。 */
    val snippet: String = "",
    val score: Double = 0.0,
)

/** `/search` 的一页结果。 */
@Serializable
data class SearchPageDto(
    val items: List<SearchHitDto> = emptyList(),
    /** 下一页的偏移量；`null` 表示没有更多。 */
    @SerialName("next_cursor") val nextCursor: Int? = null,
    /** 同条件命中总数，可能大于本页条数。 */
    val total: Int = 0,
)

/** 附件上传结果，对应 `POST /api/console/upload`。 */
@Serializable
data class UploadResultDto(
    val url: String,
    @SerialName("file_name") val fileName: String = "",
    val size: Long = 0,
)

/** 命令执行状态，对应 `GET /api/chats/{chat_id}/status`。 */
@Serializable
data class ChatStatusDto(
    val status: String? = null,
)

/** 会话分组，对应 `GET /api/chats/groups` 的元素。 */
@Serializable
data class ChatGroupDto(
    val id: String,
    val name: String = "",
    val order: Int = 0,
    val kind: String? = null,
    val source: String? = null,
    val pinned: Boolean = false,
)

/**
 * 批量归档 / 取消归档的结果，对应 `/api/chats/actions/batch-*`。
 *
 * 运行中的会话会被服务端跳过并记进 [failed]。
 */
@Serializable
data class BatchChatResultDto(
    val succeeded: List<String> = emptyList(),
    val failed: List<BatchFailureDto> = emptyList(),
)

/** 批量操作里单条失败的原因。 */
@Serializable
data class BatchFailureDto(
    @SerialName("chat_id") val chatId: String = "",
    val reason: String = "",
)

/**
 * 思考模式视图，对应 `GET/PUT /api/chats/{chat_id}/thinking`。
 *
 * - [value] 是当前会话保存的偏好，[effective] 是实际生效值（可能被模型降级）。
 * - [control] 描述当前模型支持的调节方式，决定 UI 能给出哪些选项。
 */
@Serializable
data class ThinkingViewDto(
    @SerialName("model_source") val modelSource: String? = null,
    val model: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("model_key") val modelKey: String? = null,
    @SerialName("effective_max_input_length") val effectiveMaxInputLength: Long? = null,
    val control: ThinkingControlDto? = null,
    val value: ThinkingValueDto? = null,
    val effective: ThinkingValueDto? = null,
    val source: String? = null,
    val reason: String? = null,
)

@Serializable
data class ThinkingControlDto(
    val kind: String = "unknown",
    val efforts: List<String> = emptyList(),
    @SerialName("supports_off") val supportsOff: Boolean = false,
    @SerialName("budget_min") val budgetMin: Long? = null,
    @SerialName("budget_max") val budgetMax: Long? = null,
    @SerialName("budget_default") val budgetDefault: Long? = null,
    val wire: String? = null,
)

@Serializable
data class ThinkingValueDto(
    val level: String = "inherit",
    @SerialName("budget_tokens") val budgetTokens: Long? = null,
)

/** 模型池的一页，对应 `GET /api/models/{provider_id}/pool`。 */
@Serializable
data class ModelPoolDto(
    val models: List<ModelSlotDto> = emptyList(),
    val total: Int? = null,
)

/** 模型池里的单个模型条目。 */
@Serializable
data class ModelSlotDto(
    val id: String,
    val name: String = "",
    @SerialName("is_free") val isFree: Boolean = false,
    @SerialName("is_recommended") val isRecommended: Boolean = false,
    @SerialName("max_input_length") val maxInputLength: Long? = null,
)

/**
 * 供应商，对应 `GET /api/models` 的元素。
 *
 * 可选模型分散在三个数组里：`models`（配置里写明的）、`extra_models`（额外添加的）、
 * `discovered_models`（探测到的）。**不要用 `/api/models/{id}/pool` 取模型列表**——
 * 本地部署下该端点返回空数组，客户端若依赖它会显示「没有可用模型」。
 */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val models: List<ModelSlotDto> = emptyList(),
    @SerialName("extra_models") val extraModels: List<ModelSlotDto> = emptyList(),
    @SerialName("discovered_models") val discoveredModels: List<ModelSlotDto> = emptyList(),
    /** 该供应商当前的默认对话模型，用于在菜单里标出「默认」。 */
    @SerialName("chat_model") val chatModel: String? = null,
) {
    /** 三个数组按 id 去重合并，顺序为 配置 → 额外 → 探测。 */
    val allModels: List<ModelSlotDto>
        get() {
            val seen = LinkedHashMap<String, ModelSlotDto>()
            (models + extraModels + discoveredModels).forEach { m ->
                if (m.id.isNotBlank()) seen.putIfAbsent(m.id, m)
            }
            return seen.values.toList()
        }
}

/** 智能体配置，对应 `GET /api/agents/{id}`。只取客户端需要的字段。 */
@Serializable
data class AgentProfileDto(
    val id: String,
    val name: String = "",
    /** 审批模式：STRICT / SMART / AUTO / OFF。 */
    @SerialName("approval_level") val approvalLevel: String = "AUTO",
)

/**
 * 工作区目录，对应 `GET /api/workspace/project-directory`。
 *
 * `path` 是服务端上的绝对路径，与手机本地路径无关。
 */
@Serializable
data class ProjectDirDto(
    val path: String = "",
    val name: String = "",
    @SerialName("is_workspace_default") val isWorkspaceDefault: Boolean = false,
    @SerialName("workspace_dir") val workspaceDir: String? = null,
    val exists: Boolean = true,
)

/**
 * token 用量汇总，对应 `GET /api/token-usage`。
 *
 * `cache_hit_rate` 必须可空：服务端在没有任何调用时返回 `null`
 * （实测 2.3.0b2 空库返回 `"cache_hit_rate":null`，2.2.2 在同样情况下同样如此，
 * OpenAPI 里该字段也声明为 `number | null`）。声明成非空 `Double` 会让整个
 * 响应解析失败，用户看到的是「加载用量失败」而不是一组零值。
 */
@Serializable
data class TokenUsageDto(
    @SerialName("total_prompt_tokens") val totalPromptTokens: Long = 0,
    @SerialName("total_completion_tokens") val totalCompletionTokens: Long = 0,
    @SerialName("total_cache_read_tokens") val totalCacheReadTokens: Long = 0,
    @SerialName("cache_hit_rate") val cacheHitRate: Double? = null,
    @SerialName("total_calls") val totalCalls: Int = 0,
    @SerialName("by_model") val byModel: Map<String, TokenUsageBucketDto> = emptyMap(),
    @SerialName("by_date") val byDate: Map<String, TokenUsageBucketDto> = emptyMap(),
)

/** token 用量分组（按模型或按日期）。 */
@Serializable
data class TokenUsageBucketDto(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("call_count") val callCount: Int = 0,
    @SerialName("provider_id") val providerId: String? = null,
    val model: String? = null,
)

// ---------------- 收件箱 ----------------

/**
 * 收件箱事件，对应 `GET /api/console/inbox/events` 的元素。
 *
 * `created_at` 是**浮点秒**（服务端用 `time.time()` 写入），不是 ISO 字符串，
 * 也不是毫秒。声明成 `Double` 后由展示层乘 1000 再格式化。
 */
@Serializable
data class InboxEventDto(
    val id: String = "",
    @SerialName("agent_id") val agentId: String = "default",
    @SerialName("source_type") val sourceType: String = "",
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("event_type") val eventType: String = "",
    val status: String = "",
    val severity: String = "info",
    val title: String = "",
    val body: String = "",
    val payload: JsonObject? = null,
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: Double = 0.0,
)

/**
 * 收件箱一页。
 *
 * 响应里**没有 `has_more`**，只有 `total`；结束判定交给 `PagedList.ofOffset`：
 * 已取条数达到 `total` 即结束。
 */
@Serializable
data class InboxPageDto(
    val events: List<InboxEventDto> = emptyList(),
    val total: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
)

/** 一次运行的完整轨迹，对应 `GET /api/console/inbox/traces/{run_id}`。 */
@Serializable
data class InboxTraceDto(
    @SerialName("run_id") val runId: String = "",
    /** 浮点秒。 */
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("completed_at") val completedAt: Double? = null,
    val status: String = "",
    val meta: JsonObject? = null,
    val events: List<InboxTraceEventDto> = emptyList(),
    val error: String? = null,
)

/** 轨迹里的一条事件。`event` 是服务端原样的任意结构，这里只保留可读的文本与时间。 */
@Serializable
data class InboxTraceEventDto(
    val at: Double = 0.0,
    val event: JsonElement? = null,
)

// ---------------- 工具、环境变量与后台策略 ----------------

/**
 * 工具信息，对应 `GET /api/tools` 的元素。
 *
 * 注意字段名是 `config_fields` / `config_values`，**不是** `config`：
 * 按 `config` 解析会得到空表，表现为「有配置项的工具在客户端里看不到任何输入框」。
 * `config_values` 里的密码类字段已被服务端替换成 `***`。
 */
@Serializable
data class ToolDto(
    val name: String = "",
    val enabled: Boolean = false,
    @SerialName("source_plugin_id") val sourcePluginId: String? = null,
    @SerialName("source_plugin_name") val sourcePluginName: String? = null,
    val category: String? = null,
    val description: String = "",
    @SerialName("async_execution") val asyncExecution: Boolean = false,
    val icon: String = "🔧",
    @SerialName("requires_config") val requiresConfig: Boolean = false,
    // 实测 2.2.2.post1 对无配置的工具返回 `"config_fields":null` / `"config_values":null`，
    // 不是空数组/空对象。声明成非空集合会让整份工具列表解析失败。
    @SerialName("config_fields") val configFields: List<ToolConfigFieldDto>? = null,
    @SerialName("config_values") val configValues: Map<String, JsonElement>? = null,
)

/** 工具配置项的字段定义。`type` 取 text / password / number / boolean / select / textarea。 */
@Serializable
data class ToolConfigFieldDto(
    val name: String = "",
    val label: String = "",
    val type: String = "text",
    val required: Boolean = false,
    val placeholder: String? = null,
    val help: String? = null,
    // 实测 2.2.2.post1：非 select 类型的字段返回 `"options":null`，
    // 不是空数组。声明成非空集合会让整份工具列表解析失败，页面显示「没有可用的工具」。
    val options: List<String>? = null,
    val default: JsonElement? = null,
    val min: Double? = null,
    val max: Double? = null,
)

/** 一个已显式配置的环境变量。**`value` 是明文**，展示层必须默认遮罩。 */
@Serializable
data class EnvVarDto(
    val key: String = "",
    val value: String = "",
)

/**
 * 环境变量的规格元数据，对应 `GET /api/envs/catalog`。
 *
 * 内置变量不可删除，只能重置；`editable=false` 的项连编辑都不允许。
 */
@Serializable
data class EnvCatalogDto(
    val key: String = "",
    val default: String = "",
    @SerialName("effective_value") val effectiveValue: String = "",
    val source: String = "default",
    @SerialName("description_key") val descriptionKey: String = "",
    val editable: Boolean = true,
    @SerialName("value_type") val valueType: String = "string",
    @SerialName("readonly_reason_code") val readonlyReasonCode: String? = null,
    val mutability: String = "hot_runtime",
    val configured: Boolean = false,
)

/** 后台策略，对应 `GET|PUT /api/settings/offload-policy`。 */
@Serializable
data class OffloadPolicyDto(
    @SerialName("default_action") val defaultAction: String = "keep_foreground",
)

/** 一条可补全的斜杠命令，对应 `GET /api/workspace/commands/available` 的元素。 */
@Serializable
data class SlashCommandDto(
    val name: String = "",
    val description: String = "",
    /** 分类；服务端允许空串，此时界面按「未分类」处理。 */
    val category: String = "",
)

/**
 * 命令列表响应。
 *
 * 服务端实测返回 `{"commands":[...]}`；`items` 只是兼容位——响应形状没有实测记录时，
 * 两种键名都能解析比只认一种更稳，代价是一个几乎不会用到的字段。
 */
@Serializable
data class AvailableCommandsDto(
    val commands: List<SlashCommandDto> = emptyList(),
    val items: List<SlashCommandDto> = emptyList(),
) {
    val all: List<SlashCommandDto> get() = commands.ifEmpty { items }
}

// ---------------- 定时任务 ----------------

/**
 * 客户端统一使用的 cron 时区。
 *
 * 不做国际化：不读设备时区，也不跟随服务端（服务端默认 UTC）。固定北京时间，
 * 同一份任务在任何设备上算出的触发时刻才一致。
 */
internal const val CRON_TIMEZONE = "Asia/Shanghai"

/**
 * 一个定时任务，对应 `GET /api/cron/jobs` 的元素与 `POST|PUT` 的请求体。
 *
 * `request` 是服务端的 `extra="allow"` 模型，`meta` 里带迁移审阅标记
 * （`portability.requires_review` / `safety=disabled_until_explicit_promotion`）。
 * `PUT` 是整体替换，因此两者都保留原始 JSON：只序列化已知字段会在编辑一次之后
 * 抹掉审阅锁与模型覆盖。
 */
@Serializable
data class CronJobSpecDto(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val schedule: CronScheduleDto = CronScheduleDto(),
    @SerialName("task_type") val taskType: String = "agent",
    val text: String? = null,
    val request: JsonObject? = null,
    val dispatch: CronDispatchDto = CronDispatchDto(),
    /** 为 null 时由服务端计算：`not (text 且 cron)`。 */
    @SerialName("save_result_to_inbox") val saveResultToInbox: Boolean? = null,
    val runtime: CronRuntimeDto = CronRuntimeDto(),
    val meta: JsonObject = JsonObject(emptyMap()),
)

/** 调度规则。`type` 取 `cron` / `once`。 */
@Serializable
data class CronScheduleDto(
    val type: String = "cron",
    val cron: String? = null,
    @SerialName("run_at") val runAt: String? = null,
    val timezone: String = CRON_TIMEZONE,
    @SerialName("repeat_every_days") val repeatEveryDays: Int? = null,
    @SerialName("repeat_end_type") val repeatEndType: String? = null,
    @SerialName("repeat_until") val repeatUntil: String? = null,
    @SerialName("repeat_count") val repeatCount: Int? = null,
)

/** 投递目标。`user_id` / `session_id` 服务端允许空串。 */
@Serializable
data class CronTargetDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_id") val sessionId: String = "",
)

/** 结果投递方式。`silent` 只对 agent 任务有效，text 任务带它会 422。 */
@Serializable
data class CronDispatchDto(
    val type: String = "channel",
    val channel: String = "console",
    val target: CronTargetDto = CronTargetDto(),
    val mode: String = "stream",
    val silent: Boolean = false,
    val meta: JsonObject = JsonObject(emptyMap()),
)

/** 单次执行的运行时约束，默认值与服务端 `JobRuntimeSpec` 一致。 */
@Serializable
data class CronRuntimeDto(
    @SerialName("max_concurrency") val maxConcurrency: Int = 1,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 120,
    @SerialName("misfire_grace_seconds") val misfireGraceSeconds: Int = 600,
    @SerialName("share_session") val shareSession: Boolean = false,
    @SerialName("tool_safety") val toolSafety: Boolean = false,
)

/** 运行状态。`GET /api/cron/jobs` 不含这段，需另取 `/state`。 */
@Serializable
data class CronJobStateDto(
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

/** 一条执行记录，按时间倒序返回。 */
@Serializable
data class CronExecutionRecordDto(
    @SerialName("run_at") val runAt: String = "",
    val status: String = "",
    val error: String? = null,
    val trigger: String = "scheduled",
)

/** `GET /api/cron/jobs/{id}` 的响应。 */
@Serializable
data class CronJobViewDto(
    val spec: CronJobSpecDto = CronJobSpecDto(),
    val state: CronJobStateDto = CronJobStateDto(),
)

/** 一个可投递的会话目标，由已有会话派生。 */
@Serializable
data class CronDispatchTargetItemDto(
    val channel: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_id") val sessionId: String = "",
)

/** `GET /api/cron/dispatch-targets`。`console` 恒在 `channels` 首位。 */
@Serializable
data class CronDispatchTargetsDto(
    val channels: List<String> = emptyList(),
    val items: List<CronDispatchTargetItemDto> = emptyList(),
)

/** `GET /api/config/user-timezone`，`timezone` 可能为 null 或空串。 */
@Serializable
data class UserTimezoneDto(
    val timezone: String? = null,
)

// ---------------- 工具调用控制 ----------------

/**
 * 会话内的一次工具调用，对应 `GET /api/tool-calls/{session_id}` 的元素。
 *
 * `startedAt` 是服务端的 monotonic 秒，不是 epoch：只能用于对照 `elapsed`，
 * 不能当成时间戳显示。`offloadRemaining` / `killRemaining` 是取回时的快照，
 * 界面倒计时需要自己按已过时间递减。
 */
@Serializable
data class ToolCallInfoDto(
    @SerialName("tool_call_id") val toolCallId: String = "",
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("agent_id") val agentId: String = "",
    val status: String = "",
    @SerialName("started_at") val startedAt: Double = 0.0,
    val elapsed: Double = 0.0,
    @SerialName("offload_remaining") val offloadRemaining: Double? = null,
    @SerialName("kill_remaining") val killRemaining: Double? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    @SerialName("end_state") val endState: String? = null,
    @SerialName("force_cancelled") val forceCancelled: Boolean = false,
    @SerialName("max_internal_timeout_secs") val maxInternalTimeoutSecs: Double? = null,
    @SerialName("offload_reason") val offloadReason: String? = null,
)

/** `GET /api/tool-calls/{session_id}` 的响应。 */
@Serializable
data class ToolCallListDto(
    val items: List<ToolCallInfoDto> = emptyList(),
    val total: Int = 0,
)

/** `GET /api/tool-calls/{session_id}/{id}/output`。`content` 是 agentscope 的块数组。 */
@Serializable
data class ToolCallOutputDto(
    @SerialName("tool_call_id") val toolCallId: String = "",
    @SerialName("is_closed") val isClosed: Boolean = false,
    @SerialName("final_state") val finalState: String? = null,
    val content: JsonArray = JsonArray(emptyList()),
)
