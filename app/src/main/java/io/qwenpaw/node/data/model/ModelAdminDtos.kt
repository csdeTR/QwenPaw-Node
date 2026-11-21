package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("supports_video") val supportsVideo: Boolean? = null,
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
