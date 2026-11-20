package io.qwenpaw.node.data.terminal

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次「测试连接」的结果。
 *
 * [address] 非空表示成功，且就是实际连上的那个地址；[message] 是可直接展示的一行结论；
 * [note] 是每次都随结果展示的说明：测试只验证网络与登录，不校验主机密钥。
 */
data class SshTestResult(
    val address: SshAddress?,
    val message: String,
    val note: String = Texts.get(R.string.ssh_test_note),
) {
    val succeeded: Boolean get() = address != null

    companion object {
        fun failure(message: String): SshTestResult = SshTestResult(null, message)
    }
}

/**
 * SSH 直连的「测试连接」。
 *
 * 按 [SshEndpointOrder] 的顺序逐个地址尝试，只完成 TCP 连接与登录，成功后立即断开。
 * **不读写 [SshHostKeys]**：`StrictHostKeyChecking=no` 跳过主机密钥核对，结果不落盘，
 * 因此测试通过只说明网络可达、凭据可用；主机指纹仍由建立终端时的 TOFU 流程确认。
 */
object SshConnectionTester {

    /** 与终端页单次连接的等待上限一致；超时后换下一个地址。 */
    private const val TIMEOUT_MS = 10_000

    /**
     * 依次尝试 [profile] 的地址，返回第一个连上的结果。
     *
     * [vpnActive] 参与 [SshEndpointOrder] 的排序；配置页没有网络状态信息，默认按
     * 「默认地址优先」处理。
     */
    suspend fun test(
        profile: SshProfile,
        secret: SshSecret,
        vpnActive: Boolean = false,
    ): SshTestResult = withContext(Dispatchers.IO) {
        val addresses = SshEndpointOrder.order(profile.addresses, vpnActive)
        if (addresses.isEmpty()) {
            return@withContext SshTestResult.failure(Texts.get(R.string.ssh_error_no_address))
        }
        val auth = authFor(profile.auth, secret)
            ?: return@withContext SshTestResult.failure(
                Texts.get(R.string.ssh_test_error_no_credential),
            )
        val failures = mutableListOf<String>()
        for (address in addresses) {
            try {
                connectOnce(address, auth)
                val port = address.port.takeIf { it in 1..65535 } ?: 22
                return@withContext SshTestResult(
                    address = address,
                    message = Texts.get(
                        R.string.ssh_test_success,
                        "${address.user.trim()}@${address.host.trim()}:$port",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += describe(e)
            }
        }
        SshTestResult.failure(
            if (failures.size == 1) {
                failures.first()
            } else {
                Texts.get(
                    R.string.ssh_error_all_addresses_failed,
                    failures.joinToString(Texts.get(R.string.ssh_error_list_separator)),
                )
            },
        )
    }

    private fun authFor(auth: SshAuthType, secret: SshSecret): SshAuth? = when (auth) {
        SshAuthType.KEY -> secret.privateKey()
            ?.takeIf { it.isNotBlank() }
            ?.let { SshAuth.Key(it, secret.passphrase()) }
        SshAuthType.PASSWORD -> secret.password()
            ?.takeIf { it.isNotBlank() }
            ?.let { SshAuth.Password(it) }
    }

    /** 认证配置与 `SshTransport.connectOne` 保持一致，避免测试通过而终端连不上。 */
    private fun connectOnce(address: SshAddress, auth: SshAuth) {
        val host = address.host.trim()
        val port = address.port.takeIf { it in 1..65535 } ?: 22
        val jsch = JSch()
        if (auth is SshAuth.Key) {
            jsch.addIdentity(
                "qwenpaw-test",
                auth.privateKey.toByteArray(Charsets.UTF_8),
                null,
                auth.passphrase?.toByteArray(Charsets.UTF_8),
            )
        }
        val session = jsch.getSession(address.user.trim(), host, port)
        try {
            session.setConfig("StrictHostKeyChecking", "no")
            // 读超时与连接超时同值：目标只完成 TCP 握手但不再应答 SSH 协议时也要尽快结束。
            session.setTimeout(TIMEOUT_MS)
            when (auth) {
                is SshAuth.Password -> {
                    session.setPassword(auth.password)
                    session.setConfig(
                        "PreferredAuthentications",
                        "password,keyboard-interactive",
                    )
                    session.setUserInfo(KeyboardPassword(auth.password))
                }
                is SshAuth.Key -> {
                    session.setConfig("PreferredAuthentications", "publickey")
                }
            }
            session.connect(TIMEOUT_MS)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun describe(e: Throwable): String {
        val message = e.message.orEmpty()
        val lower = message.lowercase()
        return when {
            lower.contains("auth fail") || lower.contains("auth cancel") ->
                Texts.get(R.string.ssh_error_auth_failed_local)
            lower.contains("timed out") ||
                lower.contains("timeout") ||
                e is java.net.SocketTimeoutException ->
                Texts.get(R.string.ssh_test_error_timeout)
            lower.contains("connection refused") ||
                e is java.net.ConnectException ->
                Texts.get(R.string.ssh_test_error_refused)
            message.contains("Algorithm negotiation fail", true) ->
                Texts.get(R.string.ssh_error_algorithm)
            lower.contains("ed25519") ||
                lower.contains("eddsa") ||
                lower.contains("no such algorithm") ->
                Texts.get(R.string.ssh_error_ed25519)
            else ->
                Texts.get(
                    R.string.ssh_test_error_unknown,
                    message.ifBlank { e.javaClass.simpleName },
                )
        }
    }

    /** 密码认证可能落到 keyboard-interactive，JSch 只从 [UserInfo] 取答案。 */
    private class KeyboardPassword(private val password: String) : UserInfo, UIKeyboardInteractive {

        override fun getPassphrase(): String = ""

        override fun getPassword(): String = password

        override fun promptPassword(message: String?): Boolean = true

        override fun promptPassphrase(message: String?): Boolean = false

        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? = Array(prompt?.size ?: 1) { password }
    }
}
