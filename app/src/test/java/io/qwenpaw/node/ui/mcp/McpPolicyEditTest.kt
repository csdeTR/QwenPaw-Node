package io.qwenpaw.node.ui.mcp

import io.qwenpaw.node.data.model.McpAccessRuleDto
import io.qwenpaw.node.data.model.McpPolicyDto
import io.qwenpaw.node.data.model.McpToolDefaultDto
import io.qwenpaw.node.data.model.McpToolOverrideDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 策略整读整写的单测。
 *
 * 这些断言防的是同一类缺陷：改一个字段时把其余字段丢掉。
 * 服务端按整份 payload 重建托管规则，丢一个字段就等于删掉一批规则，
 * 而界面上完全看不出来。
 */
class McpPolicyEditTest {

    private val rule = McpAccessRuleDto(
        sourceType = "channel",
        sourceValue = "console",
        subjectType = "all",
        subjectValue = "",
        effect = "ask",
    )

    private val override = McpToolOverrideDto(
        toolName = "read_file",
        sourceValue = "console",
        effect = "allow",
    )

    private fun policy() = McpPolicyDto(
        defaultEffect = "deny",
        clientOverrides = listOf(rule),
        toolDefaults = listOf(McpToolDefaultDto("search", "ask")),
        toolOverrides = listOf(override),
        unmanagedRulesCount = 2,
    )

    @Test
    fun `default effect keeps the other four fields`() {
        val next = McpPolicyEdit.withDefaultEffect(policy(), "allow")
        assertEquals("allow", next.defaultEffect)
        assertEquals(1, next.clientOverrides.size)
        assertEquals("console", next.clientOverrides.first().sourceValue)
        assertEquals(1, next.toolDefaults.size)
        assertEquals(1, next.toolOverrides.size)
        assertEquals(2, next.unmanagedRulesCount)
    }

    @Test
    fun `tool default replaces existing entry in place`() {
        val next = McpPolicyEdit.withToolDefault(policy(), "search", "deny")
        assertEquals(1, next.toolDefaults.size)
        assertEquals("deny", next.toolDefaults.first().effect)
        assertEquals("search", next.toolDefaults.first().toolName)
        // 覆盖规则不受影响。
        assertEquals(1, next.toolOverrides.size)
    }

    @Test
    fun `tool default appends new entry`() {
        val next = McpPolicyEdit.withToolDefault(policy(), "write_file", "allow")
        assertEquals(2, next.toolDefaults.size)
        assertEquals("write_file", next.toolDefaults.last().toolName)
        assertEquals("allow", next.toolDefaults.last().effect)
    }

    @Test
    fun `empty effect removes the entry`() {
        val next = McpPolicyEdit.withToolDefault(policy(), "search", "")
        assertEquals(0, next.toolDefaults.size)
    }

    @Test
    fun `blank tool name is ignored`() {
        val before = policy()
        val next = McpPolicyEdit.withToolDefault(before, "  ", "allow")
        assertEquals(before, next)
    }

    @Test
    fun `tool effect lookup`() {
        assertEquals("ask", McpPolicyEdit.toolEffectOf(policy(), "search"))
        assertEquals("", McpPolicyEdit.toolEffectOf(policy(), "missing"))
    }

    @Test
    fun `whitelist of everything becomes null`() {
        val all = listOf("a", "b")
        assertNull(McpPolicyEdit.whitelistSelection(all, setOf("a", "b")))
    }

    @Test
    fun `whitelist of subset keeps server order`() {
        val all = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), McpPolicyEdit.whitelistSelection(all, setOf("c", "a")))
    }

    @Test
    fun `whitelist of nothing is empty not null`() {
        // 空集合与 null 只差一个分支，混起来就是「取消全部勾选后把所有工具都打开」。
        assertEquals(emptyList<String>(), McpPolicyEdit.whitelistSelection(listOf("a", "b"), emptySet()))
    }

    @Test
    fun `whitelist of everything when server lists nothing`() {
        // 服务端一个工具都没有时不能返回 null：那是「全部启用」，与「没有工具」同义但语义不对。
        assertEquals(emptyList<String>(), McpPolicyEdit.whitelistSelection(emptyList(), emptySet()))
    }

    @Test
    fun `effect labels`() {
        assertEquals("允许", McpPolicyEdit.effectLabel("allow"))
        assertEquals("询问", McpPolicyEdit.effectLabel("ask"))
        assertEquals("拒绝", McpPolicyEdit.effectLabel("deny"))
        assertEquals("other", McpPolicyEdit.effectLabel("other"))
    }

    @Test
    fun `transport labels`() {
        assertEquals("本机进程", McpPolicyEdit.transportLabel("stdio"))
        assertEquals("HTTP", McpPolicyEdit.transportLabel("streamable_http"))
        assertEquals("SSE", McpPolicyEdit.transportLabel("sse"))
        assertEquals("weird", McpPolicyEdit.transportLabel("weird"))
    }

    @Test
    fun `endpoint label prefers command for stdio`() {
        assertEquals(
            "npx mcp-server",
            McpPolicyEdit.endpointLabel("stdio", "https://ignored", "npx mcp-server"),
        )
        assertEquals(
            "https://api.example.com/mcp",
            McpPolicyEdit.endpointLabel("streamable_http", "https://api.example.com/mcp", ""),
        )
        assertEquals("未配置", McpPolicyEdit.endpointLabel("stdio", "", ""))
    }
}