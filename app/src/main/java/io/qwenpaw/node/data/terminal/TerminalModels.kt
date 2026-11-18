package io.qwenpaw.node.data.terminal

import kotlinx.serialization.Serializable

/** 终端来源。旧版本存过 `SSH`，读取时迁移为 [SSH_RELAY]。 */
enum class TerminalSource { LOCAL, SSH_RELAY, SSH_DIRECT }

enum class TerminalShell { DEFAULT, BASH, DASH, POWERSHELL, CMD, GIT_BASH }

enum class TerminalPlatform { AUTO, WINDOWS, POSIX }

/** 「+」直接创建终端时使用的默认组合。 */
@Serializable
data class TerminalCreation(
    val source: TerminalSource,
    val shell: TerminalShell = TerminalShell.DEFAULT,
    val profileId: String? = null,
)

enum class SshAuthType { KEY, PASSWORD }

/** 直连 SSH 的一个地址；一个 profile 下可以有多个地址。 */
@Serializable
data class SshAddress(
    val id: String,
    val alias: String = "",
    val host: String,
    val port: Int = 22,
    val user: String,
    val default: Boolean = false,
    val preferOnVpn: Boolean = false,
)

/** 直连 SSH 的非秘密配置。私钥、口令与 passphrase 不在这里。 */
@Serializable
data class SshProfile(
    val addresses: List<SshAddress> = emptyList(),
    val auth: SshAuthType = SshAuthType.KEY,
    /** 直连目标的默认 shell；Windows 直连可在配置页选 PowerShell/命令提示符。 */
    val shell: TerminalShell = TerminalShell.DEFAULT,
    /** 目标是否就是当前 QwenPaw 实例所在主机；是则连接后进入项目目录。 */
    val sameHost: Boolean = true,
    val platform: TerminalPlatform = TerminalPlatform.AUTO,
)

/** 只在本机 EncryptedSharedPreferences 与连接内存中使用，禁止写日志。 */
class SshSecret(
    private val privateKey: String? = null,
    private val passphrase: String? = null,
    private val password: String? = null,
) {
    fun privateKey(): String? = privateKey
    fun passphrase(): String? = passphrase
    fun password(): String? = password

    val hasKey: Boolean get() = !privateKey.isNullOrBlank()
    val hasPassword: Boolean get() = !password.isNullOrBlank()

    override fun toString(): String =
        "SshSecret(key=${hasKey}, passphrase=${!passphrase.isNullOrBlank()}, password=${hasPassword})"
}
