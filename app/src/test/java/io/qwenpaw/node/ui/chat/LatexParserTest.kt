package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 解析器与行内降级。
 *
 * 未登记的命令按字面输出，只有结构性错误（括号不配平、`\left` 缺 `\right`、
 * `\begin` 缺 `\end`、嵌套过深）才返回 null，让渲染层退回源码。
 */
class LatexParserTest {

    @Test
    fun `greek letters and relations map to unicode`() {
        val node = LatexParser.parse("\\alpha \\le \\beta")!!
        assertEquals("α ≤ β", LatexInline.toPlainText(node))
    }

    @Test
    fun `fraction produces fraction node`() {
        val node = LatexParser.parse("\\frac{a}{b}")
        assertTrue(node is MathNode.Fraction)
    }

    @Test
    fun `fraction with operator numerator is parenthesized inline`() {
        val node = LatexParser.parse("\\frac{a+b}{c}")!!
        assertEquals("(a+b)⁄c", LatexInline.toPlainText(node))
    }

    @Test
    fun `sqrt without index`() {
        val node = LatexParser.parse("\\sqrt{x}")!!
        assertEquals("√(x)", LatexInline.toPlainText(node))
    }

    @Test
    fun `sqrt with index`() {
        val node = LatexParser.parse("\\sqrt[3]{x}")!!
        assertEquals("3√(x)", LatexInline.toPlainText(node))
    }

    @Test
    fun `superscript digits become unicode`() {
        val node = LatexParser.parse("x^2")!!
        assertEquals("x²", LatexInline.toPlainText(node))
    }

    @Test
    fun `subscript digits become unicode`() {
        val node = LatexParser.parse("a_1")!!
        assertEquals("a₁", LatexInline.toPlainText(node))
    }

    /** 可映射的字符逐个换成上标，`+` 与数字都有对应字形。 */
    @Test
    fun `script maps each mappable character`() {
        val node = LatexParser.parse("x^{2+3}")!!
        assertEquals("x²⁺³", LatexInline.toPlainText(node))
    }

    /** 有一个字符映射不到时整段退回 `^`，避免出现半上标半普通字的混排。 */
    @Test
    fun `script with unmappable content falls back to caret`() {
        val node = LatexParser.parse("x^{2a3}")!!
        assertEquals("x^2a3", LatexInline.toPlainText(node))
    }

    @Test
    fun `big operator keeps its limits`() {
        val node = LatexParser.parse("\\sum_{i=1}^{n}")!!
        assertEquals("∑ᵢ₌₁ⁿ", LatexInline.toPlainText(node))
    }

    @Test
    fun `left right pair produces delimiter node`() {
        val node = LatexParser.parse("\\left( x \\right)")!!
        assertTrue(node is MathNode.Delim)
        assertEquals("(x)", LatexInline.toPlainText(node))
    }

    @Test
    fun `text command keeps raw content`() {
        val node = LatexParser.parse("\\text{当 } x>0")!!
        assertEquals("当  x>0", LatexInline.toPlainText(node))
    }

    /**
     * 未登记的命令按字面输出。
     *
     * 模型给的公式里总会夹带几个本渲染器没有排版能力的命令，若一处不认就整条返回 null，
     * 实机上几乎整篇公式都会退回源码。参数仍照常解析，所以 `\foo{x}` 读作 `\foox`。
     */
    @Test
    fun `unknown command degrades to literal text`() {
        val node = LatexParser.parse("\\foobar{x}")
        assertNotNull(node)
        assertTrue(LatexInline.toPlainText(node!!).contains("\\foobar"))
    }

    @Test
    fun `unbalanced braces are rejected`() {
        assertNull(LatexParser.parse("\\frac{a}{b"))
        assertNull(LatexParser.parse("x}"))
    }

    @Test
    fun `left without right is rejected`() {
        assertNull(LatexParser.parse("\\left( x"))
    }

    @Test
    fun `too deeply nested is rejected`() {
        val deep = "\\frac{".repeat(12) + "x" + "}{x}".repeat(12)
        assertNull(LatexParser.parse(deep))
    }

    @Test
    fun `blank source parses to null`() {
        assertNull(LatexParser.parse(""))
        assertNull(LatexParser.parse("   "))
    }

    @Test
    fun `layout-only commands are ignored not fatal`() {
        // \displaystyle 不改变内容，若当成未知命令会让整条公式降级成源码。
        val node = LatexParser.parse("\\displaystyle \\int_0^1 f(x) dx")
        assertNotNull(node)
    }

    @Test
    fun `isClosed detects unterminated math`() {
        // 注意 Kotlin 字符串模板：`$` 必须转义，否则 `$x` 会被当成变量引用。
        assertEquals(true, LatexParser.isClosed("\$\$x^2\$\$", "\$\$", "\$\$"))
        assertFalse(LatexParser.isClosed("\$\$x^2", "\$\$", "\$\$"))
        assertFalse(LatexParser.isClosed("没有公式", "\$\$", "\$\$"))
    }

    @Test
    fun `cases environment splits rows and columns`() {
        val node = LatexParser.parse("\\begin{cases} x, & x \\ge 0 \\\\ -x, & x < 0 \\end{cases}")
        assertTrue(node is MathNode.Env)
        val env = node as MathNode.Env
        assertEquals(EnvKind.CASES, env.kind)
        assertEquals(2, env.rows.size)
        assertTrue(env.rows.all { it.size == 2 })
        assertEquals("x,", LatexInline.toPlainText(env.rows[0][0]))
        assertEquals("x ≥ 0", LatexInline.toPlainText(env.rows[0][1]))
    }

    @Test
    fun `aligned environment keeps alignment marks in their cells`() {
        val env = LatexParser.parse("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}") as MathNode.Env
        assertEquals(EnvKind.ALIGNED, env.kind)
        assertEquals(2, env.rows.size)
        assertEquals("= b", LatexInline.toPlainText(env.rows[0][1]))
        assertEquals("a = b ; c = d", LatexInline.toPlainText(env))
    }

    @Test
    fun `pmatrix environment is a two by two grid`() {
        val env = LatexParser.parse("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}") as MathNode.Env
        assertEquals(EnvKind.PMATRIX, env.kind)
        assertEquals(listOf(2, 2), env.rows.map { it.size })
        assertEquals("1" to "2", cellTexts(env.rows[0]))
        assertEquals("3" to "4", cellTexts(env.rows[1]))
    }

    /** `\begin{array}` 后的列格式参数只影响列宽，解析时跳过后内容仍然完整。 */
    @Test
    fun `array environment skips column spec`() {
        val env = LatexParser.parse("\\begin{array}{l|c} 1 & 2 \\end{array}") as MathNode.Env
        assertEquals(EnvKind.ARRAY, env.kind)
        assertEquals("1" to "2", cellTexts(env.rows.single()))
    }

    @Test
    fun `mathbb maps to blackboard letters`() {
        assertEquals("ℝ", LatexInline.toPlainText(LatexParser.parse("\\mathbb{R}")!!))
        // 没有对应字形的字母保持原样，不能因此失败。
        assertEquals("X", LatexInline.toPlainText(LatexParser.parse("\\mathbb{X}")!!))
    }

    @Test
    fun `vec produces accent node`() {
        val accent = LatexParser.parse("\\vec{v}") as MathNode.Accent
        assertEquals("→", accent.mark)
        assertEquals("v", LatexInline.toPlainText(accent.body))
    }

    @Test
    fun `dots maps to ellipsis`() {
        assertEquals("…", LatexInline.toPlainText(LatexParser.parse("\\dots")!!))
    }

    /** 公式编号与边框不影响内容，`\tag` 整段丢弃、`\boxed` 只留内容。 */
    @Test
    fun `tag is dropped and boxed keeps its content`() {
        assertEquals("x", LatexInline.toPlainText(LatexParser.parse("\\boxed{x}")!!))
        assertEquals("x", LatexInline.toPlainText(LatexParser.parse("x \\tag{1}")!!))
    }

    private fun cellTexts(row: List<MathNode>): Pair<String, String> =
        LatexInline.toPlainText(row[0]) to LatexInline.toPlainText(row[1])
}