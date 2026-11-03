package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.SlashCommandDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 斜杠命令补全的触发、过滤与分组。 */
class SlashCommandsTest {

    private fun cmd(name: String, category: String = "", description: String = "") =
        SlashCommandDto(name = name, category = category, description = description)

    @Test
    fun `slash token only at the very start`() {
        assertEquals("help", slashToken("/help"))
        assertNull(slashToken("hello /help"))
        // 首字符是空格就不是命令：服务端 resolve 会先 lstrip，但输入框里先打空格是误触。
        assertNull(slashToken(" /help"))
        assertNull(slashToken("hello"))
    }

    @Test
    fun `whitespace closes the candidate list`() {
        assertNull(slashToken("/help me"))
        assertNull(slashToken("/help\n"))
        assertNull(slashToken("/help "))
    }

    @Test
    fun `lone slash returns empty token`() {
        assertEquals("", slashToken("/"))
    }

    @Test
    fun `prefix filter is case insensitive`() {
        val commands = listOf(cmd("reload"), cmd("compact"))
        assertEquals(listOf("reload"), visibleCommands(commands, "RE").map { it.name })
    }

    @Test
    fun `empty token lists everything`() {
        val commands = listOf(cmd("a"), cmd("b"))
        assertEquals(2, visibleCommands(commands, "").size)
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(visibleCommands(listOf(cmd("a")), "zzz").isEmpty())
    }

    /** 分组保持服务端顺序，空分类单独成组。 */
    @Test
    fun `categories keep server order and empty group`() {
        val commands = listOf(
            cmd("a", "daemon"),
            cmd("b", "daemon"),
            cmd("c", ""),
        )
        val groups = groupByCategory(commands)
        assertEquals(listOf("daemon", ""), groups.map { it.first })
        assertEquals(2, groups.first().second.size)
        assertEquals(1, groups.last().second.size)
    }

    @Test
    fun `empty command name is dropped`() {
        assertTrue(visibleCommands(listOf(cmd("")), "").isEmpty())
        assertTrue(groupByCategory(listOf(cmd(""))).isEmpty())
    }

    /** 插入文本带尾随空格：既能直接接参数，也让下一次 slashToken 立刻返回 null。 */
    @Test
    fun `insertion appends a trailing space and closes the list`() {
        val text = commandInsertion(cmd("help"))
        assertEquals("/help ", text)
        assertNull(slashToken(text))
    }
}