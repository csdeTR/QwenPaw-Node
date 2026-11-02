package io.qwenpaw.node.ui.chat

import androidx.compose.ui.text.font.FontStyle
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
 *
 * 判据只认数字与花括号：`P_A`、`msg_id`、`user_name` 这类写法保持原文。
 * 早期版本按 `isLetterOrDigit()` 判定词内下划线，把标识符和 `[^1]` 一起排成了上下标。
 */
class MarkdownScriptTest {

    private fun superscriptSpans(text: String): Int =
        inline(text).spanStyles.count { it.item.baselineShift == BaselineShift.Superscript }

    private fun subscriptSpans(text: String): Int =
        inline(text).spanStyles.count { it.item.baselineShift == BaselineShift.Subscript }

    private fun italicSpans(text: String): Int =
        inline(text).spanStyles.count { it.item.fontStyle == FontStyle.Italic }

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

    /** 数字下标仍然排版，且连续数字整段算一个下标，不留「₁2」这种半截。 */
    @Test
    fun `digit subscript produces subscript span`() {
        assertEquals("H2O", inline("H_2O").text)
        assertEquals(1, subscriptSpans("H_2O"))
        assertEquals("x12", inline("x_12").text)
        assertEquals(1, subscriptSpans("x_12"))
    }

    /**
     * 字母下标退回原文。
     *
     * `P_A、P_B` 是用户报的那一条：下划线被吞掉、字母掉到基线以下。
     * 断言里同时盯 0 个斜体 span——下标判据收紧后，落空的 `_` 会掉进行内强调分支，
     * 若配对不设限就会把「A、P」整段变成斜体，比原症状更难读。
     */
    @Test
    fun `letter subscripts stay literal`() {
        assertEquals("a_i", inline("a_i").text)
        assertEquals(0, subscriptSpans("a_i"))

        assertEquals("P_A、P_B", inline("P_A、P_B").text)
        assertEquals(0, subscriptSpans("P_A、P_B"))
        assertEquals(0, italicSpans("P_A、P_B"))

        assertEquals("msg_id 和 user_name", inline("msg_id 和 user_name").text)
        assertEquals(0, subscriptSpans("msg_id 和 user_name"))
        assertEquals(0, italicSpans("msg_id 和 user_name"))

        assertEquals("foo_bar_baz", inline("foo_bar_baz").text)
        assertEquals(0, subscriptSpans("foo_bar_baz"))
        assertEquals(0, italicSpans("foo_bar_baz"))
    }

    /** 花括号是作者的显式意图，字母也照排下标。 */
    @Test
    fun `braced letter subscript renders`() {
        assertEquals("PA", inline("P_{A}").text)
        assertEquals(1, subscriptSpans("P_{A}"))
    }

    /** 判据里的「词字符」按 ASCII 收，汉字邻居不算，否则「第_1_章」会被拆坏。 */
    @Test
    fun `cjk neighbour is not a script base`() {
        assertEquals("第_1_章", inline("第_1_章").text)
        assertEquals(0, subscriptSpans("第_1_章"))
    }

    /**
     * `^` 与 `_` 共用判据。
     *
     * `[^1]` 的前一个字符是 `[`，不再是上标；主体一侧不设限，所以 `C^2` 照旧排成上标。
     */
    @Test
    fun `caret follows the same predicate`() {
        assertEquals("[^1]", inline("[^1]").text)
        assertEquals(0, superscriptSpans("[^1]"))
        assertEquals("210", inline("2^10").text)
        assertEquals(1, superscriptSpans("2^10"))
        assertEquals(1, superscriptSpans("C^2"))
    }

    /**
     * 孤立下划线不跨句配对，词内下划线不成对。
     *
     * 词字符按 Unicode 判定，因此中文句子里的 `_…_` 保持原文（与 CommonMark、
     * GitHub 一致）；要强调得写成 `*重点*`，或者在标记两侧留空格。
     */
    @Test
    fun `underscore emphasis needs word boundaries`() {
        assertEquals("附录_ 与 表_", inline("附录_ 与 表_").text)
        assertEquals(0, italicSpans("附录_ 与 表_"))

        assertEquals("_ text _", inline("_ text _").text)
        assertEquals(0, italicSpans("_ text _"))

        assertEquals("这是_重点_内容", inline("这是_重点_内容").text)
        assertEquals(0, italicSpans("这是_重点_内容"))

        assertEquals("见 重点 内容", inline("见 _重点_ 内容").text)
        assertEquals(1, italicSpans("见 _重点_ 内容"))
    }

    /** 表格单元格走同一个行内解析，标识符表头不能被拆坏。 */
    @Test
    fun `table cells keep identifiers literal`() {
        val block = MarkdownParser.parse("| msg_id | user_name |").single()
        val cells = (block as MdBlock.Table).rows.first()
        assertEquals("msg_id", inline(cells[0]).text)
        assertEquals(0, subscriptSpans(cells[0]))
        assertEquals("user_name", inline(cells[1]).text)
        assertEquals(0, italicSpans(cells[1]))
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