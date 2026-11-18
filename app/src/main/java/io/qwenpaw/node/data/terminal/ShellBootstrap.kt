package io.qwenpaw.node.data.terminal

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
        TerminalShell.DEFAULT -> "默认"
        TerminalShell.BASH -> "bash"
        TerminalShell.DASH -> "dash"
        TerminalShell.POWERSHELL -> "PowerShell"
        TerminalShell.CMD -> "命令提示符"
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
}
