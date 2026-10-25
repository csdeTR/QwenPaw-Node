package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 解析测试：覆盖 QwenPaw 回复里常见结构。 */
class MarkdownParserTest {

    @Test
    fun `splits paragraphs on blank line`() {
        val blocks = MarkdownParser.parse("第一段\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段", (blocks[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `keeps single newline inside paragraph`() {
        val blocks = MarkdownParser.parse("第一行\n第二行")
        assertEquals("第一行\n第二行", (blocks.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `parses fenced code with language`() {
        val md = "```python\nprint('hi')\n```"
        val code = MarkdownParser.parse(md).single() as MdBlock.Code
        assertEquals("python", code.language)
        assertEquals("print('hi')", code.code)
    }

    @Test
    fun `parses mermaid fence`() {
        val md = "```mermaid\ngraph TD; A-->B\n```"
        val code = MarkdownParser.parse(md).single() as MdBlock.Code
        assertEquals("mermaid", code.language)
    }

    @Test
    fun `unclosed fence still yields code block`() {
        val blocks = MarkdownParser.parse("```\ncode without end")
        val code = blocks.single() as MdBlock.Code
        assertEquals("code without end", code.code)
    }

    @Test
    fun `parses headings and bullets`() {
        val md = "# 标题\n- 项目一\n- 项目二"
        val blocks = MarkdownParser.parse(md)
        assertEquals(MdBlock.Heading(1, "标题"), blocks[0])
        assertEquals(MdBlock.Bullet("项目一"), blocks[1])
        assertEquals(MdBlock.Bullet("项目二"), blocks[2])
    }

    @Test
    fun `parses numbered list as bullet`() {
        val blocks = MarkdownParser.parse("1. 第一步\n2) 第二步")
        assertEquals(MdBlock.Bullet("第一步"), blocks[0])
        assertEquals(MdBlock.Bullet("第二步"), blocks[1])
    }

    @Test
    fun `parses table and skips separator row`() {
        val md = """
            | 名称 | 数量 |
            | --- | --- |
            | 苹果 | 3 |
        """.trimIndent()
        val table = MarkdownParser.parse(md).single() as MdBlock.Table
        assertEquals(2, table.rows.size)
        assertEquals(listOf("名称", "数量"), table.rows[0])
        assertEquals(listOf("苹果", "3"), table.rows[1])
    }

    @Test
    fun `parses blockquote`() {
        val blocks = MarkdownParser.parse("> 引用内容")
        assertEquals(MdBlock.Quote("引用内容"), blocks.single())
    }

    @Test
    fun `mixed document produces ordered blocks`() {
        val md = """
            说明文字

            ## 小标题

            - 要点

            ```bash
            ls -la
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Heading)
        assertTrue(blocks[2] is MdBlock.Bullet)
        assertTrue(blocks[3] is MdBlock.Code)
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `code block content is not parsed as markdown`() {
        val md = "```\n# 这不是标题\n- 这不是列表\n```"
        val code = MarkdownParser.parse(md).single() as MdBlock.Code
        assertTrue(code.code.contains("# 这不是标题"))
    }
}
