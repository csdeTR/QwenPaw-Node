package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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

/** 供应商，对应 `GET /api/models` 的元素（只取客户端需要的字段）。 */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
)

/** token 用量汇总，对应 `GET /api/token-usage`。 */
@Serializable
data class TokenUsageDto(
    @SerialName("total_prompt_tokens") val totalPromptTokens: Long = 0,
    @SerialName("total_completion_tokens") val totalCompletionTokens: Long = 0,
    @SerialName("total_cache_read_tokens") val totalCacheReadTokens: Long = 0,
    @SerialName("cache_hit_rate") val cacheHitRate: Double = 0.0,
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
