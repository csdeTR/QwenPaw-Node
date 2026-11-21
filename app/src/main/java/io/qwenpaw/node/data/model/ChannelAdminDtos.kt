package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 占用同一 Bot 身份的运行中智能体。 */
@Serializable
data class ChannelConflictAgentDto(
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("agent_name") val agentName: String = "",
)

/** `POST /api/config/channels/{channel}/conflict-check`。 */
@Serializable
data class ChannelConflictDto(
    val conflict: Boolean = false,
    val agents: List<ChannelConflictAgentDto> = emptyList(),
)

/** `GET /api/config/channels/{channel}/health`。 */
@Serializable
data class ChannelHealthDto(
    val channel: String = "",
    val status: String = "",
    val detail: String = "",
)

/** `POST /api/config/channels/{channel}/restart`。 */
@Serializable
data class ChannelRestartDto(
    val channel: String = "",
    val status: String = "",
    val detail: String = "",
)
