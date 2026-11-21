package io.qwenpaw.node.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/terminals/status`。 */
@Serializable
data class TerminalStatusDto(
    val enabled: Boolean = false,
    val reason: String? = null,
)

/** 一个服务端终端。`exit_code` 只在退出后出现。 */
@Serializable
data class TerminalDto(
    val id: String,
    val title: String = "",
    val cwd: String = "",
    val exited: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
)

/**
 * `GET /api/terminals/{group}/{id}/output`。
 *
 * [cursor] 是服务端缓冲区里的**字符**游标，不是字节偏移；下次原样回传。
 * [reset] 为真时表示游标已失效（缓冲区只有 256 KB），应按 [cursor] 重读。
 */
@Serializable
data class TerminalOutputDto(
    val data: String = "",
    val cursor: Int = 0,
    val reset: Boolean = false,
    val exited: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
)

/** Git 状态里的一个变更。同一文件可能同时出现在已暂存与未暂存两段。 */
@Serializable
data class GitChangeDto(
    val path: String,
    val status: String,
    val staged: Boolean = false,
)

@Serializable
data class GitStatusDto(
    val branch: String = "",
    val changes: List<GitChangeDto> = emptyList(),
    val ahead: Int = 0,
    val behind: Int = 0,
)

@Serializable
data class GitDiffDto(val diff: String = "")

@Serializable
data class GitCommitDiffDto(
    val diff: String = "",
    val hash: String = "",
)

@Serializable
data class GitLogEntryDto(
    val hash: String,
    val author: String = "",
    val date: String = "",
    val message: String = "",
)

@Serializable
data class GitBranchDto(
    val name: String,
    val current: Boolean = false,
    val remote: Boolean = false,
)

/** `POST /api/workspace/git/checkout`。 */
@Serializable
data class GitCheckoutResultDto(val branch: String = "")

/** `POST /api/workspace/git/discard`。 */
@Serializable
data class GitDiscardResultDto(val discarded: List<String> = emptyList())

/** `POST /api/workspace/git/revert`。 */
@Serializable
data class GitRevertResultDto(
    val reverted: String = "",
    val output: String = "",
)

/** 插件 `/ssh/config` 的脱敏结果；私钥与口令不在这里。 */
@Serializable
data class SshConfigDto(
    val host: String,
    val port: Int = 22,
    val user: String = "",
    val auth: String = "key",
    @SerialName("key_configured") val keyConfigured: Boolean = false,
    @SerialName("key_source") val keySource: String = "file",
)

/** 插件 `/ssh/credential` 的按需凭据。只放内存，不落盘、不写日志。 */
@Serializable
data class SshCredentialDto(
    val auth: String = "key",
    @SerialName("private_key") val privateKey: String? = null,
    val passphrase: String? = null,
    val password: String? = null,
)
