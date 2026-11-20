package io.qwenpaw.node.ui.mcp

import io.qwenpaw.node.data.model.McpClientDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 新建立与编辑的纯逻辑单测。
 *
 * 这些断言防的是同一类缺陷：部分更新时多发或漏发字段。
 * 多发会把用户没动过的参数覆盖掉，漏发会让切换传输方式后旧地址、旧请求头
 * 留在服务端，界面上看不出区别，只有下一次加载工具时才暴露。
 */
class McpEditDraftTest {

    private val stdioClient = McpClientDto(
        key = "fs",
        name = "Files",
        enabled = true,
        transport = "stdio",
        command = "npx",
        args = listOf("-y", "server-fs", "/tmp"),
        env = mapOf("TOKEN" to "secret"),
        cwd = "/work",
    )

    private val remoteClient = McpClientDto(
        key = "web",
        name = "Web",
        enabled = true,
        transport = "streamable_http",
        url = "https://example.com/mcp",
        headers = mapOf("Authorization" to "Bearer t"),
        httpTimeout = 30.0,
    )

    @Test
    fun `stdio client round trips through the draft`() {
        val draft = McpEditDraft.from(stdioClient)
        assertEquals("npx", draft.command)
        assertEquals(listOf("-y", "server-fs", "/tmp"), McpEditDraft.parseArgs(draft.argsText))
        assertEquals(
            mapOf("TOKEN" to "secret"),
            McpEditDraft.parsePairs(draft.envText, '=').entries,
        )
        assertEquals("/work", draft.cwd)
    }

    @Test
    fun `remote client round trips through the draft`() {
        val draft = McpEditDraft.from(remoteClient)
        assertEquals("https://example.com/mcp", draft.url)
        assertEquals(
            mapOf("Authorization" to "Bearer t"),
            McpEditDraft.parsePairs(draft.headersText, ':').entries,
        )
        assertEquals(30.0, McpEditDraft.parseTimeout(draft.httpTimeoutText))
    }

    @Test
    fun `switching to remote clears the local fields`() {
        val next = McpEditDraft.withTransport(McpEditDraft.from(stdioClient), McpEditDraft.HTTP)
        assertEquals(McpEditDraft.HTTP, next.transport)
        assertEquals("", next.command)
        assertTrue(McpEditDraft.parseArgs(next.argsText).isEmpty())
        assertTrue(McpEditDraft.parsePairs(next.envText, '=').entries.isEmpty())
        assertEquals("", next.cwd)
    }

    @Test
    fun `switching to stdio clears the remote fields`() {
        val next = McpEditDraft.withTransport(McpEditDraft.from(remoteClient), McpEditDraft.STDIO)
        assertEquals(McpEditDraft.STDIO, next.transport)
        assertEquals("", next.url)
        assertTrue(McpEditDraft.parsePairs(next.headersText, ':').entries.isEmpty())
        assertEquals("", next.httpTimeoutText)
    }

    @Test
    fun `unchanged draft produces no request body`() {
        val draft = McpEditDraft.from(stdioClient)
        val body = McpEditDraft.changes(draft, stdioClient)
        assertFalse(McpEditDraft.hasChanges(body))
    }

    @Test
    fun `changes include only the modified field`() {
        val draft = McpEditDraft.from(stdioClient).copy(name = "Renamed")
        val body = McpEditDraft.changes(draft, stdioClient)
        assertEquals("Renamed", body.name)
        assertNull(body.command)
        assertNull(body.args)
        assertNull(body.env)
        assertNull(body.transport)
        assertTrue(McpEditDraft.hasChanges(body))
    }

    @Test
    fun `transport switch submits explicit empties for the other side`() {
        val draft = McpEditDraft
            .withTransport(McpEditDraft.from(remoteClient), McpEditDraft.STDIO)
            .copy(command = "npx")
        val body = McpEditDraft.changes(draft, remoteClient)
        assertEquals(McpEditDraft.STDIO, body.transport)
        assertEquals("", body.url)
        assertEquals(emptyMap<String, String>(), body.headers)
        assertEquals("npx", body.command)
    }

    @Test
    fun `switching back to remote submits empty local fields`() {
        val draft = McpEditDraft.withTransport(McpEditDraft.from(stdioClient), McpEditDraft.SSE)
        val body = McpEditDraft.changes(draft, stdioClient)
        assertEquals(McpEditDraft.SSE, body.transport)
        assertEquals("", body.command)
        assertEquals(emptyList<String>(), body.args)
        assertEquals(emptyMap<String, String>(), body.env)
        assertEquals("", body.cwd)
    }

    @Test
    fun `timeout is only sent when it changed`() {
        val draft = McpEditDraft.from(remoteClient).copy(httpTimeoutText = "12")
        assertEquals(12.0, McpEditDraft.changes(draft, remoteClient).httpTimeout)
        val cleared = McpEditDraft.from(remoteClient).copy(httpTimeoutText = "")
        // 超时没有「清空」的写法：null 会被序列化配置省略，因此这里不下发。
        assertNull(McpEditDraft.changes(cleared, remoteClient).httpTimeout)
    }

    @Test
    fun `new client body keeps to the current transport`() {
        val body = McpEditDraft.newClientBody(
            McpDraftState(
                key = "k",
                name = "K",
                transport = McpEditDraft.STDIO,
                command = "npx",
                argsText = "a\n\nb",
            ),
        )
        assertEquals(listOf("a", "b"), body.args)
        assertEquals("npx", body.command)
        assertNull(body.url)
        assertNull(body.headers)
        assertNull(body.httpTimeout)
    }

    @Test
    fun `reserved key prefixes are rejected`() {
        listOf("access-principals/", "tools/", "toggle/", "oauth/", "policy/").forEach { prefix ->
            val draft = McpDraftState(key = prefix + "mine", name = "N", command = "npx")
            val errors = McpEditDraft.validate(draft)
            assertTrue(
                "reserved prefix must be rejected: $prefix",
                errors.any {
                    it.field == McpDraftField.KEY && it.issue == McpDraftIssue.KEY_RESERVED
                },
            )
        }
    }

    @Test
    fun `blank key and name are rejected`() {
        val errors = McpEditDraft.validate(McpDraftState(command = "npx"))
        assertTrue(errors.any { it.issue == McpDraftIssue.KEY_REQUIRED })
        assertTrue(errors.any { it.issue == McpDraftIssue.NAME_REQUIRED })
    }

    @Test
    fun `stdio command is required for a new client`() {
        val errors = McpEditDraft.validate(McpDraftState(key = "k", name = "N"))
        assertTrue(
            errors.any {
                it.field == McpDraftField.COMMAND && it.issue == McpDraftIssue.COMMAND_REQUIRED
            },
        )
        assertTrue(
            McpEditDraft.validate(McpDraftState(key = "k", name = "N", command = "npx")).isEmpty(),
        )
    }

    @Test
    fun `editing does not recheck the key`() {
        // 键是旧客户端的身份，编辑表单里不出现；只有新建才校验。
        val errors = McpEditDraft.validate(
            McpDraftState(key = "", name = "N", command = "npx", original = stdioClient),
        )
        assertTrue(errors.none { it.field == McpDraftField.KEY })
    }

    @Test
    fun `remote transport requires an http url`() {
        val missing = McpEditDraft.validate(
            McpDraftState(name = "N", transport = McpEditDraft.HTTP, url = ""),
        )
        assertTrue(missing.any { it.issue == McpDraftIssue.URL_REQUIRED })
        val scheme = McpEditDraft.validate(
            McpDraftState(name = "N", transport = McpEditDraft.HTTP, url = "ftp://example.com"),
        )
        assertTrue(scheme.any { it.issue == McpDraftIssue.URL_SCHEME })
        val ok = McpEditDraft.validate(
            McpDraftState(key = "web", name = "N", transport = McpEditDraft.SSE, url = "https://example.com"),
        )
        assertTrue(ok.isEmpty())
    }

    @Test
    fun `pair parsing reports the offending line and keeps the valid ones`() {
        val parsed = McpEditDraft.parsePairs("A=1\n\nBROKEN\nB=2", '=')
        assertEquals(mapOf("A" to "1", "B" to "2"), parsed.entries)
        assertEquals(listOf(3), parsed.invalidLines)
    }

    @Test
    fun `header values may contain the separator`() {
        val parsed = McpEditDraft.parsePairs("Authorization: Bearer a:b", ':')
        assertEquals(mapOf("Authorization" to "Bearer a:b"), parsed.entries)
        assertTrue(parsed.invalidLines.isEmpty())
    }

    @Test
    fun `blank timeout falls back to the server default`() {
        assertNull(McpEditDraft.parseTimeout(" "))
        assertNull(McpEditDraft.parseTimeout("-5"))
        assertEquals(30.0, McpEditDraft.parseTimeout("30"))
    }

    @Test
    fun `expires_at is formatted in the given zone`() {
        // 1700000000 秒 = 2023-11-14T22:13:20Z。
        assertEquals(
            "2023.11.14 22:13",
            McpEditDraft.expiryLabel(1_700_000_000.0, ZoneId.of("UTC")),
        )
        assertEquals(
            "2023.11.15 06:13",
            McpEditDraft.expiryLabel(1_700_000_000.0, ZoneId.of("Asia/Shanghai")),
        )
        assertEquals("", McpEditDraft.expiryLabel(0.0, ZoneId.of("UTC")))
    }

    @Test
    fun `new draft is stdio and enabled`() {
        val draft = McpEditDraft.blank()
        assertEquals(McpEditDraft.STDIO, draft.transport)
        assertTrue(draft.enabled)
        assertTrue(draft.isNew)
    }
}
