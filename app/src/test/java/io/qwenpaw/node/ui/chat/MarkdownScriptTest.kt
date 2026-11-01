package io.qwenpaw.node.ui.chat

import androidx.compose.ui.text.style.BaselineShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段落里的裸上下标与行内公式识别。
 *
 * 模型解释时常常直接写 `x^2`、`H_2O` 而不套 `$`，过去这些文本原样显示成「x^2」。
 * 这里验证它们变成带基线偏移的 span，同时不破坏 Markdown 的斜体、粗体与行内代码。
 */
class MarkdownScriptTest {

    private fun superscriptSpans(text: String): Int =
        inline(text).spanStyles.count { it.item.baselineShift == BaselineShift.Superscript }

    private fun subscriptSpans(text: String): Int =
        inline(text).spanStyles.count { it.item.baselineShift == BaselineShift.Subscript }

    @Test
    fun `caret produces superscript span`() {
        val styled = inline("x^2")
        assertEquals("x2", styled.text)
        assertEquals(1, superscriptSpans("x^2"))
    }

    @Test
    fun `braced superscript keeps whole content`() {
        val styled = inline("x^{n+1}")
        assertEquals("xn+1", styled.text)
        assertEquals(1, superscriptSpans("x^{n+1}"))
    }

    @Test
    fun `word internal underscore produces subscript span`() {
        assertEquals("H2O", inline("H_2O").text)
        assertEquals(1, subscriptSpans("H_2O"))
        assertEquals("ai", inline("a_i").text)
        assertEquals(1, subscriptSpans("a_i"))
    }

    /** `_斜体_` 的首个下划线不在词内，仍按强调处理，不能变成下标。 */
    @Test
    fun `underscore emphasis is not a subscript`() {
        val styled = inline("_斜体_")
        assertEquals("斜体", styled.text)
        assertEquals(0, subscriptSpans("_斜体_"))
        assertEquals(0, subscriptSpans("_ text _"))
    }

    /** 行内代码与粗体内部不解析上下标，否则 `snake_case` 会被拆坏。 */
    @Test
    fun `code and bold content keep carets literally`() {
        assertEquals("x^2", inline("`x^2`").text)
        assertEquals(0, superscriptSpans("`x^2`"))
        assertEquals("x^2", inline("**x^2**").text)
        assertEquals(0, superscriptSpans("**x^2**"))
    }

    /** `\(…\)` 是模型常用的行内公式写法，必须走内联渲染而不是留下字面定界符。 */
    @Test
    fun `parenthesis delimited math renders inline`() {
        val styled = inline("其中 \\(x^2\\) 表示面积")
        assertEquals("其中 x² 表示面积", styled.text)
        assertFalse(styled.text.contains("\\("))
    }

    @Test
    fun `bracket delimited math renders inline`() {
        val styled = inline("式 \\[x^2\\] 成立")
        assertEquals("式 x² 成立", styled.text)
    }

    @Test
    fun `dollar math still renders inline`() {
        val styled = inline("面积 \$x^2\$")
        assertEquals("面积 x²", styled.text)
    }

    /** 行中的块级公式要拆成「前文 + Math 块 + 后文」。 */
    @Test
    fun `inline display math splits line into three blocks`() {
        val blocks = MarkdownParser.parse("前文 \$\$x^2\$\$ 后文")
        assertEquals(3, blocks.size)
        assertEquals("前文", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("x^2", (blocks[1] as MdBlock.Math).source)
        assertEquals("后文", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun `inline bracket display math also splits the line`() {
        val blocks = MarkdownParser.parse("前文 \\[x^2\\] 后文")
        assertEquals(3, blocks.size)
        assertEquals("x^2", (blocks[1] as MdBlock.Math).source)
    }

    /** 未闭合的定界符是流式输出的中间态，不能渲染成半个公式。 */
    @Test
    fun `unclosed display math stays literal`() {
        val blocks = MarkdownParser.parse("前文 \$\$x^2 后文")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }
}