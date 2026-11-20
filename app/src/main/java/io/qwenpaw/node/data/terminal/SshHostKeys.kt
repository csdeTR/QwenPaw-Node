package io.qwenpaw.node.data.terminal

import android.content.Context
import android.content.SharedPreferences

/** 首次连接时展示给用户的主机密钥信息。 */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val keyType: String,
)

/**
 * 主机密钥的 TOFU 存储，按实例隔离。
 *
 * 键是 `scope|host|port`，`scope` 取实例 id：同一台主机在两个实例下分别记录，
 * 换一个实例连接时不会自动沿用另一个实例确认过的指纹。旧版本按 `host|port`
 * 写入的记录不再读取，升级后首次连接会重新确认一次。
 *
 * 值是 `SHA256:...` 指纹。指纹变化时不能自动接受；用户必须清除旧记录后重新确认。
 */
class SshHostKeys internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE),
    )

    fun fingerprint(scope: String, host: String, port: Int): String? =
        prefs.getString(hostKey(scope, host, port), null)

    fun remember(scope: String, host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(hostKey(scope, host, port), fingerprint).apply()
    }

    fun forget(scope: String, host: String, port: Int) {
        // 清除后界面会立刻重连，必须同步落盘，避免重连先读到旧指纹。
        prefs.edit().remove(hostKey(scope, host, port)).commit()
    }

    private companion object {
        const val PREF_NAME = "qwenpaw_ssh_hostkeys"
    }
}

/** TOFU 记录的键；拆成纯函数便于单测直接核对拼接结果。 */
internal fun hostKey(scope: String, host: String, port: Int): String = "$scope|$host|$port"
