package io.qwenpaw.node.data.terminal

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts

/**
 * shell 切换与进入工作区的命令生成。
 *
 * 所有路径都来自服务端 API，不来自用户自由输入；仍然按目标平台做引号处理，
 * 含换行、NUL 或无法安全引用的路径一律返回 null，不发命令。
 */
object ShellBootstrap {

    fun options(platform: TerminalPlatform): List<TerminalShell> =
        when (resolve(platform, null)) {
            TerminalPlatform.WINDOWS -> listOf(
                TerminalShell.DEFAULT,
                TerminalShell.POWERSHELL,
                TerminalShell.CMD,
                TerminalShell.GIT_BASH,
            )
            TerminalPlatform.POSIX -> listOf(
                TerminalShell.DEFAULT,
                TerminalShell.BASH,
                TerminalShell.DASH,
            )
            TerminalPlatform.AUTO -> emptyList()
        }

    fun label(shell: TerminalShell): String = when (shell) {
        TerminalShell.DEFAULT -> Texts.get(R.string.ssh_shell_default)
        TerminalShell.BASH -> "bash"
        TerminalShell.DASH -> "dash"
        TerminalShell.POWERSHELL -> "PowerShell"
        TerminalShell.CMD -> Texts.get(R.string.ssh_shell_cmd)
        TerminalShell.GIT_BASH -> "Bash"
    }

    fun resolve(platform: TerminalPlatform, path: String?): TerminalPlatform =
        if (platform != TerminalPlatform.AUTO) {
            platform
        } else if (isWindowsPath(path.orEmpty())) {
            TerminalPlatform.WINDOWS
        } else {
            TerminalPlatform.POSIX
        }

    fun isWindowsPath(path: String): Boolean =
        WINDOWS_DRIVE.matches(path)

    /**
     * 服务端默认 shell 是否已经是用户选择的那个。
     *
     * 本地 PTY 的 `title` 是服务端启动命令的文件名（如 `pwsh`、`cmd`）。
     * 默认已是 PowerShell 时再发 `powershell -NoLogo` 会嵌套一层 PS：
     * 启动更慢，且 PSReadLine 在 ConPTY 里会因初始光标位置报
     * `top = -1`。匹配时按 DEFAULT 处理，不切换。
     */
    fun matchesDefault(shell: TerminalShell, title: String): Boolean {
        val name = title.substringAfterLast('\\').substringAfterLast('/').lowercase()
        return when (shell) {
            TerminalShell.POWERSHELL -> name in setOf("pwsh", "powershell", "powershell.exe")
            TerminalShell.CMD -> name in setOf("cmd", "cmd.exe")
            TerminalShell.BASH -> name == "bash" || name == "bash.exe"
            TerminalShell.GIT_BASH -> name == "bash" || name == "bash.exe"
            TerminalShell.DASH -> name == "dash"
            TerminalShell.DEFAULT -> true
        }
    }

    /**
     * SSH 直连无法预知远端默认 shell，只能看第一条输出里的提示符。
     *
     * 典型提示符：PowerShell `PS C:\Users\x>`、cmd `C:\Users\x>`、bash `x@y:~$`。
     * 只做保守匹配；认不出就照旧发送切换命令，不要因为误判让用户没有 shell 可用。
     */
    fun promptMatchesShell(shell: TerminalShell, output: String): Boolean = when (shell) {
        TerminalShell.POWERSHELL -> POWERSHELL_PROMPT.containsMatchIn(output)
        TerminalShell.CMD -> CMD_PROMPT.containsMatchIn(output)
        TerminalShell.BASH, TerminalShell.GIT_BASH, TerminalShell.DASH ->
            POSIX_PROMPT.containsMatchIn(output)

        TerminalShell.DEFAULT -> true
    }

    /** 在默认 PTY/SSH shell 建立后发送；null 表示保持服务端默认 shell。 */
    fun switchCommand(shell: TerminalShell, platform: TerminalPlatform): String? =
        when (resolve(platform, null)) {
            TerminalPlatform.POSIX -> when (shell) {
                TerminalShell.DEFAULT -> null
                TerminalShell.BASH -> "exec bash"
                TerminalShell.DASH -> "exec dash"
                else -> null
            }
            TerminalPlatform.WINDOWS -> when (shell) {
                TerminalShell.DEFAULT -> null
                TerminalShell.POWERSHELL -> "powershell -NoLogo"
                TerminalShell.CMD -> "cmd.exe /K"
                TerminalShell.GIT_BASH -> "bash.exe -i"
                else -> null
            }
            TerminalPlatform.AUTO -> null
        }

    /**
     * SSH 直连时直接以目标 shell 作为远程命令启动，避免「默认 shell 再嵌套一层」。
     *
     * Windows 的默认 shell 常是 cmd；在 cmd 里再执行 `powershell -NoLogo` 会创建
     * 嵌套 PS，旧版 PSReadLine 在 80×24 缓冲区上会报 `top = -1`。直接开 exec
     * 通道把 PS 作为子进程，既不经过 cmd，也省掉默认 shell 的启动时间。
     * 返回 null 表示沿用默认 shell 通道。
     */
    fun execCommand(shell: TerminalShell, platform: TerminalPlatform): String? =
        when (resolve(platform, null)) {
            TerminalPlatform.POSIX -> when (shell) {
                TerminalShell.BASH -> "bash -i"
                TerminalShell.DASH -> "dash -i"
                else -> null
            }
            TerminalPlatform.WINDOWS -> when (shell) {
                TerminalShell.POWERSHELL -> "powershell -NoLogo"
                TerminalShell.CMD -> "cmd.exe"
                TerminalShell.GIT_BASH -> "bash.exe -i"
                else -> null
            }
            TerminalPlatform.AUTO -> null
        }

    /**
     * 连接后进入智能体工作区；null 表示不发送（不同机、路径为空或无法安全引用）。
     * 命令失败时由 shell 自己报错，不阻断终端。
     */
    fun workspaceCommand(
        path: String?,
        shell: TerminalShell,
        platform: TerminalPlatform,
        sameHost: Boolean,
    ): String? {
        if (!sameHost) return null
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty() || raw.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return when (resolve(platform, raw)) {
            TerminalPlatform.POSIX -> {
                val quoted = posixQuote(raw) ?: return null
                "cd -- $quoted"
            }
            TerminalPlatform.WINDOWS -> when (shell) {
                TerminalShell.POWERSHELL -> {
                    val quoted = powerShellQuote(raw) ?: return null
                    "Set-Location -LiteralPath $quoted"
                }
                TerminalShell.CMD -> {
                    val quoted = cmdQuote(raw) ?: return null
                    "cd /d $quoted"
                }
                TerminalShell.GIT_BASH -> {
                    val quoted = posixQuote(raw.replace('\\', '/')) ?: return null
                    "cd -- $quoted"
                }
                // 默认 shell 可能是 PowerShell 或 cmd；cd 在两者里都可用。
                TerminalShell.DEFAULT -> {
                    val quoted = cmdQuote(raw) ?: return null
                    "cd $quoted"
                }
                else -> null
            }
            TerminalPlatform.AUTO -> null
        }
    }

    private fun posixQuote(value: String): String? {
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun powerShellQuote(value: String): String? {
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return "'" + value.replace("'", "''") + "'"
    }

    private fun cmdQuote(value: String): String? {
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' || it == '"' }) return null
        return "\"$value\""
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/].*")

    private val POWERSHELL_PROMPT = Regex("(?m)^\\s*PS(?:\\s+[A-Za-z]:[^\\r\\n]*)?\\s*>")
    private val CMD_PROMPT = Regex("(?m)^\\s*[A-Za-z]:\\\\[^\\r\\n>]*>")
    private val POSIX_PROMPT = Regex("[$#]\\s*$")
}
