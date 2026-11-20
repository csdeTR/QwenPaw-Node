package io.qwenpaw.node.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellBootstrapTest {

    @Test
    fun `shell options follow platform`() {
        assertEquals(
            listOf(TerminalShell.DEFAULT, TerminalShell.BASH, TerminalShell.DASH),
            ShellBootstrap.options(TerminalPlatform.POSIX),
        )
        assertEquals(
            listOf(
                TerminalShell.DEFAULT,
                TerminalShell.POWERSHELL,
                TerminalShell.CMD,
                TerminalShell.GIT_BASH,
            ),
            ShellBootstrap.options(TerminalPlatform.WINDOWS),
        )
    }

    @Test
    fun `auto platform detects windows drive paths`() {
        assertEquals(
            TerminalPlatform.WINDOWS,
            ShellBootstrap.resolve(TerminalPlatform.AUTO, "C:\\Users\\csdetr"),
        )
        assertEquals(
            TerminalPlatform.POSIX,
            ShellBootstrap.resolve(TerminalPlatform.AUTO, "/home/csdetr"),
        )
        assertEquals(
            TerminalPlatform.POSIX,
            ShellBootstrap.resolve(TerminalPlatform.AUTO, null),
        )
    }

    @Test
    fun `default shell match avoids nested powershell or cmd`() {
        assertTrue(ShellBootstrap.matchesDefault(TerminalShell.POWERSHELL, "pwsh"))
        assertTrue(ShellBootstrap.matchesDefault(TerminalShell.POWERSHELL, "powershell.exe"))
        assertTrue(ShellBootstrap.matchesDefault(TerminalShell.POWERSHELL, "C:\\Windows\\powershell.exe"))
        assertTrue(ShellBootstrap.matchesDefault(TerminalShell.CMD, "cmd.exe"))
        assertTrue(ShellBootstrap.matchesDefault(TerminalShell.BASH, "bash"))
        assertTrue(!ShellBootstrap.matchesDefault(TerminalShell.CMD, "pwsh"))
        assertTrue(!ShellBootstrap.matchesDefault(TerminalShell.POWERSHELL, "cmd"))
    }

    @Test
    fun `prompt detection avoids nested shell over ssh`() {
        assertTrue(
            ShellBootstrap.promptMatchesShell(
                TerminalShell.POWERSHELL,
                "PS C:\\Users\\Administrator> ",
            ),
        )
        assertTrue(
            ShellBootstrap.promptMatchesShell(
                TerminalShell.CMD,
                "C:\\Users\\Administrator>",
            ),
        )
        assertTrue(
            ShellBootstrap.promptMatchesShell(TerminalShell.BASH, "user@host:~$ "),
        )
        assertTrue(
            !ShellBootstrap.promptMatchesShell(
                TerminalShell.POWERSHELL,
                "C:\\Users\\Administrator>",
            ),
        )
        assertTrue(
            !ShellBootstrap.promptMatchesShell(
                TerminalShell.CMD,
                "PS C:\\Users\\Administrator>",
            ),
        )
    }

    @Test
    fun `exec commands start the selected shell directly`() {
        assertEquals(
            "powershell -NoLogo",
            ShellBootstrap.execCommand(TerminalShell.POWERSHELL, TerminalPlatform.WINDOWS),
        )
        assertEquals(
            "cmd.exe",
            ShellBootstrap.execCommand(TerminalShell.CMD, TerminalPlatform.WINDOWS),
        )
        assertEquals(
            "bash -i",
            ShellBootstrap.execCommand(TerminalShell.BASH, TerminalPlatform.POSIX),
        )
        assertNull(ShellBootstrap.execCommand(TerminalShell.DEFAULT, TerminalPlatform.WINDOWS))
    }

    @Test
    fun `switch commands cover posix and windows`() {
        assertNull(ShellBootstrap.switchCommand(TerminalShell.DEFAULT, TerminalPlatform.POSIX))
        assertTrue(
            ShellBootstrap.switchCommand(TerminalShell.BASH, TerminalPlatform.POSIX)!!
                .contains("exec bash"),
        )
        assertTrue(
            ShellBootstrap.switchCommand(TerminalShell.DASH, TerminalPlatform.POSIX)!!
                .contains("exec dash"),
        )
        assertEquals(
            "powershell -NoLogo",
            ShellBootstrap.switchCommand(TerminalShell.POWERSHELL, TerminalPlatform.WINDOWS),
        )
        assertEquals(
            "cmd.exe /K",
            ShellBootstrap.switchCommand(TerminalShell.CMD, TerminalPlatform.WINDOWS),
        )
        assertEquals(
            "bash.exe -i",
            ShellBootstrap.switchCommand(TerminalShell.GIT_BASH, TerminalPlatform.WINDOWS),
        )
    }

    @Test
    fun `workspace commands quote paths per shell`() {
        assertEquals(
            "cd -- '/home/a b'\\''s'",
            ShellBootstrap.workspaceCommand(
                "/home/a b's",
                TerminalShell.BASH,
                TerminalPlatform.POSIX,
                sameHost = true,
            ),
        )
        assertEquals(
            "Set-Location -LiteralPath 'C:\\Users\\O''Brien'",
            ShellBootstrap.workspaceCommand(
                "C:\\Users\\O'Brien",
                TerminalShell.POWERSHELL,
                TerminalPlatform.WINDOWS,
                sameHost = true,
            ),
        )
        assertEquals(
            "cd /d \"C:\\a b\"",
            ShellBootstrap.workspaceCommand(
                "C:\\a b",
                TerminalShell.CMD,
                TerminalPlatform.WINDOWS,
                sameHost = true,
            ),
        )
        assertEquals(
            "cd -- 'C:/Users/csdetr'",
            ShellBootstrap.workspaceCommand(
                "C:\\Users\\csdetr",
                TerminalShell.GIT_BASH,
                TerminalPlatform.WINDOWS,
                sameHost = true,
            ),
        )
        assertEquals(
            "cd \"C:\\Users\\csdetr\"",
            ShellBootstrap.workspaceCommand(
                "C:\\Users\\csdetr",
                TerminalShell.DEFAULT,
                TerminalPlatform.WINDOWS,
                sameHost = true,
            ),
        )
    }

    @Test
    fun `workspace command is skipped for other hosts or unsafe paths`() {
        assertNull(
            ShellBootstrap.workspaceCommand(
                "/home/csdetr",
                TerminalShell.BASH,
                TerminalPlatform.POSIX,
                sameHost = false,
            ),
        )
        assertNull(
            ShellBootstrap.workspaceCommand(
                "/home/\ncsdetr",
                TerminalShell.BASH,
                TerminalPlatform.POSIX,
                sameHost = true,
            ),
        )
        assertNull(
            ShellBootstrap.workspaceCommand(
                "  ",
                TerminalShell.BASH,
                TerminalPlatform.POSIX,
                sameHost = true,
            ),
        )
        assertNull(
            ShellBootstrap.workspaceCommand(
                "C:\\a\"b",
                TerminalShell.CMD,
                TerminalPlatform.WINDOWS,
                sameHost = true,
            ),
        )
    }
}
