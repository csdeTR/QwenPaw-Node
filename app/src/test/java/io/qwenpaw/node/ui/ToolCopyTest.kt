package io.qwenpaw.node.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具的中文名与描述。
 *
 * 服务端的 33 个工具全部要有译文；`view_audio` 是唯一的例外来源——
 * 它在 `/api/tools` 里有、在 Console 语言包里没有，因此单独补了一条。
 */
class ToolCopyTest {

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
    fun `every server tool has a chinese name and description`() {
        assertEquals(33, serverTools.size)
        serverTools.forEach { name ->
            val entry = ToolCopy.of(name)
            assertNotNull("缺少译文：$name", entry)
            val (title, description) = entry!!
            assertTrue("中文名不应为空：$name", title.isNotBlank())
            assertTrue("描述不应为空：$name", description.isNotBlank())
            assertTrue("中文名不是中文：$name -> $title", title.any { it.code in 0x4e00..0x9fff })
        }
    }

    /** 插件工具不在表里，界面回退到服务端原文，不能当成错误。 */
    @Test
    fun `unknown tool returns null`() {
        assertNull(ToolCopy.of("my_plugin_tool"))
    }

    @Test
    fun `blank category falls back to unclassified`() {
        assertEquals("未分类", ToolCopy.category(""))
        assertEquals("未分类", ToolCopy.category("   "))
    }

    @Test
    fun `known and unknown categories`() {
        assertEquals("文件与代码", ToolCopy.category("files"))
        assertEquals("网页与浏览器", ToolCopy.category("web"))
        // 插件可能自带新分组，原样透出比一律折成「未分类」更有信息量。
        assertEquals("plugin-group", ToolCopy.category("plugin-group"))
    }
}