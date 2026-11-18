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
