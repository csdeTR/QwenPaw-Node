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
 */
@Serializable
data class ContentBlockDto(
    val type: String = "text",
    val text: String? = null,
    val delta: Boolean = false,
    val index: Int? = null,
    val data: JsonElement? = null,
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
