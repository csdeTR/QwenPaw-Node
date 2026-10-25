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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> SelectableParagraph(block.text, color)
                is MdBlock.Heading -> Text(
                    text = inline(block.text),
                    fontSize = (22 - block.level * 2).coerceAtLeast(13).sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )

                is MdBlock.Bullet -> Row {
                    Text("•", color = color, modifier = Modifier.padding(end = 6.dp))
                    Text(text = inline(block.text), color = color)
                }

                is MdBlock.Code -> CodeBlock(block.code, block.language)
                is MdBlock.Table -> MarkdownTable(block.rows, color)
                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = inline(block.text),
                        color = color.copy(alpha = 0.8f),
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableParagraph(text: String, color: Color) {
    SelectionContainer {
        Text(text = inline(text), color = color)
    }
}

/** 行内样式：**粗体**、`代码`、*斜体*。 */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
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
                            fontSize = 13.sp,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("*", i) || text.startsWith("_", i) -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                if (end > i + 1) {
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

@Composable
private fun CodeBlock(code: String, language: String?) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isMermaid = language.equals("mermaid", ignoreCase = true)
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
                    imageVector = Icons.Default.ContentCopy,
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
                fontSize = 13.sp,
                lineHeight = 19.sp,
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
                        text = inline(row.getOrElse(col) { "" }),
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
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Code(val code: String, val language: String?) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
}

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

                trimmed.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                    flushParagraph()
                    blocks += MdBlock.Bullet(trimmed.replaceFirst(Regex("^\\d+[.)]\\s+"), ""))
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
                        if (!cells.all { it.matches(Regex("^:?-{2,}:?$")) }) {
                            rows += cells
                        }
                        i++
                    }
                    blocks += MdBlock.Table(rows)
                    continue
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }
}
