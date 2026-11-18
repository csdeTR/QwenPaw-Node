package io.qwenpaw.node.data.terminal

import android.content.Context

/** 首次连接时展示给用户的主机密钥信息。 */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val keyType: String,
)

/**
 * 主机密钥的 TOFU 存储。
 *
 * 键是 `host|port`，值是 `SHA256:...` 指纹。指纹变化时不能自动接受；
 * 用户必须清除旧记录后重新确认。
 */
class SshHostKeys(context: Context) {

    private val prefs = context.getSharedPreferences("qwenpaw_ssh_hostkeys", Context.MODE_PRIVATE)

    fun fingerprint(host: String, port: Int): String? =
        prefs.getString(key(host, port), null)

    fun remember(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    fun forget(host: String, port: Int) {
        // 清除后界面会立刻重连，必须同步落盘，避免重连先读到旧指纹。
        prefs.edit().remove(key(host, port)).commit()
    }

    private fun key(host: String, port: Int): String = "$host|$port"
}
