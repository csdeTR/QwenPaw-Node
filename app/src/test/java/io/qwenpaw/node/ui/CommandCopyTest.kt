package io.qwenpaw.node.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令的中文文案表。
 *
 * 表是静态数据，单测要钉住两件事：一是服务端实测的 35 条命令都有译文，
 * 二是内部命令的判定与注释里列出的名单一致——名单错一个字，
 * 用户就会在候选列表里看到一条本不该出现的 dump_history。
 */
class CommandCopyTest {

    /** 服务端 `GET /api/workspace/commands/available` 在 2.2.2.post1 上实测的全部命令名。 */
    private val serverCommands = listOf(
        "approval", "approve", "auto_memory_status", "checkpoint", "clear",
        "compact", "compact_str", "cron-create", "cron-delete", "cron-list",
        "daemon", "deny", "dump_history", "goal", "history", "load_history",
        "logs", "message", "mission", "model", "new", "plan", "proactive",
        "reload-config", "reload_config", "reme", "restart", "skills", "status",
        "stop", "system_prompt", "tool-bg", "tool-cancel", "tools", "version",
    )

    @Test
    fun `every server command has a chinese description`() {
        assertEquals(35, serverCommands.size)
        serverCommands.forEach { name ->
            val text = CommandCopy.description(name)
            assertNotNull("缺少译文：$name", text)
            assertTrue("译文不应为空：$name", text!!.isNotBlank())
            // 译文必须是中文：至少含一个汉字，避免误把英文原句贴进来。
            assertTrue("译文不是中文：$name -> $text", text.any { it.code in 0x4e00..0x9fff })
        }
    }

    @Test
    fun `internal commands are hidden and user commands are kept`() {
        listOf(
            "dump_history", "load_history", "system_prompt", "compact_str",
            "auto_memory_status", "message", "proactive", "history",
            "daemon", "restart", "logs", "version", "reload-config", "reload_config",
            "cron-create", "cron-delete", "cron-list",
        ).forEach { assertFalse("$it 应隐藏", CommandCopy.isUserFacing(it)) }

        listOf(
            "clear", "compact", "new", "plan", "reme", "status", "goal", "mission",
            "approval", "approve", "deny", "stop", "checkpoint", "model",
            "skills", "tools", "tool-bg", "tool-cancel",
        ).forEach { assertTrue("$it 应保留", CommandCopy.isUserFacing(it)) }
    }

    /** 插件与技能注册的命令不在名单里，必须保留；否则它们的命令会从候选里消失。 */
    @Test
    fun `unknown commands are kept`() {
        assertTrue(CommandCopy.isUserFacing("some-skill-command"))
        assertNull(CommandCopy.description("some-skill-command"))
        assertNull(CommandCopy.example("some-skill-command"))
        assertNull(CommandCopy.detail("some-skill-command"))
    }

    /**
     * 「魔法命令」的详情依赖这两张表。缺一条就会在长按后只剩命令名，
     * 因此按 [CommandCopy.userFacingNames] 逐条核对，新增命令时这条用例会直接报出名字。
     */
    @Test
    fun `every user facing command has an example and a detail`() {
        val names = CommandCopy.userFacingNames()
        assertEquals(18, names.size)
        names.forEach { name ->
            val example = CommandCopy.example(name)
            assertNotNull("缺少示例：$name", example)
            assertTrue("示例应以 /$name 开头：$example", example!!.startsWith("/$name"))

            val detail = CommandCopy.detail(name)
            assertNotNull("缺少详情：$name", detail)
            assertTrue("详情不是中文：$name -> $detail", detail!!.any { it.code in 0x4e00..0x9fff })
            // 详情要给出比列表灰字更多的信息，不能把同一句抄两遍。
            assertTrue("详情与列表说明重复：$name", detail != CommandCopy.description(name))
        }
    }

    /**
     * 服务端把分类叫 `conversation`，Console 译作「会话内容」。
     * 界面统一用「对话」，因此这里必须是「对话」。
     */
    @Test
    fun `conversation category is translated as dialogue`() {
        assertEquals("对话", CommandCopy.category("conversation"))
        assertEquals("控制", CommandCopy.category("control"))
        assertEquals("服务", CommandCopy.category("daemon"))
    }

    @Test
    fun `unknown category passes through`() {
        assertEquals("plugin-category", CommandCopy.category("plugin-category"))
        assertEquals("", CommandCopy.category(""))
    }
}