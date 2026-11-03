package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ToolDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具分组与图标。
 *
 * 官方控制台的分组不依赖服务端 `category`——内置工具的该字段为 `null`。
 * 这里守住三件事：内置工具都有归属、插件工具优先用自己的分类、组顺序固定。
 */
class ToolGroupingTest {

    /** `GET /api/tools` 在 2.2.2.post1 上实测的全部工具名。 */
    private val serverTools = listOf(
        "execute_shell_command", "read_file", "write_file", "edit_file",
        "grep_search", "glob_search", "web_search", "web_fetch",
        "desktop_screenshot", "view_image", "view_video", "send_file_to_user",
        "get_current_time", "set_user_timezone", "get_token_usage",
        "delegate_external_agent", "list_agents", "chat_with_agent",
        "submit_to_agent", "check_agent_task", "spawn_subagent", "browser",
        "append_file", "materialize_skill", "ast_search", "run_tool_batch",
        "activate_f1_exploration_mode", "view_audio",
        "migration_compat_inspect", "migration_compat_read_file",
        "migration_compat_write_file", "migration_compat_update",
        "migration_compat_finalize",
    )

    @Test
    fun `every server tool maps to a known group`() {
        serverTools.forEach { name ->
            val group = TOOL_GROUP_OF[name]
            assertNotNull("缺少分组：$name", group)
            assertTrue("分组不在官方列表里：$name -> $group", group in TOOL_GROUP_ORDER)
        }
    }

    @Test
    fun `every server tool has an icon`() {
        serverTools.forEach { name ->
            assertNotNull("缺少图标：$name", TOOL_ICONS[name])
        }
    }

    @Test
    fun `built-in tool groups match the official presentation table`() {
        // 抽样核对官方 TOOL_PRESENTATION 里几条容易写错的归属。
        val expected = mapOf(
            "execute_shell_command" to "runtime",
            "send_file_to_user" to "files",
            "browser" to "web",
            "spawn_subagent" to "agents",
            "enhance_and_generate_image" to "media",
            "migration_compat_finalize" to "migration",
        )
        expected.forEach { (name, group) ->
            assertEquals(name, group, TOOL_GROUP_OF[name])
        }
    }

    @Test
    fun `group order is fixed and other comes last`() {
        val tools = listOf(
            ToolDto(name = "migration_compat_inspect"),
            ToolDto(name = "read_file"),
            ToolDto(name = "not_a_known_tool"),
            ToolDto(name = "browser"),
        )
        val groups = groupTools(tools).map { it.first }
        assertEquals(listOf("files", "web", "migration", "other"), groups)
    }

    @Test
    fun `empty groups are omitted and server order is preserved`() {
        val tools = listOf(
            ToolDto(name = "write_file"),
            ToolDto(name = "read_file"),
        )
        val grouped = groupTools(tools)
        assertEquals(1, grouped.size)
        assertEquals(listOf("write_file", "read_file"), grouped[0].second.map { it.name })
    }

    @Test
    fun `plugin tool uses its category when it is a known group`() {
        val tool = ToolDto(name = "custom_tool", sourcePluginId = "p1", category = "web")
        assertEquals("web", toolGroup(tool))
    }

    @Test
    fun `plugin tool with unknown category falls back to other`() {
        val tool = ToolDto(name = "custom_tool", sourcePluginId = "p1", category = "custom-group")
        assertEquals(TOOL_GROUP_OTHER, toolGroup(tool))
    }

    @Test
    fun `plugin tool with null category falls back to other`() {
        val tool = ToolDto(name = "custom_tool", sourcePluginId = "p1", category = null)
        assertEquals(TOOL_GROUP_OTHER, toolGroup(tool))
    }

    @Test
    fun `unknown built-in tool falls back to other`() {
        val tool = ToolDto(name = "brand_new_tool")
        assertEquals(TOOL_GROUP_OTHER, toolGroup(tool))
    }

    @Test
    fun `icon fallback only applies to unknown tools`() {
        assertNull(TOOL_ICONS["brand_new_tool"])
        // 已知工具的图标不是兜底图标。
        assertTrue(TOOL_ICONS["read_file"] !== io.qwenpaw.node.ui.theme.QwenIcons.wrench)
        assertSame(
            io.qwenpaw.node.ui.theme.QwenIcons.wrench,
            toolIcon(ToolDto(name = "brand_new_tool")),
        )
    }
}
