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
    val pinned: Boolean = false,
    @SerialName("workspace_dir") val workspaceDir: String = "",
    val backend: String = "qwenpaw",
    @SerialName("available_in_chat") val availableInChat: Boolean = true,
    @SerialName("startup_status") val startupStatus: String? = null,
    @SerialName("active_model") val activeModel: ActiveModelDto? = null,
)

/** 智能体运行时状态，对应 `GET /api/agents/{id}/agent-status`。 */
@Serializable
data class AgentStatusDto(
    val status: String = "idle",
    @SerialName("running_task_count") val runningTaskCount: Int = 0,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_finish_at") val lastFinishAt: String? = null,
)

/** 新建或复制智能体的返回，对应 `POST /api/agents` 与 `POST /api/agents/{id}/copy`。 */
@Serializable
data class AgentRefDto(
    val id: String = "",
    @SerialName("workspace_dir") val workspaceDir: String = "",
    val enabled: Boolean = true,
    val pinned: Boolean = false,
)

/** ACP Node 运行时状态，对应 `GET|PUT /api/config/acp/node-runtime`。 */
@Serializable
data class AcpNodeRuntimeDto(
    @SerialName("node_path") val nodePath: String = "",
    @SerialName("effective_node_path") val effectiveNodePath: String = "",
    val candidates: List<AcpNodeCandidateDto> = emptyList(),
)

/** 一个可用的 Node 运行时候选。 */
@Serializable
data class AcpNodeCandidateDto(
    val key: String = "",
    val label: String = "",
    @SerialName("node_path") val nodePath: String = "",
    @SerialName("npx_path") val npxPath: String = "",
    @SerialName("node_version") val nodeVersion: String = "",
    @SerialName("npx_version") val npxVersion: String = "",
    val available: Boolean = false,
    @SerialName("reason_code") val reasonCode: String = "",
    val reason: String = "",
)

/** 一个外部智能体后端（Codex / Qoder 等），对应 `GET /api/harnesses` 的 providers 元素。 */
@Serializable
data class HarnessProviderDto(
    val id: String = "",
    /** 旧形状的显示名。 */
    val name: String = "",
    /** 新形状（2.3.0.post1）的显示名。 */
    val label: String = "",
    @SerialName("plugin_id") val pluginId: String = "",
    /** 新形状的可用状态：available / disabled / failed；旧形状没有这个字段。 */
    val status: String? = null,
    val capabilities: JsonElement? = null,
    @SerialName("config_fields") val configFields: List<JsonObject> = emptyList(),
    val available: Boolean = false,
    @SerialName("coming_soon") val comingSoon: Boolean = false,
    val installed: Boolean = false,
    val authenticated: Boolean = false,
    val account: JsonObject? = null,
    @SerialName("runtime_path") val runtimePath: String? = null,
    @SerialName("runtime_source") val runtimeSource: String? = null,
    val error: String? = null,
) {
    /** 新旧两种形状统一的显示名。 */
    val displayName: String
        get() = label.ifBlank { name }.ifBlank { id }

    /** 新形状按 status 判定，旧形状沿用 available。 */
    val isAvailable: Boolean
        get() = status?.let { it == "available" } ?: available
}

/** 智能体可选后端，对应 `GET /api/agents/backends`。 */
@Serializable
data class AgentBackendDto(
    val id: String = "",
    val label: String = "",
    @SerialName("plugin_id") val pluginId: String? = null,
    val status: String = "",
    val capabilities: JsonElement? = null,
    @SerialName("config_fields") val configFields: List<JsonObject> = emptyList(),
) {
    val isAvailable: Boolean get() = status == "available"
}

@Serializable
data class AgentBackendsDto(
    val backends: List<AgentBackendDto> = emptyList(),
)

@Serializable
data class HarnessesDto(
    val providers: List<HarnessProviderDto> = emptyList(),
)

/** 一个可导入的来源（Codex / Qoder）。 */
@Serializable
data class PortabilitySourceDto(
    val source: String = "",
    val name: String = "",
    val detected: Boolean = false,
)

/** 一次导入中每个来源的资产选择。字段与插件契约一致。 */
@Serializable
data class PortabilitySelectionDto(
    val sessions: Boolean = true,
    val memory: List<String> = emptyList(),
    val cron: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val mcp: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
)

/** 一项可导入资产。 */
@Serializable
data class PortabilityAssetDto(
    @SerialName("asset_type") val assetType: String = "",
    @SerialName("source_id") val sourceId: String = "",
    val name: String = "",
    val state: String = "pending",
    val enabled: Boolean? = null,
    val message: String = "",
    @SerialName("requires_sessions") val requiresSessions: Boolean = false,
    @SerialName("blocked_reason") val blockedReason: String = "",
)

/** 导入任务里一个来源的进度。 */
@Serializable
data class PortabilityProviderDto(
    val source: String = "",
    val state: String = "scanning",
    @SerialName("plan_id") val planId: String = "",
    @SerialName("sessions_total") val sessionsTotal: Int = 0,
    @SerialName("sessions_processed") val sessionsProcessed: Int = 0,
    @SerialName("sessions_imported") val sessionsImported: Int = 0,
    val selection: PortabilitySelectionDto = PortabilitySelectionDto(),
    val assets: List<PortabilityAssetDto> = emptyList(),
    val error: String = "",
)

/** `GET /api/pawapps`。 */
@Serializable
data class PawAppsDto(
    val apps: List<PawAppDto> = emptyList(),
    val total: Int = 0,
)

/** 一个已安装的 PawApp。 */
@Serializable
data class PawAppDto(
    val id: String = "",
    val name: String = "",
    val version: String = "0.0.0",
    val description: String = "",
    @SerialName("description_i18n") val descriptionI18n: Map<String, String>? = null,
    val author: String = "",
    val category: String = "",
    val icon: String = "",
    @SerialName("icon_url") val iconUrl: String = "",
    @SerialName("entry_page") val entryPage: String = "",
    @SerialName("launch_scope") val launchScope: String = "page",
    val status: String = "installed",
)

/** 插件安装结果，对应 `POST /api/plugins/upload` 与 `/install`。 */
@Serializable
data class PluginInstallResultDto(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val loaded: Boolean = false,
    val message: String? = null,
)

/** 技能 zip 导入的冲突项。 */
@Serializable
data class SkillImportConflictDto(
    val reason: String = "",
    @SerialName("skill_name") val skillName: String = "",
    @SerialName("suggested_name") val suggestedName: String? = null,
)

/** 技能 zip 导入结果，对应 `POST /api/skills/upload` 与 `/pool/upload-zip`。 */
@Serializable
data class SkillImportResultDto(
    val imported: List<String> = emptyList(),
    val count: Int = 0,
    val enabled: Boolean = true,
    val conflicts: List<SkillImportConflictDto> = emptyList(),
)

/** 技能广播的冲突项。工作区名与建议名都可能为空。 */
@Serializable
data class BroadcastConflictDto(
    val reason: String = "",
    @SerialName("skill_name") val skillName: String = "",
    @SerialName("suggested_name") val suggestedName: String? = null,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("workspace_name") val workspaceName: String = "",
    val name: String = "",
)

@Serializable
data class BroadcastDownloadDto(
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("workspace_name") val workspaceName: String = "",
    val name: String = "",
)

/** 技能广播结果。全有或全无：有冲突时 `downloaded` 为空。 */
@Serializable
data class BroadcastResultDto(
    val downloaded: List<BroadcastDownloadDto> = emptyList(),
    val conflicts: List<BroadcastConflictDto> = emptyList(),
)

/** 技能中心安装任务。 */
@Serializable
data class HubInstallTaskDto(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("bundle_url") val bundleUrl: String = "",
    val version: String = "",
    val enable: Boolean = true,
    val status: String = "pending",
    val error: String? = null,
    val result: SkillImportResultDto? = null,
) {
    val isTerminal: Boolean
        get() = status == "completed" || status == "failed" || status == "cancelled"
}

/** 一次导入任务的快照。 */
@Serializable
data class PortabilityJobDto(
    @SerialName("job_id") val jobId: String = "",
    @SerialName("agent_id") val agentId: String = "",
    val state: String = "scanning",
    val seq: Int = 0,
    val providers: List<PortabilityProviderDto> = emptyList(),
    val logs: List<String> = emptyList(),
) {
    val isTerminal: Boolean
        get() = state == "completed" || state == "completed_with_issues" ||
            state == "failed" || state == "interrupted" || state == "cancelled"
}

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

/**
 * 令牌校验结果，对应 `GET /api/auth/verify`。
 *
 * 服务端返回 `{"valid": true, "username": "…"}`；未启用登录时 `username` 是空串。
 * `username` 是**唯一可信的身份来源**，令牌载荷里解出的 subject 只用于展示。
 */
@Serializable
data class VerifyTokenDto(
    val valid: Boolean = false,
    val username: String? = null,
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
 * 配套插件的更新状态，对应 `GET /api/plugins/qwenpaw-node-bridge/update/status`。
 *
 * 字段全部给默认值：插件版本较旧时该端点返回 404，客户端据此当作「没有更新能力」，
 * 而不是把缺字段当成故障。[notifyClient] 由插件决定要不要打扰手机端——
 * 插件自己可能选择只在服务端日志里提示。
 */
@Serializable
data class BridgeUpdateStatusDto(
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("latest_url") val latestUrl: String = "",
    @SerialName("notify_client") val notifyClient: Boolean = false,
    /** 插件回的是 epoch 秒整数；用 JsonElement 兼容旧测试/字符串形式。 */
    @SerialName("checked_at") val checkedAt: JsonElement? = null,
)

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
 * 供应商连通性测试的结果，对应 `POST /api/models/{provider_id}/test`。
 *
 * **测试失败仍是 HTTP 200**，靠 [success] 区分（实测 `lmstudio` 在未启动时返回
 * `success=false` + `message` + 200）。因此界面必须先看 `success`，不能靠状态码。
 * 除 [success] 外全部可空：实测成功路径其余字段都是 `null`。
 */
@Serializable
data class TestConnectionDto(
    val success: Boolean = false,
    val message: String = "",
    val status: String? = null,
    @SerialName("http_status") val httpStatus: Int? = null,
    val retryable: Boolean? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    val verification: String? = null,
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
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = false,
    @SerialName("supports_oauth") val supportsOauth: Boolean = false,
    @SerialName("oauth_connected") val oauthConnected: Boolean = false,
    @SerialName("auth_mode") val authMode: String? = null,
    @SerialName("freeze_url") val freezeUrl: Boolean = false,
    @SerialName("hidden_model_ids") val hiddenModelIds: List<String> = emptyList(),
    @SerialName("custom_headers") val customHeaders: Map<String, String>? = null,
    @SerialName("generate_kwargs") val generateKwargs: JsonObject? = null,
    @SerialName("require_api_key") val requireApiKey: Boolean? = null,
    @SerialName("support_model_discovery") val supportModelDiscovery: Boolean = false,
    @SerialName("support_connection_check") val supportConnectionCheck: Boolean = false,
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
    /** 后端类型；`qwenpaw` 之外的值由 harness 插件提供。 */
    val backend: String = "qwenpaw",
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
 * `GET /api/workspace/project-directory/browse-dirs`。
 *
 * 只列目录、不列文件。`show_hidden` 必须为 true：实测在 2.2.2.post1 上，
 * 不传该参数时 `dirs` 可能为空，看起来像「这个目录下什么都没有」。
 * [parent] 在根目录处为 null。
 */
@Serializable
data class BrowseDirsDto(
    val current: String = "",
    val parent: String? = null,
    val dirs: List<BrowseDirDto> = emptyList(),
)

/** 一个可进入的子目录。 */
@Serializable
data class BrowseDirDto(
    val name: String = "",
    val path: String = "",
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

/**
 * 一条原始用量记录，对应 `GET /api/token-usage/details` 的元素。
 *
 * 一行是「日期 × 智能体 × 供应商 × 模型」。**全局**口径：不受 `X-Agent-Id` 过滤，
 * `agent_id` 为 `null` 表示该行早于服务端的智能体归属机制，不能丢弃。
 */
@Serializable
data class TokenUsageRecordDto(
    val date: String = "",
    @SerialName("provider_id") val providerId: String = "",
    val model: String = "",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("call_count") val callCount: Int = 0,
)

/**
 * 智能体统计汇总，对应 `GET /api/agent-stats`。
 *
 * **按请求的工作区过滤**（服务端读 `X-Agent-Id`），与 [TokenUsageDto] 的全局口径不同。
 *
 * `agent_cache_hit_rate` 必须可空：空区间实测返回 `null`（2026-11-14 在 2.2.2.post1 与
 * 2.3.0b2 上均为 `null`），声明成非空 `Double` 会让整页解析失败。
 */
@Serializable
data class AgentStatsSummaryDto(
    @SerialName("total_active_sessions") val totalActiveSessions: Int = 0,
    @SerialName("total_messages") val totalMessages: Int = 0,
    @SerialName("total_user_messages") val totalUserMessages: Int = 0,
    @SerialName("total_assistant_messages") val totalAssistantMessages: Int = 0,
    @SerialName("total_prompt_tokens") val totalPromptTokens: Long = 0,
    @SerialName("total_completion_tokens") val totalCompletionTokens: Long = 0,
    @SerialName("total_llm_calls") val totalLlmCalls: Int = 0,
    @SerialName("total_tool_calls") val totalToolCalls: Int = 0,
    @SerialName("by_date") val byDate: List<DailyStatsDto> = emptyList(),
    @SerialName("channel_stats") val channelStats: List<ChannelStatsDto> = emptyList(),
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("agent_prompt_tokens") val agentPromptTokens: Long = 0,
    @SerialName("agent_completion_tokens") val agentCompletionTokens: Long = 0,
    @SerialName("agent_llm_calls") val agentLlmCalls: Int = 0,
    @SerialName("agent_cache_read_tokens") val agentCacheReadTokens: Long = 0,
    @SerialName("agent_cache_eligible_input_tokens") val agentCacheEligibleInputTokens: Long = 0,
    @SerialName("agent_cache_hit_rate") val agentCacheHitRate: Double? = null,
)

/** `AgentStatsSummaryDto.by_date` 的元素，共 14 个字段。 */
@Serializable
data class DailyStatsDto(
    val date: String = "",
    val chats: Int = 0,
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("user_messages") val userMessages: Int = 0,
    @SerialName("assistant_messages") val assistantMessages: Int = 0,
    @SerialName("total_messages") val totalMessages: Int = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("llm_calls") val llmCalls: Int = 0,
    @SerialName("tool_calls") val toolCalls: Int = 0,
    @SerialName("agent_prompt_tokens") val agentPromptTokens: Long = 0,
    @SerialName("agent_completion_tokens") val agentCompletionTokens: Long = 0,
    @SerialName("agent_llm_calls") val agentLlmCalls: Int = 0,
    @SerialName("agent_cache_read_tokens") val agentCacheReadTokens: Long = 0,
)

/** 按渠道分组的会话与消息计数。 */
@Serializable
data class ChannelStatsDto(
    val channel: String = "",
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("user_messages") val userMessages: Int = 0,
    @SerialName("assistant_messages") val assistantMessages: Int = 0,
    @SerialName("total_messages") val totalMessages: Int = 0,
)

/**
 * 单日调用趋势，对应 `GET /api/agent-stats/llm-tool-trend` 的元素。
 *
 * 该端点**跨智能体汇总**、返回裸数组；实测服务端已把整个区间补齐零值
 * （93 天区间返回 93 条），客户端仍保留一次幂等补齐以防版本变化。
 */
@Serializable
data class LlmToolDailyDto(
    val date: String = "",
    @SerialName("agent_llm_calls") val agentLlmCalls: Int = 0,
    @SerialName("tool_calls") val toolCalls: Int = 0,
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

/** `GET|PUT /api/settings/language`。合法值见 [io.qwenpaw.node.ui.settings.SERVER_LANGUAGES]。 */
@Serializable
data class ServerLanguageDto(
    val language: String = "en",
)

/** 一条内置工具防护规则，对应 `GET /api/config/security/tool-guard/builtin-rules`。 */
@Serializable
data class ToolGuardRuleDto(
    val id: String = "",
    val tools: List<String> = emptyList(),
    val params: List<String> = emptyList(),
    val category: String = "",
    val severity: String = "",
    val patterns: List<String> = emptyList(),
    @SerialName("exclude_patterns") val excludePatterns: List<String> = emptyList(),
    val description: String = "",
    val remediation: String = "",
)

/** 文件防护设置，对应 `GET|PUT /api/config/security/file-guard`。 */
@Serializable
data class FileGuardDto(
    val enabled: Boolean = false,
    val paths: List<String> = emptyList(),
    @SerialName("allow_preview_outside_workspace")
    val allowPreviewOutsideWorkspace: Boolean = false,
)

/**
 * 沙箱状态，对应 `GET|PUT /api/config/security/sandbox`。
 *
 * `enabled` 是配置值，`effective` 是当前机器上真正生效的结果；两者可能不同，
 * 例如宿主机缺少所需能力时 `reason` 会给出原因。
 */
@Serializable
data class SandboxStatusDto(
    val enabled: Boolean = false,
    val effective: Boolean = false,
    val reason: String? = null,
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

// ---------------- 工作区文件 ----------------

/**
 * `GET /api/workspace/tree` 的条目。
 *
 * `previewKind` 由服务端判定（`directory` / `text` / `binary` / `image`），
 * 界面据此决定能否预览，不要用扩展名另判一套——实测 `history.db` 的
 * `file-content` 会返回带 NUL 的字符串，按扩展名猜会把数据库当文本渲染。
 */
@Serializable
data class WorkspaceEntryDto(
    val name: String = "",
    val path: String = "",
    val kind: String = "",
    @SerialName("preview_kind") val previewKind: String? = null,
    val size: Long? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
)

/** `GET /api/workspace/tree`。游标原样回传给 `cursor`，`has_more=false` 即到底。 */
@Serializable
data class WorkspaceTreeDto(
    val directory: String = "",
    val entries: List<WorkspaceEntryDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * `GET /api/workspace/file-content`。
 *
 * `limit` 是字节上限，服务端会把 [nextOffset] 落到 UTF-8 边界，因此它可能略大于
 * 请求的 `limit`。分块内容可直接拼接，实测中文不会在边界处损坏。
 */
@Serializable
data class WorkspaceFileContentDto(
    val content: String = "",
    val encoding: String? = null,
    val eof: Boolean = false,
    val etag: String? = null,
    val limit: Long = 0,
    @SerialName("next_offset") val nextOffset: Long = 0,
    val offset: Long = 0,
    val path: String = "",
    val truncated: Boolean = false,
)

/**
 * `PUT /api/workspace/file-content` 的响应。
 *
 * 保存成功后服务端返回新的 [etag]；下一次保存必须用它，否则会 409。
 */
@Serializable
data class WorkspaceSaveResultDto(
    val etag: String = "",
    val path: String = "",
    val size: Long = 0,
)

/** `POST /api/workspace/file-upload` 响应里的单个文件。 */
@Serializable
data class WorkspaceUploadedFileDto(
    val name: String = "",
    val path: String = "",
    val size: Long? = null,
    /** `uploaded` 或 `skipped`（服务端按 `conflict=skip` 跳过同名文件）。 */
    val status: String = "",
)

/** `POST /api/workspace/file-upload`。本轮每个请求只带一个文件，列表通常只有一条。 */
@Serializable
data class WorkspaceUploadResultDto(
    val files: List<WorkspaceUploadedFileDto> = emptyList(),
)

/** `GET /api/settings/upload-limit`。值为 null 表示服务端不限制。 */
@Serializable
data class UploadLimitDto(
    @SerialName("upload_max_size_mb") val uploadMaxSizeMb: Long? = null,
)

/**
 * `POST /api/workspace/transcribe`。表单字段名是 `file`，成功时只回 `{"text": "..."}`。
 *
 * 失败有三套形状（实测）：`disabled` 与不支持的扩展名是 400 + 对象 detail
 * （`{"code","message"}`），provider 不可用是 500 + 字符串 detail。判定留在
 * `voice/ServerTranscriber` 一处，这里只描述成功形状。
 */
@Serializable
data class TranscribeResultDto(
    val text: String = "",
)

/**
 * `GET /api/workspace/transcription-provider-type`。
 *
 * 取值只有三个：`disabled` / `whisper_api` / `local_whisper`。
 * 这是「转写开关」本身，不是可用 provider 清单。
 */
@Serializable
data class TranscriptionProviderTypeDto(
    @SerialName("transcription_provider_type") val providerType: String = "disabled",
)

/** `GET /api/workspace/transcription-providers` 里的单个 provider。 */
@Serializable
data class TranscriptionProviderDto(
    val id: String = "",
    val name: String = "",
    val available: Boolean = true,
)

/**
 * `GET /api/workspace/transcription-providers`。
 *
 * 这不等于「能转写」：`providers` 列的是**有能力**转写的 provider（服务端按凭据判定），
 * 而真正生效的是 `configured_provider_id`。实测 `whisper_api` + 空
 * `configured_provider_id` 时 `transcribe` 返回 500，因此客户端不能只看 provider 数量。
 */
@Serializable
data class TranscriptionProvidersDto(
    val providers: List<TranscriptionProviderDto> = emptyList(),
    @SerialName("configured_provider_id") val configuredProviderId: String = "",
)

/** `GET /api/workspace/local-whisper-status`。两项依赖都装齐才算可用。 */
@Serializable
data class LocalWhisperStatusDto(
    val available: Boolean = false,
    @SerialName("ffmpeg_installed") val ffmpegInstalled: Boolean = false,
    @SerialName("whisper_installed") val whisperInstalled: Boolean = false,
)

/**
 * `GET /api/workspace/memory` 的元素。
 *
 * `path` 是服务端绝对路径，只用于展示或排错；读取时用 [filename]（相对记忆根，
 * 可含子目录）拼到 `/api/workspace/memory/` 后面。
 */
@Serializable
data class WorkspaceMemoryFileDto(
    val filename: String = "",
    val path: String = "",
    val size: Long? = null,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("modified_time") val modifiedTime: String? = null,
)

/** `GET /api/workspace/memory/{md_path}`。 */
@Serializable
data class WorkspaceMemoryContentDto(
    val content: String = "",
)

// ---------------- 备份 ----------------

/**
 * 备份范围。
 *
 * 默认值与服务端 `BackupScope` 一致：不包含密钥。恢复时密钥是否可行
 * 取备份创建时的选择，界面不能默认打开这一项。
 */
@Serializable
data class BackupScopeDto(
    @SerialName("include_agents") val includeAgents: Boolean = true,
    @SerialName("include_global_config") val includeGlobalConfig: Boolean = true,
    @SerialName("include_secrets") val includeSecrets: Boolean = false,
    @SerialName("include_skill_pool") val includeSkillPool: Boolean = true,
)

/** `GET /api/backups` 的元素。列表接口把 `signature` 置空。 */
@Serializable
data class BackupMetaDto(
    val id: String,
    val name: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val version: String = "1",
    val scope: BackupScopeDto = BackupScopeDto(),
    @SerialName("agent_count") val agentCount: Int = 0,
    @SerialName("qwenpaw_version") val qwenpawVersion: String = "",
    /**
     * 信任标记。`null` 表示未签名或状态未知，`false` 为本机签名，
     * `true` 为导入时曾显式信任过。
     */
    @SerialName("accepted_via_trust") val acceptedViaTrust: Boolean? = null,
)

/** 备份详情里的单智能体统计。 */
@Serializable
data class BackupAgentStatsDto(
    val files: Int = 0,
    val size: Long = 0,
)

/**
 * `GET /api/backups/{id}`。
 *
 * 与 [BackupMetaDto] 的重复字段是刻意的：服务端的 `BackupDetail` 继承
 * `BackupMeta`，Kotlin 侧用两个独立类比「继承 + 委托」直白，列表与详情
 * 也不必互相牵连。
 */
@Serializable
data class BackupDetailDto(
    val id: String,
    val name: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val version: String = "1",
    val scope: BackupScopeDto = BackupScopeDto(),
    @SerialName("agent_count") val agentCount: Int = 0,
    @SerialName("qwenpaw_version") val qwenpawVersion: String = "",
    @SerialName("accepted_via_trust") val acceptedViaTrust: Boolean? = null,
    @SerialName("workspace_stats") val workspaceStats: Map<String, BackupAgentStatsDto> = emptyMap(),
)

/** `POST /api/backups/jobs` 的请求体。`agents` 必须是显式列表。 */
@Serializable
data class CreateBackupRequestDto(
    val name: String,
    val description: String = "",
    val scope: BackupScopeDto = BackupScopeDto(),
    val agents: List<String> = emptyList(),
)

/**
 * 备份任务快照。
 *
 * `status` 取值为 `pending|running|cancel_requested|completed|failed|cancelled`，
 * `phase` 为 `preparing|agents|finalizing`。SSE 每一帧都是完整快照，不是增量。
 */
@Serializable
data class BackupJobSnapshotDto(
    @SerialName("job_id") val jobId: String = "",
    @SerialName("backup_id") val backupId: String = "",
    val status: String = "",
    val phase: String = "",
    val percent: Int = 0,
    @SerialName("current_agent") val currentAgent: String? = null,
    @SerialName("agent_index") val agentIndex: Int = 0,
    @SerialName("total_agents") val totalAgents: Int = 0,
    val result: BackupMetaDto? = null,
    val error: String? = null,
) {
    /** 任务已经结束，不再产生新帧。 */
    val isTerminal: Boolean
        get() = status == "completed" || status == "failed" || status == "cancelled"
}

/** `POST /api/backups/{id}/restore` 的请求体。 */
@Serializable
data class RestoreBackupRequestDto(
    val mode: String = "custom",
    @SerialName("include_agents") val includeAgents: Boolean = true,
    @SerialName("agent_ids") val agentIds: List<String> = emptyList(),
    @SerialName("include_global_config") val includeGlobalConfig: Boolean = true,
    @SerialName("include_secrets") val includeSecrets: Boolean = false,
    @SerialName("include_skill_pool") val includeSkillPool: Boolean = true,
    @SerialName("default_workspace_dir") val defaultWorkspaceDir: String? = null,
    @SerialName("preserve_local_protected_config") val preserveLocalProtectedConfig: Boolean? = null,
    @SerialName("trust_mode") val trustMode: String? = null,
)

/** `POST /api/backups/{id}/restore` 的响应。 */
@Serializable
data class RestoreBackupResponseDto(
    val ok: Boolean = false,
    @SerialName("preserved_local_keys") val preservedLocalKeys: List<String> = emptyList(),
)

/** 批量删除结果里失败的条目。字段形状服务端未固定，未知字段一律忽略。 */
@Serializable
data class DeleteBackupFailureDto(
    val id: String = "",
    val error: String? = null,
)

/** `POST /api/backups/delete`。 */
@Serializable
data class DeleteBackupsResultDto(
    val deleted: List<String> = emptyList(),
    val failed: List<DeleteBackupFailureDto> = emptyList(),
)

// ---------------- 检查点 ----------------

/** `GET /api/workspace/checkpoints/status`。 */
@Serializable
data class CheckpointStatusDto(
    @SerialName("auto_enabled") val autoEnabled: Boolean = false,
    @SerialName("has_checkpoints") val hasCheckpoints: Boolean = false,
    @SerialName("workspace_dir") val workspaceDir: String = "",
)

/** 图谱节点。`sha` 是 `commit` 的前 12 位，不起唯一标识作用。 */
@Serializable
data class CheckpointNodeDto(
    val ref: String = "",
    val kind: String = "",
    @SerialName("session_key") val sessionKey: String = "",
    val name: String = "",
    val commit: String = "",
    val sha: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
    val subject: String = "",
    val query: String? = null,
    val channel: String = "unknown",
    @SerialName("restore_index") val restoreIndex: Int? = null,
    @SerialName("parent_commit") val parentCommit: String? = null,
    @SerialName("is_head") val isHead: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("session_title") val sessionTitle: String = "",
)

/** 图谱里的会话目录。`archived` 的会话仍会出现，界面按原样展示。 */
@Serializable
data class CheckpointSessionDto(
    @SerialName("session_key") val sessionKey: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("user_id") val userId: String = "",
    val channel: String = "",
    val title: String = "",
    val archived: Boolean = false,
)

/** 图谱摘要。 */
@Serializable
data class CheckpointSummaryDto(
    val total: Int = 0,
    val auto: Int = 0,
    val snapshots: Int = 0,
    val safety: Int = 0,
    val heads: Int = 0,
)

/** `GET /api/workspace/checkpoints/graph`。节点数达到 `limit` 时 `truncated` 为真。 */
@Serializable
data class CheckpointGraphDto(
    val nodes: List<CheckpointNodeDto> = emptyList(),
    val sessions: List<CheckpointSessionDto> = emptyList(),
    val summary: CheckpointSummaryDto = CheckpointSummaryDto(),
    val truncated: Boolean = false,
)

/** `POST /api/workspace/checkpoints/snapshot` 的请求体。 */
@Serializable
data class SnapshotRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String = "",
    val channel: String = "console",
    val name: String = "",
)

/** 快照创建结果。 */
@Serializable
data class SnapshotResultDto(
    val ref: String = "",
    val commit: String = "",
    val tree: String = "",
    @SerialName("parent_commit") val parentCommit: String? = null,
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
)

/**
 * 回滚请求。
 *
 * 服务端的字段名是 `commit`（不是 `target`），且 `session_id` 必填；
 * 回滚范围由 `include_memory` / `include_files` 决定，`include_files=true`
 * 时 `files` 不能为空。
 */
@Serializable
data class RestoreCheckpointRequestDto(
    val commit: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String = "",
    val channel: String = "console",
    @SerialName("include_memory") val includeMemory: Boolean = false,
    @SerialName("include_files") val includeFiles: Boolean = false,
    val files: List<String> = emptyList(),
)

/** 回滚预览与执行共用。tuple 在 JSON 里是数组。 */
@Serializable
data class RestoreResultDto(
    val target: String = "",
    val commit: String = "",
    @SerialName("restored_paths") val restoredPaths: List<String> = emptyList(),
    @SerialName("pre_restore_ref") val preRestoreRef: String? = null,
    @SerialName("dry_run") val dryRun: Boolean = false,
    @SerialName("include_memory") val includeMemory: Boolean = false,
    @SerialName("include_files") val includeFiles: Boolean = false,
    @SerialName("deleted_paths") val deletedPaths: List<String> = emptyList(),
    @SerialName("file_paths") val filePaths: List<String> = emptyList(),
)

/** `GET/PATCH /api/workspace/checkpoints/gc/settings`。 */
@Serializable
data class GcSettingsDto(
    @SerialName("gc_keep_count") val gcKeepCount: Int = 20,
    @SerialName("gc_keep_days") val gcKeepDays: Int = 30,
    @SerialName("pre_restore_retention_days") val preRestoreRetentionDays: Int = 7,
)

/** `POST /api/workspace/checkpoints/gc` 与 `/gc/preview` 的请求体。 */
@Serializable
data class GcRequestDto(
    val compact: Boolean = false,
    @SerialName("keep_count") val keepCount: Int? = null,
    @SerialName("keep_days") val keepDays: Int? = null,
    @SerialName("pre_restore_days") val preRestoreDays: Int? = null,
)

/** 清理结果。 */
@Serializable
data class GcResultDto(
    @SerialName("deleted_refs") val deletedRefs: List<String> = emptyList(),
    @SerialName("kept_refs") val keptRefs: List<String> = emptyList(),
    @SerialName("dry_run") val dryRun: Boolean = false,
)

/** `DELETE /api/workspace/checkpoints`。 */
@Serializable
data class ResetCheckpointsDto(
    val reset: Boolean = false,
    @SerialName("auto_enabled") val autoEnabled: Boolean = false,
)

// ---------------- 技能 ----------------

/**
 * 工作区技能，对应 `GET /api/skills` 的元素。
 *
 * 列表里**已启用的技能也在**，靠 `enabled` 区分；这与「技能池只有未启用的」直觉相反。
 * `last_updated` 实测是带 `Z` 的 ISO 串（`2026-11-01T01:08:08.599528Z`），
 * 因此展示前要按 UTC 折算本地。
 */
@Serializable
data class SkillDto(
    val name: String = "",
    val description: String = "",
    @SerialName("version_text") val versionText: String = "",
    val source: String = "customized",
    val emoji: String = "",
    val enabled: Boolean = false,
    val channels: List<String> = emptyList(),
    val preload: Boolean = false,
    val tags: List<String> = emptyList(),
    @SerialName("last_updated") val lastUpdated: String = "",
)

/**
 * 技能详情，对应 `GET /api/skills/{name}`。
 *
 * 与列表的差别只有四项，因此单独声明而不是继承：[requirements] 只在详情里有，
 * 列表里的 `last_updated` 与详情同形。`content` 就是 `SKILL.md` 全文，
 * 保存时原样回传，不做任何裁剪。
 */
@Serializable
data class SkillDetailDto(
    val name: String = "",
    val description: String = "",
    @SerialName("version_text") val versionText: String = "",
    val source: String = "customized",
    val emoji: String = "",
    val enabled: Boolean = false,
    val channels: List<String> = emptyList(),
    val preload: Boolean = false,
    val tags: List<String> = emptyList(),
    @SerialName("last_updated") val lastUpdated: String = "",
    val content: String = "",
    @SerialName("installed_from") val installedFrom: String = "",
    val requirements: SkillRequirementsDto = SkillRequirementsDto(),
)

/** 技能声明的运行依赖。三项都是字符串列表，缺省时为空。 */
@Serializable
data class SkillRequirementsDto(
    @SerialName("require_bins") val requireBins: List<String> = emptyList(),
    @SerialName("require_envs") val requireEnvs: List<String> = emptyList(),
    @SerialName("require_mcps") val requireMcps: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = requireBins.isEmpty() && requireEnvs.isEmpty() && requireMcps.isEmpty()
}

/**
 * 技能池条目，对应 `GET /api/skills/pool` 的元素。
 *
 * **没有 `enabled`**：开关的对象是「某个工作区里的某个技能」，池内技能本身没有启停状态。
 * `sync_status` 是与各工作区的同步结论（`synced` 等），本版只显示不解释。
 */
@Serializable
data class PoolSkillDto(
    val name: String = "",
    val description: String = "",
    @SerialName("version_text") val versionText: String = "",
    val source: String = "customized",
    val emoji: String = "",
    val external: Boolean = false,
    @SerialName("external_path") val externalPath: String = "",
    @SerialName("sync_status") val syncStatus: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("last_updated") val lastUpdated: String = "",
    @SerialName("auto_sync") val autoSync: Boolean = false,
    @SerialName("auto_update") val autoUpdate: Boolean = false,
)

/** 技能池详情，对应 `GET /api/skills/pool/{name}`。 */
@Serializable
data class PoolSkillDetailDto(
    val name: String = "",
    val description: String = "",
    @SerialName("version_text") val versionText: String = "",
    val source: String = "customized",
    val emoji: String = "",
    val external: Boolean = false,
    @SerialName("external_path") val externalPath: String = "",
    @SerialName("sync_status") val syncStatus: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("last_updated") val lastUpdated: String = "",
    @SerialName("auto_sync") val autoSync: Boolean = false,
    @SerialName("auto_update") val autoUpdate: Boolean = false,
    val content: String = "",
    val config: JsonObject = JsonObject(emptyMap()),
    @SerialName("installed_from") val installedFrom: String = "",
    val requirements: SkillRequirementsDto = SkillRequirementsDto(),
    @SerialName("builtin_language") val builtinLanguage: String = "",
    @SerialName("available_builtin_languages") val availableBuiltinLanguages: List<String> = emptyList(),
    @SerialName("auto_sync_targets") val autoSyncTargets: List<String>? = null,
)

/** 技能池与工作区的枚举：`GET /api/skills/workspaces`。 */
@Serializable
data class SkillWorkspaceDto(
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("agent_name") val agentName: String = "",
    @SerialName("skill_names") val skillNames: List<String> = emptyList(),
)

/** 内置技能的某一种语言。 */
@Serializable
data class BuiltinLanguageDto(
    val language: String = "",
    val description: String = "",
    @SerialName("version_text") val versionText: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val status: String = "",
)

/** 内置来源清单的一条：`GET /api/skills/pool/builtin-sources` 与通知里的条目同形。 */
@Serializable
data class BuiltinSourceDto(
    val name: String = "",
    val description: String = "",
    @SerialName("version_text") val versionText: String = "",
    @SerialName("current_version_text") val currentVersionText: String = "",
    @SerialName("current_source") val currentSource: String = "",
    @SerialName("current_language") val currentLanguage: String = "",
    @SerialName("available_languages") val availableLanguages: List<String> = emptyList(),
    val languages: Map<String, BuiltinLanguageDto> = emptyMap(),
    val status: String = "",
)

@Serializable
data class BuiltinRemovedDto(
    val name: String = "",
    val description: String = "",
    @SerialName("current_version_text") val currentVersionText: String = "",
    @SerialName("current_source") val currentSource: String = "",
)

/** `GET /api/skills/pool/builtin-notice`。 */
@Serializable
data class BuiltinNoticeDto(
    val fingerprint: String = "",
    @SerialName("has_updates") val hasUpdates: Boolean = false,
    @SerialName("total_changes") val totalChanges: Int = 0,
    @SerialName("actionable_skill_names") val actionableSkillNames: List<String> = emptyList(),
    val added: List<BuiltinSourceDto> = emptyList(),
    val missing: List<BuiltinSourceDto> = emptyList(),
    val updated: List<BuiltinSourceDto> = emptyList(),
    val removed: List<BuiltinRemovedDto> = emptyList(),
)

/** 异步分发任务的句柄。没有进度字段，只有四态。 */
@Serializable
data class PoolDownloadJobDto(
    @SerialName("job_id") val jobId: String = "",
    val state: String = "running",
    val error: String? = null,
    val result: JsonObject? = null,
)

/** 导入内置时 409 里的冲突项。 */
@Serializable
data class BuiltinImportConflictDto(
    @SerialName("skill_name") val skillName: String = "",
    val status: String = "",
    val language: String = "",
    @SerialName("current_version_text") val currentVersionText: String = "",
    @SerialName("source_version_text") val sourceVersionText: String = "",
)

/** `PUT /api/skills/save` 的结果。`mode` 取 `edit` 或 `noop`（内容未变）。 */
@Serializable
data class SaveSkillResultDto(
    val success: Boolean = false,
    val mode: String = "",
    val name: String = "",
)

/**
 * `POST /api/skills` 重名时的 `detail`。
 *
 * 服务端回 409 且 `detail` 是对象，`ApiException.detail` 已被序列化成字符串，
 * 需要再解析回来才能拿到建议名。
 */
@Serializable
data class SkillConflictDto(
    val reason: String = "",
    @SerialName("suggested_name") val suggestedName: String = "",
)

// ---------------- MCP ----------------

/**
 * 一个 MCP 客户端，对应 `GET /api/mcp` 的元素。
 *
 * `tools` 为 `null` 表示「未设白名单，全部启用」，是**语义上的区别而不是缺值**，
 * 因此必须声明成可空列表；声明成非空集合会让整份列表解析失败。
 * `env` 与 `headers` 里可能有凭据，界面不展示。
 */
@Serializable
data class McpClientDto(
    val key: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = false,
    val transport: String = "stdio",
    val url: String = "",
    val headers: Map<String, String>? = null,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String>? = null,
    val cwd: String? = null,
    @SerialName("http_timeout") val httpTimeout: Double? = null,
    val tools: List<String>? = null,
    @SerialName("oauth_status") val oauthStatus: McpOAuthStatusDto? = null,
    @SerialName("access_summary") val accessSummary: McpAccessSummaryDto = McpAccessSummaryDto(),
)

/** MCP 客户端的 OAuth 状态。未配置 OAuth 时服务端给 `null`。 */
@Serializable
data class McpOAuthStatusDto(
    val authorized: Boolean = false,
    @SerialName("expires_at") val expiresAt: Double = 0.0,
    val scope: String = "",
    @SerialName("client_id") val clientId: String = "",
)

/** MCP 访问策略摘要，挂在客户端卡片上。 */
@Serializable
data class McpAccessSummaryDto(
    @SerialName("default_effect") val defaultEffect: String = "deny",
    @SerialName("overrides_count") val overridesCount: Int = 0,
)

/**
 * 一个 MCP 工具，对应 `GET|PUT /api/mcp/tools/{key}` 的元素。
 *
 * `enabled` 是「是否通过白名单」，服务端按卡片里的 `tools` 逐个算出来，
 * 客户端不需要自己再比一遍。`input_schema` 本版不渲染（表单在手机上无法审阅）。
 */
@Serializable
data class McpToolDto(
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    @SerialName("input_schema") val inputSchema: JsonObject? = null,
)

/**
 * MCP 访问策略，对应 `GET|PUT /api/mcp/policy/{key}`。
 *
 * **整读整写**：服务端按这份 payload 重建「控制台托管」的规则，
 * `client_overrides` 与 `tool_overrides` 若不原样带回就会被清掉。
 * 本版的可编辑面只有 [defaultEffect] 与 [toolDefaults] 两处，其余原样回传。
 */
@Serializable
data class McpPolicyDto(
    @SerialName("default_effect") val defaultEffect: String = "deny",
    @SerialName("client_overrides") val clientOverrides: List<McpAccessRuleDto> = emptyList(),
    @SerialName("tool_defaults") val toolDefaults: List<McpToolDefaultDto> = emptyList(),
    @SerialName("tool_overrides") val toolOverrides: List<McpToolOverrideDto> = emptyList(),
    @SerialName("unmanaged_rules_count") val unmanagedRulesCount: Int = 0,
)

/**
 * 一条来源与对象维度的规则。
 *
 * 五维分别是 `source_type`（实测只有 `channel`）、`source_value`、`subject_type`
 * （`all` / `user`）、`subject_value`、`effect`。规则表在手机上无法审阅，本版只回传。
 */
@Serializable
data class McpAccessRuleDto(
    @SerialName("source_type") val sourceType: String = "channel",
    @SerialName("source_value") val sourceValue: String = "",
    @SerialName("subject_type") val subjectType: String = "all",
    @SerialName("subject_value") val subjectValue: String = "",
    val effect: String = "deny",
)

/** 单个工具的默认效果。 */
@Serializable
data class McpToolDefaultDto(
    @SerialName("tool_name") val toolName: String = "",
    val effect: String = "deny",
)

/** 单个工具在某个来源/对象下的效果，比 [McpAccessRuleDto] 多一个工具名。 */
@Serializable
data class McpToolOverrideDto(
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("source_type") val sourceType: String = "channel",
    @SerialName("source_value") val sourceValue: String = "",
    @SerialName("subject_type") val subjectType: String = "all",
    @SerialName("subject_value") val subjectValue: String = "",
    val effect: String = "deny",
)

// ---------------- 市场与插件目录 ----------------

/** 技能市场的一个来源，对应 `GET /api/market/providers`。 */
@Serializable
data class MarketProviderDto(
    val key: String = "",
    val label: String = "",
    val available: Boolean = true,
    /** 不可用时的原因，服务端会给英文原文（例如缺少哪些环境变量）。 */
    val reason: String? = null,
    @SerialName("supports_browse") val supportsBrowse: Boolean = true,
)

/** 技能市场的一个分类，对应 `GET /api/market/categories?lang=zh`。 */
@Serializable
data class MarketCategoryDto(
    val id: String = "",
    val label: String = "",
)

/**
 * 一条市场搜索结果。
 *
 * `stats` 实测为 `null`，只有部分来源会填；界面不能假设它有键。
 * `source_url` 是本版唯一的落地动作：用系统浏览器打开。
 */
@Serializable
data class MarketResultDto(
    val source: String = "",
    @SerialName("source_label") val sourceLabel: String? = null,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("source_url") val sourceUrl: String = "",
    val version: String? = null,
    val author: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val stats: Map<String, JsonElement>? = null,
)

/** 某个来源的分页信息。 */
@Serializable
data class ProviderPageInfoDto(
    @SerialName("has_more") val hasMore: Boolean = false,
    val total: Int = 0,
)

/** 一个来源的失败原因。`provider` 是来源 key，客户端按 key 换成显示名。 */
@Serializable
data class MarketSearchErrorDto(
    val provider: String = "",
    val message: String = "",
)

/** `POST /api/market/search` 的响应。 */
@Serializable
data class MarketSearchResponseDto(
    val results: List<MarketResultDto> = emptyList(),
    val errors: List<MarketSearchErrorDto> = emptyList(),
    @SerialName("by_provider") val byProvider: Map<String, ProviderPageInfoDto> = emptyMap(),
)

/**
 * 插件目录，对应 `GET /api/plugins/catalog`。
 *
 * **CDN 拉不到时仍回 200**，此时 `plugins` 为空、`error` 非空。
 * 界面必须优先看 `error`，否则会把「取不到」显示成「没有插件」。
 */
@Serializable
data class PluginCatalogDto(
    @SerialName("updated_at") val updatedAt: String? = null,
    val plugins: List<PluginCatalogEntryDto> = emptyList(),
    val error: String? = null,
)

/** 插件目录中的一个条目。`installed_version` 在未安装时为 `null`。 */
@Serializable
data class PluginCatalogEntryDto(
    val id: String = "",
    @SerialName("plugin_id") val pluginId: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("description_i18n") val descriptionI18n: Map<String, String>? = null,
    val version: String = "",
    val author: String = "",
    /** 插件类别，实测取值如 `apps`。 */
    val kind: String = "",
    val size: String = "",
    @SerialName("install_url") val installUrl: String = "",
    val installed: Boolean = false,
    @SerialName("installed_version") val installedVersion: String? = null,
    @SerialName("upgrade_available") val upgradeAvailable: Boolean = false,
)

/**
 * 在线插件市场（`platform.agentscope.io`）的一个条目。
 *
 * 该接口由服务端透传上游 JSON，外层是 `{success, message, data:{total, plugins}}`。
 * 字段名与插件目录完全不同，因此单独一组 DTO，不复用 [PluginCatalogEntryDto]。
 */
@Serializable
data class PluginMarketEntryDto(
    val id: String = "",
    val developer: String = "",
    val owner: String = "",
    val version: String = "",
    val downloads: Long = 0,
    val locales: Map<String, PluginMarketLocaleDto>? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("view_count") val viewCount: Long = 0,
    @SerialName("details_url") val detailsUrl: String = "",
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("qwenpaw_compat_labels") val compatLabels: List<String> = emptyList(),
)

/** 在线插件市场的本地化字段。`en` 与 `zh` 实测都有，但都可能缺。 */
@Serializable
data class PluginMarketLocaleDto(
    val description: String = "",
    val category: String = "",
)

/** 在线插件市场的外层响应。 */
@Serializable
data class PluginMarketResponseDto(
    val success: Boolean = false,
    val message: String = "",
    val data: PluginMarketDataDto = PluginMarketDataDto(),
)

/** 在线插件市场的 `data` 段。 */
@Serializable
data class PluginMarketDataDto(
    val total: Int = 0,
    val plugins: List<PluginMarketEntryDto> = emptyList(),
)

// ---------- 访问控制 ----------

/** 白/黑名单中的一项。聊天渠道用 username，邮件用 display_name。 */
@Serializable
data class AclUserInfoDto(
    val remark: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
)

/** 一条待审批记录。聊天渠道用 user_id/channel，邮件用 agent_id/address。 */
@Serializable
data class AclPendingDto(
    @SerialName("user_id") val userId: String = "",
    val channel: String = "",
    /** 服务端给浮点秒。 */
    val timestamp: Double = 0.0,
    @SerialName("first_message") val firstMessage: String = "",
    val remark: String = "",
    val username: String = "",
    @SerialName("agent_id") val agentId: String = "",
    /** 聊天渠道为空；邮件用 sender_address。 */
    val address: String = "",
    @SerialName("sender_address") val senderAddress: String = "",
    /** 邮件 pending 的主题与正文预览，聊天为空。 */
    val subject: String = "",
    @SerialName("body_preview") val bodyPreview: String = "",
    @SerialName("display_name") val displayName: String = "",
) {
    /** 统一后的用户标识：聊天是 user_id，邮件是地址。 */
    val effectiveAddress: String get() = address.ifBlank { senderAddress }

    /** 统一后的首条内容：聊天是 first_message，邮件是主题 + 正文预览。 */
    val effectiveFirstMessage: String
        get() = firstMessage.ifBlank {
            listOf(subject, bodyPreview).filter { it.isNotBlank() }.joinToString("\n")
        }

    /** 当前 tab 下这一条的唯一键。 */
    val key: String get() = "$agentId|$channel|${userId.ifBlank { effectiveAddress }}"

    /** 界面上的人名：优先用户名/显示名，最后退回 user_id 或地址。 */
    val label: String
        get() = username.ifBlank { displayName }.ifBlank { userId }.ifBlank { effectiveAddress }
}

/** 一个渠道（聊天）或一个智能体（邮件）下的三份名单。 */
@Serializable
data class AclChannelDto(
    val whitelist: Map<String, AclUserInfoDto> = emptyMap(),
    val blacklist: Map<String, AclUserInfoDto> = emptyMap(),
    val pending: List<AclPendingDto> = emptyList(),
)

/** 聊天渠道的批量动作条目。 */
data class AclActionEntryDto(
    val channel: String,
    val userId: String,
    val remark: String = "",
    val username: String = "",
)

/** 邮件的批量动作条目。 */
data class MailAclActionEntryDto(
    val agentId: String,
    val address: String,
    val remark: String? = null,
    val displayName: String? = null,
)

/** 邮件待批角标。 */
@Serializable
data class MailAclPendingCountDto(
    val count: Int = 0,
)

/** `GET /api/mail-access-control/agents`。 */
@Serializable
data class MailAclAgentsDto(
    val agents: List<String> = emptyList(),
)

/** 邮件处理暂停；`pause_id` 是恢复时唯一的凭据。 */
@Serializable
data class MailAclPauseDto(
    @SerialName("pause_id") val pauseId: String = "",
    val reason: String = "",
    val count: Int = 0,
    val notified: Boolean = false,
    @SerialName("agent_id") val agentId: String = "",
)

/**
 * 频道二维码，对应 `GET /api/config/channels/{channel}/qrcode`。
 *
 * [qrcodeImg] 是**不带 `data:` 前缀**的 base64 PNG，渲染前要自己拼前缀或直接解码字节。
 * [pollToken] 是轮询状态时唯一的凭据，有时效，离开页面即作废。
 */
@Serializable
data class ChannelQrDto(
    @SerialName("qrcode_img") val qrcodeImg: String = "",
    @SerialName("poll_token") val pollToken: String = "",
)

/**
 * 扫码状态，对应 `GET …/qrcode/status?token=`。
 *
 * 成功值因频道而异（微信 `confirmed`，其余 `success`），判定集中在
 * [io.qwenpaw.node.data.channel.qrOutcome]，不要在界面里各写一份。
 */
@Serializable
data class ChannelQrStatusDto(
    val status: String = "",
    val credentials: JsonObject = JsonObject(emptyMap()),
)

/** 本地模型运行时状态，对应 `GET /api/local-models/server`。 */
@Serializable
data class LocalModelServerDto(
    val available: Boolean = false,
    val installable: Boolean = false,
    val installed: Boolean = false,
    val port: Int? = null,
    @SerialName("model_name") val modelName: String? = null,
    val message: String? = null,
)

/**
 * 可下载的本地模型，对应 `GET /api/local-models/models` 的元素。
 *
 * 服务端返回的是完整模型目录条目，这里只取界面用得到的四项；其余字段由
 * `ignoreUnknownKeys` 丢弃。
 */
@Serializable
data class LocalModelDto(
    val id: String = "",
    val name: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val downloaded: Boolean = false,
    /** 下载来源：`huggingface` / `modelscope` / `auto`。同一模型可能两个来源各一条。 */
    val source: String? = null,
)

/**
 * 下载进度，对应 `GET /api/local-models/models/download` 与
 * `GET /api/local-models/server/download`。
 *
 * 服务端**没有百分比字段**，[totalBytes] 还可能为 null（大小未探明），
 * 两种进度条形态由 [io.qwenpaw.node.data.localmodel.progressFraction] 决定。
 */
@Serializable
data class LocalModelProgressDto(
    val status: String = "",
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("speed_bytes_per_sec") val speedBytesPerSec: Double = 0.0,
    val source: String? = null,
    val error: String? = null,
    @SerialName("local_path") val localPath: String? = null,
)

// ---------------- 0.7.3 管理面 ----------------

/**
 * MCP 客户端的新建与部分更新请求。
 *
 * 所有字段可空：`PUT /api/mcp/{key}` 是部分更新，未写的字段服务端保持原值。
 * 序列化配置是 `explicitNulls = false`，因此默认值 null 不会进请求体。
 * 新建时服务端要求 `name` 非空，由调用方校验。
 */
@Serializable
data class McpClientWriteDto(
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val transport: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val cwd: String? = null,
    @SerialName("http_timeout") val httpTimeout: Double? = null,
)

/** MCP OAuth 启动请求。`url` 必填，其余可选。 */
@Serializable
data class McpOAuthStartDto(
    val url: String,
    val scope: String = "",
    @SerialName("client_id") val clientId: String = "",
    @SerialName("auth_endpoint") val authEndpoint: String = "",
    @SerialName("token_endpoint") val tokenEndpoint: String = "",
)

/** MCP OAuth 启动结果。浏览器打开 [authUrl]，回 App 后用 status 端点轮询。 */
@Serializable
data class McpOAuthStartResultDto(
    @SerialName("auth_url") val authUrl: String = "",
    @SerialName("session_id") val sessionId: String = "",
)

/** 插件列表与详情元素，对应 `GET /api/plugins`。 */
@Serializable
data class PluginListItemDto(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val enabled: Boolean = false,
    val loaded: Boolean = false,
    /** 2.3.0 起才有；旧版本缺省为空串。 */
    val status: String = "",
    /** 元素形状随插件而变，界面只做只读展示，不按固定字段解析。 */
    val diagnostics: List<JsonElement> = emptyList(),
    @SerialName("plugin_type") val pluginType: String = "",
    @SerialName("market_id") val marketId: String? = null,
    @SerialName("installation_origin") val installationOrigin: String? = null,
)

/** 插件配置整段写入。服务端没有读取端点，提交会替换 `config.plugins.<id>`。 */
@Serializable
data class PluginConfigWriteDto(
    val config: JsonObject,
    @SerialName("confirm_legacy") val confirmLegacy: Boolean = false,
    @SerialName("interrupt_tasks") val interruptTasks: Boolean = false,
)

/** 插件启停请求。 */
@Serializable
data class PluginEnabledWriteDto(
    val enabled: Boolean,
    @SerialName("interrupt_tasks") val interruptTasks: Boolean = false,
)

/** 免鉴权地址名单。服务端负责 IPv4/IPv6 规范化与去重。 */
@Serializable
data class AllowNoAuthHostsDto(
    val hosts: List<String> = emptyList(),
)

/** 本地模型配置。`port = null` 表示自动端口。 */
@Serializable
data class LocalModelConfigDto(
    @SerialName("max_context_length") val maxContextLength: Int? = null,
    val port: Int? = null,
    @SerialName("generate_kwargs") val generateKwargs: JsonObject? = null,
)

/** 本地模型运行时更新检查。 */
@Serializable
data class LocalModelServerUpdateDto(
    @SerialName("has_update") val hasUpdate: Boolean = false,
)

/** 浏览器自检的单个检查项。`metadata` 的形状随检查项不同。 */
@Serializable
data class BrowserCheckDto(
    val name: String = "",
    val passed: Boolean = false,
    val status: String = "",
    val code: String = "",
    val message: String = "",
    @SerialName("repair_action") val repairAction: String? = null,
    val metadata: JsonObject? = null,
    /** 仅 contract_drift 可能出现。 */
    val severity: String? = null,
    val detail: String? = null,
)

/** 浏览器自检结果。 */
@Serializable
data class BrowserSelfTestDto(
    val status: String = "",
    @SerialName("checked_at") val checkedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Double = 0.0,
    val checks: List<BrowserCheckDto> = emptyList(),
)

/** 浏览器桥生命周期。 */
@Serializable
data class BrowserBridgeLifecycleDto(
    val connected: Boolean = false,
    @SerialName("connected_since") val connectedSince: String? = null,
)

/** 浏览器控制链路状态，对应 `GET /api/browser/chrome/status`。 */
@Serializable
data class BrowserChromeStatusDto(
    val connected: Boolean = false,
    @SerialName("connected_since") val connectedSince: String? = null,
    @SerialName("extension_version") val extensionVersion: String? = null,
    @SerialName("bridge_lifecycle") val bridgeLifecycle: BrowserBridgeLifecycleDto? = null,
    /** 断开记录是自由形状，界面只显示可识别的字段。 */
    @SerialName("last_extension_disconnect") val lastExtensionDisconnect: JsonObject? = null,
    @SerialName("contract_health") val contractHealth: BrowserCheckDto? = null,
    @SerialName("last_self_test") val lastSelfTest: BrowserSelfTestDto? = null,
    @SerialName("readiness_state") val readinessState: String = "",
    val backend: JsonObject? = null,
)

/** Bridge 读取 NM host 日志的返回。 */
@Serializable
data class BridgeBrowserLogsDto(
    val lines: List<String> = emptyList(),
    val truncated: Boolean = false,
    val path: String? = null,
)

/**
 * 服务端错误 detail 对象的保守映射。
 *
 * 同一状态码下不同端点会返回不同形状：插件启停是 `{id, loaded, clean, ...}`，
 * legacy 钩子是 `{code, legacy_hooks}`，依赖升级是 `{code, operation}`。
 * 解析不出来时调用方回退到服务端原文，不臆造结论。
 */
@Serializable
data class ApiErrorDetailDto(
    val code: String = "",
    val message: String = "",
    val id: String = "",
    val loaded: Boolean = false,
    val clean: Boolean = true,
    val quiescent: Boolean = true,
    val errors: List<String> = emptyList(),
    val unchanged: Boolean = false,
    @SerialName("needs_restart") val needsRestart: Boolean = false,
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean = false,
    @SerialName("legacy_hooks") val legacyHooks: List<String> = emptyList(),
    @SerialName("confirm_legacy") val confirmLegacy: Boolean = false,
    val operation: JsonObject? = null,
)
