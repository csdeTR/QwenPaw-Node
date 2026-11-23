package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET/PUT /api/workspace/audio-mode`，取值 `auto` 或 `native`。 */
@Serializable
data class AudioModeDto(
    @SerialName("audio_mode") val audioMode: String = "auto",
)

/** `GET/PUT /api/workspace/transcription-model`，空串表示服务端默认 `whisper-1`。 */
@Serializable
data class TranscriptionModelDto(
    @SerialName("transcription_model") val model: String = "",
)

/**
 * `GET/PUT /api/config/security/sandbox/deny-paths-protection`。
 *
 * 该保护依赖 Windows ACL，其他平台返回 `platform_supported=false`，
 * 此时 [active] 恒为 false，不能当作开关状态使用。
 */
@Serializable
data class DenyPathsProtectionDto(
    val active: Boolean = false,
    @SerialName("protected_paths") val protectedPaths: List<String> = emptyList(),
    @SerialName("failed_paths") val failedPaths: List<String> = emptyList(),
    @SerialName("platform_supported") val platformSupported: Boolean = false,
    val message: String? = null,
)

/** `GET/POST /api/coding-mode`。 */
@Serializable
data class CodingModeDto(
    val enabled: Boolean = false,
    @SerialName("agent_id") val agentId: String? = null,
)

/** 技能批量操作的逐条结果。 */
@Serializable
data class BatchSkillResultDto(
    val success: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class BatchSkillResultsDto(
    val results: Map<String, BatchSkillResultDto> = emptyMap(),
)
