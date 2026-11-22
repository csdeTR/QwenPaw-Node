package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /api/models/model-templates` 的条目。 */
@Serializable
data class ModelTemplateDto(
    val id: String = "",
    val name: String = "",
    @SerialName("model_id") val modelId: String = "",
    @SerialName("provider_id") val providerId: String = "",
)

/** `POST /api/models/{id}/discover`。只取客户端需要展示的字段。 */
@Serializable
data class DiscoverModelsResultDto(
    val success: Boolean = false,
    val message: String = "",
    @SerialName("discovered_count") val discoveredCount: Int = 0,
)

/** `POST /api/models/{provider}/models/{model}/probe-multimodal`。 */
@Serializable
data class ProbeMultimodalDto(
    @SerialName("supports_image") val supportsImage: Boolean? = null,
    @SerialName("supports_video") val supportsVideo: Boolean? = null,
    @SerialName("supports_multimodal") val supportsMultimodal: Boolean? = null,
    @SerialName("image_message") val imageMessage: String = "",
    @SerialName("video_message") val videoMessage: String = "",
)

/** 模型池里的一张模型卡，只取渲染需要的字段。 */
@Serializable
data class PoolModelDto(
    val id: String = "",
    val name: String = "",
    @SerialName("model_type") val modelType: String = "chat",
    @SerialName("is_free") val isFree: Boolean = false,
    @SerialName("is_recommended") val isRecommended: Boolean = false,
    @SerialName("supports_image") val supportsImage: Boolean? = null,
    @SerialName("supports_audio") val supportsAudio: Boolean? = null,
    @SerialName("supports_video") val supportsVideo: Boolean? = null,
    @SerialName("supports_tool_calling") val supportsToolCalling: Boolean? = null,
    @SerialName("max_input_length") val maxInputLength: Long? = null,
    @SerialName("effective_max_input_length") val effectiveMaxInputLength: Long? = null,
    @SerialName("automatic_max_input_length") val automaticMaxInputLength: Long? = null,
    @SerialName("max_input_length_auto_detected") val maxInputLengthAutoDetected: Long? = null,
    @SerialName("max_input_length_configured") val maxInputLengthConfigured: Boolean = false,
    @SerialName("context_length_source") val contextLengthSource: String? = null,
    @SerialName("max_output_length") val maxOutputLength: Long? = null,
    @SerialName("max_output_length_source") val maxOutputLengthSource: String? = null,
    @SerialName("config_overrides") val configOverrides: List<String> = emptyList(),
    @SerialName("thinking_control") val thinkingControl: ThinkingControlDto? = null,
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Long? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("relay_reasoning") val relayReasoning: Boolean? = null,
    @SerialName("generate_kwargs") val generateKwargs: JsonObject? = null,
    @SerialName("thinking_param_style") val thinkingParamStyle: String? = null,
    @SerialName("reasoning_effort_options") val reasoningEffortOptions: List<String> = emptyList(),
    @SerialName("thinking_budget_range") val thinkingBudgetRange: List<Long> = emptyList(),
    @SerialName("requires_paid_confirmation") val requiresPaidConfirmation: Boolean = false,
    @SerialName("remote_missing") val remoteMissing: Boolean = false,
)

@Serializable
data class ModelPoolPageDto(
    val models: List<PoolModelDto> = emptyList(),
    val total: Int = 0,
    @SerialName("selected_count") val selectedCount: Int = 0,
    @SerialName("candidate_count") val candidateCount: Int = 0,
    val families: List<String> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 30,
)

/** 供应商 OAuth 启动结果。 */
@Serializable
data class OAuthStartDto(
    @SerialName("authorize_url") val authorizeUrl: String = "",
    val state: String = "",
    @SerialName("flow_type") val flowType: String = "browser_redirect",
)

@Serializable
data class OAuthStatusDto(
    val status: String = "pending",
    val error: String? = null,
)

/**
 * 把 provider 列表里的模型条目转成模型详情用的形状。
 *
 * 「模型」页签的行来自 `provider.allModels`，不一定在当前模型池分页里；
 * 详情入口必须能用它打开，否则部分行的「设置」会静默无响应。
 */
fun resolveModelDetail(
    id: String,
    pool: List<PoolModelDto>,
    provider: ProviderDto?,
): PoolModelDto? = pool.firstOrNull { it.id == id }
    ?: provider?.allModels?.firstOrNull { it.id == id }?.toPoolModel()

fun ModelSlotDto.toPoolModel(): PoolModelDto = PoolModelDto(
    id = id,
    name = name,
    modelType = modelType,
    isFree = isFree,
    isRecommended = isRecommended,
    supportsImage = supportsImage,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    supportsToolCalling = supportsToolCalling,
    maxInputLength = maxInputLength,
    effectiveMaxInputLength = effectiveMaxInputLength,
    automaticMaxInputLength = automaticMaxInputLength,
    maxInputLengthAutoDetected = maxInputLengthAutoDetected,
    maxInputLengthConfigured = maxInputLengthConfigured,
    contextLengthSource = contextLengthSource,
    maxOutputLength = maxOutputLength,
    maxOutputLengthSource = maxOutputLengthSource,
    configOverrides = configOverrides,
    thinkingControl = thinkingControl,
    thinkingEnabled = thinkingEnabled,
    thinkingBudget = thinkingBudget,
    reasoningEffort = reasoningEffort,
    relayReasoning = relayReasoning,
    generateKwargs = generateKwargs,
    thinkingParamStyle = thinkingParamStyle,
    reasoningEffortOptions = reasoningEffortOptions,
    thinkingBudgetRange = thinkingBudgetRange,
    requiresPaidConfirmation = requiresPaidConfirmation,
    remoteMissing = remoteMissing,
)
