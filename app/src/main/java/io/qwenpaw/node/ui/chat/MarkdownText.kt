package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import kotlinx.coroutines.launch
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 轻量 Markdown 渲染。
 *
 * QwenPaw 的回复常见结构是：段落、列表、行内代码、围栏代码块、表格、链接。
 * 这里按行扫描处理这些结构，避免引入重型渲染库带来的体积与流式刷新开销。
 * Mermaid 代码块退化为可复制源码 + 提示，不内嵌渲染。
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    // 标题/代码块这类固定尺寸也要跟着聊天字号缩放，否则调大后它们相对偏小。
    val scale = io.qwenpaw.node.ui.theme.LocalChatFontScale.current
    // 段落基准字号：M3 的 MaterialTheme 会 ProvideTextStyle(typography.bodyLarge)，
    // ChatFontScale 作用域内它就是已乘过 chatScale 的 bodyLarge，与下面 Text 的实际字号一致。
    val bodyBase = MaterialTheme.typography.bodyLarge.fontSize.takeOrElse { 16.sp }.value
    // 行内样式（**粗体**、`代码`、*斜体*）的解析结果按块缓存。滚动时进入视口的条目
    // 会反复重组，不缓存就要每次重跑一遍逐字符解析、重建 AnnotatedString。
    // 基准字号也进缓存键：聊天字号或界面字号变化后若复用旧结果，上下标会停在旧字号上。
    val inlines = remember(blocks, scale, bodyBase) {
        blocks.map { block ->
            block.inlineText?.let {
                val base = when (block) {
                    is MdBlock.Heading -> headingSizeSp(block.level, scale)
                    else -> bodyBase
                }
                inline(it, scale, base)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val styled = inlines[index]
            when (block) {
                // 段落不再各自套 SelectionContainer：整条气泡由外层统一提供选区。
                // 每段落套一层会让命中路径上的指针节点翻倍，逐事件的命中测试开销随之上涨。
                // 样式显式写出：正文基准字号正是从 bodyLarge 取的，两处同源才不会漂移。
                is MdBlock.Paragraph -> Text(
                    text = styled!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
                is MdBlock.Heading -> Text(
                    text = styled!!,
                    fontSize = headingSizeSp(block.level, scale).sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )

                is MdBlock.Bullet -> Row {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.padding(end = 6.dp))
                    Text(text = styled!!, style = MaterialTheme.typography.bodyLarge, color = color)
                }

                is MdBlock.Code -> CodeBlock(block.code, block.language)
                is MdBlock.Table -> MarkdownTable(block.rows, color)
                is MdBlock.Math -> LatexView(block.source, color)
                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = styled!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = color.copy(alpha = 0.8f),
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
    }
}

/** 标题字号：层级越深越小，最低 13sp。渲染字号与行内上下标基准共用这一处。 */
internal fun headingSizeSp(level: Int, scale: Float): Float =
    (22 - level * 2).coerceAtLeast(13) * scale

/**
 * 行内上下标相对基准字号的缩放比。
 *
 * 与 `LatexView` 的块级上下标取同一个 0.58，两处保持一致后调参不必分头改。
 */
internal const val SCRIPT_SIZE_RATIO = 0.58f

/**
 * 行内样式：**粗体**、`代码`、*斜体*，以及正文里直接写的上下标。
 *
 * `internal` 而非 `private`：上下标的 span 只能在纯 JVM 单测里断言，
 * 组合函数的渲染结果需要设备测试才能验证，成本高得多。
 *
 * @param scale 聊天字号缩放系数。行内代码按 `13 * scale` 排版。
 * @param baseSize 该块正文的基准字号（sp）。上下标取 `baseSize * 0.58`——
 *   只有基线偏移而不缩字号时，`H_2O` 看起来像一个掉下去的大写字符。
 *   生产调用必须传真实基准：段落/列表/引用是 `bodyLarge`，标题按层级，
 *   表格单元格是 `bodySmall`。默认值只给单测与无基准调用兜底。
 */
internal fun inline(text: String, scale: Float = 1f, baseSize: Float = 16f): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        // 粗体内部不递归解析：上下标在强调里会挤压行高，
                        // 且 `**a_i**` 的写法本身少见，保持原样更稳定。
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x33808080),
                            fontSize = (13 * scale).sp,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            // `\(…\)` 与 `\[…\]`：模型常把行内公式写成这两种定界符形式。
            // 只要闭合就走内联公式，与 `$…$` 共用同一套单行降级。
            text.startsWith("\\(", i) || text.startsWith("\\[", i) -> {
                val close = if (text[i + 1] == '(') "\\)" else "\\]"
                val end = text.indexOf(close, i + 2)
                if (end > i && appendInlineMath(text.substring(i + 2, end))) {
                    i = end + close.length
                } else {
                    append(text[i]); i++
                }
            }

            // 行内公式 `$…$`。判定要避开货币符号：`$` 后不能是空白，
            // 闭合的 `$` 前也不能是空白，且中间不含换行（本函数只处理单行文本）。
            text[i] == '$' && isInlineMathStart(text, i) -> {
                val end = closingInlineMath(text, i)
                if (end > 0 && appendInlineMath(text.substring(i + 1, end))) {
                    i = end + 1
                } else {
                    // 解析失败保留原文，用户至少能看到自己写了什么。
                    append(text[i]); i++
                }
            }

            // 段落里直接写的上下标（`x^2`、`H_2O`）。不经 LaTeX 也要能读，
            // 因为模型在解释里常常只用纯文本写一个指数或下标。
            // 判据对 `^` 与 `_` 同时生效：`^` 原先不设限，`[^1]` 这类写法会被排成上标。
            (text[i] == '^' || text[i] == '_') && isBareScriptAt(text, i) -> {
                val script = readBareScript(text, i + 1)
                if (script == null) {
                    append(text[i]); i++
                } else {
                    val shift = if (text[i] == '^') {
                        BaselineShift.Superscript
                    } else {
                        BaselineShift.Subscript
                    }
                    withStyle(
                        SpanStyle(
                            baselineShift = shift,
                            fontSize = (baseSize * SCRIPT_SIZE_RATIO).sp,
                        ),
                    ) { append(script.first) }
                    i = script.second
                }
            }

            text.startsWith("*", i) -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            text[i] == '_' -> {
                val end = closingUnderscoreEmphasis(text, i)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            else -> {
                append(text[i]); i++
            }
        }
    }
}

/**
 * 追加一段行内公式；解析失败返回 false，由调用方把定界符按字面留下。
 *
 * 行内不排二维结构：分式、根式压成单行文本，否则段落里嵌套公式会撑高行距并破坏换行。
 */
private fun AnnotatedString.Builder.appendInlineMath(source: String): Boolean {
    val node = LatexParser.parse(source) ?: return false
    withStyle(
        SpanStyle(
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
        ),
    ) {
        append(LatexInline.toPlainText(node))
    }
    return true
}

/**
 * 读裸上下标的内容，返回「内容 to 结束下标」。
 *
 * 取花括号里的整段，或紧跟的一串数字：`x^{n+1}` 与 `x^2`、`H_2O` 与 `x_12` 都常见，
 * 数字成串时整段算一个下标，否则 `x_12` 会排成「₁2」这种半截下标。
 */
private fun readBareScript(text: String, start: Int): Pair<String, Int>? {
    if (start >= text.length) return null
    if (text[start] == '{') {
        val close = text.indexOf('}', start + 1)
        if (close <= start + 1) return null
        return text.substring(start + 1, close) to close + 1
    }
    if (!isAsciiDigit(text[start])) return null
    var end = start
    while (end < text.length && isAsciiDigit(text[end])) end++
    return text.substring(start, end) to end
}

/**
 * 正文里「这是裸上下标」的判据，`^` 与 `_` 共用。
 *
 * 两个条件各挡一类误判。前一个字符必须是 ASCII 字母或数字，于是 `[^1]` 这种脚注
 * （前一个是 `[`）和「第_1_章」这种汉字夹注都不排版；后一个字符必须是数字或 `{`，
 * 于是 `P_A`、`msg_id`、`user_name` 保持原文——CommonMark 也不承认词内下划线构成强调，
 * 而标识符在解释文字里出现的频率远高于化学式。
 *
 * 判据落在被标注的一侧而不是主体一侧，代价是 `C^2` 仍会排成上标：
 * 改判主体会让 `x^2`、`2^10` 一起失效，得不偿失。
 */
private fun isBareScriptAt(text: String, index: Int): Boolean {
    val prev = text.getOrNull(index - 1) ?: return false
    val next = text.getOrNull(index + 1) ?: return false
    return prev.isAsciiLetterOrDigit() && (isAsciiDigit(next) || next == '{')
}

/**
 * `_斜体_` 的闭合位置，不构成强调时返回 -1。
 *
 * 上一版把 `*` 与 `_` 合在一起处理，闭合用的是「行内下一个 `_`」：
 * 一句话里任何一个孤立下划线都会和远处那个配上，把中间整段变成斜体。
 * 收紧裸下标之后落进这里的 `_` 变多，不补边界就会把「`P_A、P_B` 被吞掉下划线」
 * 换成「`A、P` 整段斜体」，更难看。
 *
 * 按 CommonMark 补四条：开侧前不接词字符、开侧后不接空白、闭侧前不接空白、
 * 闭侧后不接词字符。词字符按 Unicode 判定（汉字也算），这是「`第_1_章`、
 * `这是_重点_内容` 保持原文」的依据——CommonMark 与 GitHub 同款处理，
 * 中文里要强调请用 `*重点*` 或 `**重点**`，或在两侧留空格：`见 _重点_ 内容`。
 */
private fun closingUnderscoreEmphasis(text: String, start: Int): Int {
    if (text.getOrNull(start - 1)?.isScriptWordChar() == true) return -1
    val afterOpen = text.getOrNull(start + 1) ?: return -1
    if (afterOpen.isWhitespace()) return -1
    var i = start + 1
    while (i < text.length) {
        if (text[i] == '_') {
            if (text[i - 1].isWhitespace()) return -1
            if (text.getOrNull(i + 1)?.isScriptWordChar() == true) return -1
            return i
        }
        i++
    }
    return -1
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigit(this)

private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

/** CommonMark 意义上的「词字符」，汉字与全角数字都算，下划线本身也算。 */
private fun Char.isScriptWordChar(): Boolean = isLetterOrDigit() || this == '_'

/** 行内公式的起始判据：`$` 后紧跟非空白，且不是 `$$`（那是块级定界符）。 */
private fun isInlineMathStart(text: String, index: Int): Boolean {
    if (text.getOrElse(index + 1) { ' ' }.isWhitespace()) return false
    var next = index + 1
    while (next < text.length && text[next] == '$') next++
    return next == index + 1
}

/**
 * 找行内公式的闭合 `$`，返回其下标；找不到返回 -1。
 *
 * 要求闭合的 `$` 前不是空白，避免把 `$5 and $10` 里的第一个美元符号
 * 当成公式起点。中间出现的 `$$` 直接判为非公式，交给块级逻辑处理。
 */
private fun closingInlineMath(text: String, start: Int): Int {
    var i = start + 1
    while (i < text.length) {
        if (text[i] == '$') {
            if (text.getOrElse(i - 1) { ' ' }.isWhitespace()) return -1
            if (text.getOrElse(i + 1) { ' ' } == '$') return -1
            return i
        }
        i++
    }
    return -1
}

@Composable
private fun CodeBlock(code: String, language: String?) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isMermaid = language.equals("mermaid", ignoreCase = true)
    // 代码块用固定字号，也要跟着聊天字号缩放，否则正文调大后代码块明显偏小。
    val scale = io.qwenpaw.node.ui.theme.LocalChatFontScale.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    isMermaid -> "mermaid（源码）"
                    !language.isNullOrBlank() -> language
                    else -> "代码"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = QwenIcons.copy,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        SelectionContainer {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = (13 * scale).sp,
                lineHeight = (19 * scale).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
        if (isMermaid) {
            Text(
                text = "Mermaid 图表暂以源码展示，可复制到支持渲染的工具查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
    // 显式使用 scope，保留协程作用域给后续扩展（如复制提示）。
    scope.launch { }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: Color) {
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    val scale = io.qwenpaw.node.ui.theme.LocalChatFontScale.current
    // 单元格用 bodySmall 渲染，上下标基准跟着它走，而不是段落基准。
    val cellBase = MaterialTheme.typography.bodySmall.fontSize.takeOrElse { 12.sp }.value
    // 单元格的行内样式按内容缓存：一张十几行的表格有几十个单元格，
    // 若每次重组都逐字符重解析，可见表格在滚动期间会持续产生额外的主线程开销。
    // 基准与缩放一并进键，理由同段落缓存。
    val cells = remember(rows, columns, scale, cellBase) {
        rows.map { row ->
            (0 until columns).map { col -> inline(row.getOrElse(col) { "" }, scale, cellBase) }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { index, row ->
            Row {
                repeat(columns) { col ->
                    Text(
                        text = cells[index][col],
                        color = color,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .width(120.dp)
                            .padding(4.dp),
                    )
                }
            }
            if (index == 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }
    }
}

/** Markdown 块模型。 */
sealed interface MdBlock {
    /** 需要做行内样式解析的原文；代码块/表格为 null，不走行内缓存。 */
    val inlineText: String?

    data class Paragraph(val text: String) : MdBlock {
        override val inlineText: String get() = text
    }

    data class Heading(val level: Int, val text: String) : MdBlock {
        override val inlineText: String get() = text
    }

    data class Bullet(val text: String) : MdBlock {
        override val inlineText: String get() = text
    }

    data class Code(val code: String, val language: String?) : MdBlock {
        override val inlineText: String? get() = null
    }

    data class Table(val rows: List<List<String>>) : MdBlock {
        override val inlineText: String? get() = null
    }

    data class Quote(val text: String) : MdBlock {
        override val inlineText: String get() = text
    }

    /**
     * 独占一行的公式，`$$…$$` 或 `\[…\]`。
     *
     * 只有闭合后才成为本类型；未闭合时按普通段落原样显示，
     * 这是流式输出必须的处理：`$$` 先于公式内容到达，此刻渲染会解析失败。
     */
    data class Math(val source: String) : MdBlock {
        override val inlineText: String? get() = null
    }
}

/** 一行被块级公式定界符切开后的片段。 */
private sealed interface LineSegment {
    data class Text(val text: String) : LineSegment

    data class Math(val source: String) : LineSegment
}

/**
 * 把一行按成对的 `$$…$$` 或 `\[…\]` 切开；没有闭合的定界符时返回 null。
 *
 * 定界符常出现在模型写的「前文 + 公式 + 后文」里，只在行首识别会整行漏掉。
 * 只认同行内闭合：跨行的公式由行首分支处理，半截公式不能交给渲染层。
 */
private fun splitDisplayMath(line: String): List<LineSegment>? {
    val segments = mutableListOf<LineSegment>()
    var textStart = 0
    var i = 0
    var found = false
    while (i < line.length) {
        val close = when {
            line.startsWith("$$", i) -> "$$"
            line.startsWith("\\[", i) -> "\\]"
            else -> {
                i++
                continue
            }
        }
        // 被反斜杠转义的定界符是字面量，例如价格写法 `\$5`。
        if (i > 0 && line[i - 1] == '\\') {
            i += 2
            continue
        }
        val end = line.indexOf(close, i + 2)
        if (end < 0) {
            i += 2
            continue
        }
        if (i > textStart) segments += LineSegment.Text(line.substring(textStart, i))
        segments += LineSegment.Math(line.substring(i + 2, end))
        i = end + close.length
        textStart = i
        found = true
    }
    if (!found) return null
    if (textStart < line.length) segments += LineSegment.Text(line.substring(textStart))
    return segments
}

/**
 * 解析期用到的正则。
 *
 * 必须是文件级常量：写在循环里会让**每一行、每一个表格单元格**都重新编译一次
 * `java.util.regex.Pattern`。解析本身已按内容缓存，但列表项滚出视口后会被销毁、
 * 再滚回来时要重解析一遍，这些重复编译就压在滚动的每一帧上。
 */
private val ORDERED_LIST_RE = Regex("^\\d+[.)]\\s+.*")
private val ORDERED_LIST_PREFIX_RE = Regex("^\\d+[.)]\\s+")
private val TABLE_SEPARATOR_RE = Regex("^:?-{2,}:?$")

/** 逐行解析 Markdown。保持无状态，便于单元测试。 */
object MarkdownParser {

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val paragraph = StringBuilder()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += MdBlock.Paragraph(paragraph.toString().trim())
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim().ifBlank { null }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    blocks += MdBlock.Code(code.toString().trimEnd('\n'), language)
                }

                // 独占一行的公式。`$$` 可以单行闭合，也可以跨行；
                // 未闭合时落到 else 分支按普通段落显示，避免流式输出中途渲染半个公式。
                trimmed.startsWith("$$") -> {
                    val closed = collectDollarMath(lines, i)
                    if (closed != null) {
                        flushParagraph()
                        blocks += MdBlock.Math(closed.first)
                        i = closed.second
                    } else {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line)
                    }
                }

                trimmed.startsWith("\\[") -> {
                    val closed = collectBracketMath(lines, i)
                    if (closed != null) {
                        flushParagraph()
                        blocks += MdBlock.Math(closed.first)
                        i = closed.second
                    } else {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line)
                    }
                }

                trimmed.isEmpty() -> flushParagraph()

                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    flushParagraph()
                    blocks += MdBlock.Bullet(trimmed.drop(2).trim())
                }

                trimmed.matches(ORDERED_LIST_RE) -> {
                    flushParagraph()
                    blocks += MdBlock.Bullet(trimmed.replaceFirst(ORDERED_LIST_PREFIX_RE, ""))
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
                }

                trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                    flushParagraph()
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size) {
                        val candidate = lines[i].trim()
                        if (!candidate.startsWith("|") || !candidate.endsWith("|")) break
                        val cells = candidate.trim('|').split('|').map { it.trim() }
                        // 跳过 Markdown 表格的分隔行 |---|---|
                        if (!cells.all { it.matches(TABLE_SEPARATOR_RE) }) {
                            rows += cells
                        }
                        i++
                    }
                    blocks += MdBlock.Table(rows)
                    continue
                }

                else -> {
                    // 段落里可能夹着已经闭合的块级公式（`前文 $$x^2$$ 后文`）。
                    // 未闭合时不拆：流式输出会先到达半个定界符，此时必须按文本显示。
                    val segments = splitDisplayMath(line)
                    if (segments == null) {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line)
                    } else {
                        flushParagraph()
                        segments.forEach { segment ->
                            when (segment) {
                                is LineSegment.Text ->
                                    if (segment.text.isNotBlank()) {
                                        blocks += MdBlock.Paragraph(segment.text.trim())
                                    }

                                is LineSegment.Math -> blocks += MdBlock.Math(segment.source.trim())
                            }
                        }
                    }
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * 收集 `$$…$$` 包裹的块级公式，返回「公式源 to 下一行下标」。
     *
     * 从 [start] 行开始找闭合的 `$$`。同一行内闭合（`$$x^2$$`）与跨行闭合都支持；
     * 找不到闭合定界符时返回 null，由调用方按普通段落处理——
     * 流式输出时 `$$` 会先到达，此刻若强行渲染会得到半条公式。
     */
    private fun collectDollarMath(lines: List<String>, start: Int): Pair<String, Int>? {
        val body = StringBuilder()
        var i = start
        while (i < lines.size) {
            val text = lines[i].trim()
            if (i == start) {
                // 首行：去掉前导 `$$`，看行内是否同时有结尾的 `$$`。
                val rest = text.removePrefix("$$")
                val end = rest.indexOf("$$")
                if (end >= 0) {
                    // 单行闭合时两侧空格会在渲染里变成多余缩进，统一去掉。
                    body.append(rest.substring(0, end))
                    return body.toString().trim() to i + 1
                }
                body.append(rest)
            } else {
                val end = text.indexOf("$$")
                if (end >= 0) {
                    body.append('\n').append(text.substring(0, end))
                    return body.toString().trim() to i + 1
                }
                body.append('\n').append(text)
            }
            i++
        }
        return null
    }

    /** `\[…\]` 形式的块级公式，语义与 `$$…$$` 相同，只是定界符不同。 */
    private fun collectBracketMath(lines: List<String>, start: Int): Pair<String, Int>? {
        val body = StringBuilder()
        var i = start
        while (i < lines.size) {
            val text = lines[i].trim()
            if (i == start) {
                val rest = text.removePrefix("\\[")
                val end = rest.indexOf("\\]")
                if (end >= 0) {
                    // 同一行闭合（`\[ E = mc^2 \]`）时定界符两侧常带空格，去掉再交给解析器。
                    body.append(rest.substring(0, end))
                    return body.toString().trim() to i + 1
                }
                body.append(rest)
            } else {
                val end = text.indexOf("\\]")
                if (end >= 0) {
                    body.append('\n').append(text.substring(0, end))
                    return body.toString().trim() to i + 1
                }
                body.append('\n').append(text)
            }
            i++
        }
        return null
    }
}
