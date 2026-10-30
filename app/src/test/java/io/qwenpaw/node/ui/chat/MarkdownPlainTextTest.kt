package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Markdown → 纯文本。
 *
 * 这是「复制」按钮的内容来源。两个按钮此前都复制原文，「复制」与
 * 「复制为 Markdown」行为完全相同，等于没实现；这里验证剥离规则
 * 既去掉了标记，也没有误伤代码里的符号。
 */
class MarkdownPlainTextTest {

    @Test
    fun `plain text passes through unchanged`() {
        val text = "这是一段普通文本。\n第二行。"
        assertEquals(text, MarkdownPlainText.strip(text))
    }

    @Test
    fun `headings lose the hash prefix`() {
        assertEquals("标题", MarkdownPlainText.strip("# 标题"))
        assertEquals("三级标题", MarkdownPlainText.strip("### 三级标题"))
    }

    /** 引用里套列表时前缀会叠加，需要逐层剥掉。 */
    @Test
    fun `nested quote and list prefixes are stripped`() {
        assertEquals("条目", MarkdownPlainText.strip("> - 条目"))
    }

    @Test
    fun `ordered and unordered list markers are stripped`() {
        assertEquals("第一项", MarkdownPlainText.strip("- 第一项"))
        assertEquals("第二项", MarkdownPlainText.strip("* 第二项"))
        assertEquals("第三项", MarkdownPlainText.strip("1. 第三项"))
        assertEquals("第四项", MarkdownPlainText.strip("2) 第四项"))
    }

    @Test
    fun `inline emphasis markers are stripped`() {
        assertEquals("粗体与斜体", MarkdownPlainText.strip("**粗体**与*斜体*"))
        assertEquals("删除线", MarkdownPlainText.strip("~~删除线~~"))
        assertEquals("行内代码", MarkdownPlainText.strip("`行内代码`"))
    }

    /** 下划线只在词边界生效，`user_id_name` 里的下划线是标识符，不能吃。 */
    @Test
    fun `underscore emphasis does not eat identifiers`() {
        assertEquals("user_id_name", MarkdownPlainText.strip("user_id_name"))
        assertEquals("斜体", MarkdownPlainText.strip("_斜体_"))
    }

    @Test
    fun `link keeps only the text`() {
        assertEquals("看这里", MarkdownPlainText.strip("[看这里](https://example.com/a)"))
    }

    @Test
    fun `image keeps only the alt text`() {
        assertEquals("截图", MarkdownPlainText.strip("![截图](https://example.com/a.png)"))
    }

    @Test
    fun `fence markers are dropped but code content is kept`() {
        val md = "说明：\n```kotlin\nval a = 1 * 2\n```\n结束"
        val out = MarkdownPlainText.strip(md)
        assertEquals("说明：\nval a = 1 * 2\n结束", out)
    }

    /** 代码块里的 `#` 与 `-` 是语法本身，不能被当成标题与列表处理。 */
    @Test
    fun `code block content is not stripped`() {
        val md = "```\n# 不是标题\n- 不是列表\n```"
        assertEquals("# 不是标题\n- 不是列表", MarkdownPlainText.strip(md))
    }

    @Test
    fun `table keeps cell text and drops separator row`() {
        val md = "| 名称 | 值 |\n|---|---|\n| a | 1 |"
        val out = MarkdownPlainText.strip(md)
        assertEquals("名称  值\na  1", out)
    }

    @Test
    fun `horizontal rule is dropped`() {
        assertEquals("前\n后", MarkdownPlainText.strip("前\n\n---\n\n后"))
    }

    /** 段落之间的空行必须保留，否则整段会挤成一坨。 */
    @Test
    fun `blank lines are preserved`() {
        val out = MarkdownPlainText.strip("第一段\n\n第二段")
        assertEquals("第一段\n\n第二段", out)
    }

    @Test
    fun `blank input returns blank`() {
        assertEquals("", MarkdownPlainText.strip(""))
        assertEquals("", MarkdownPlainText.strip("   \n  "))
    }

    /** 嵌套强调：三重星号先于双重、双重先于单重。 */
    @Test
    fun `triple emphasis is stripped`() {
        assertEquals("重要", MarkdownPlainText.strip("***重要***"))
    }
}