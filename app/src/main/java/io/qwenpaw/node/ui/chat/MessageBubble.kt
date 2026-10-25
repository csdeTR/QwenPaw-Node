package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import io.qwenpaw.node.ui.theme.CodeTextStyle
import io.qwenpaw.node.ui.theme.MessageMaxWidth

/** 单条消息气泡。 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onRetry: (ChatMessage) -> Unit = {},
) {
    when (message.kind) {
        ChatMessage.Kind.REASONING -> ReasoningBlock(message)
        ChatMessage.Kind.TOOL -> ToolCardBlock(message)
        ChatMessage.Kind.TEXT -> TextBubble(message, modifier, onRetry)
    }
}

@Composable
private fun TextBubble(
    message: ChatMessage,
    modifier: Modifier,
    onRetry: (ChatMessage) -> Unit,
) {
    val isUser = message.role == Role.USER
    val container = when {
        message.error != null -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        message.error != null -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = MessageMaxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    ),
                )
                .background(container)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (message.error != null) {
                    // 错误文案是纯文本，不经过 Markdown 解析，避免异常内容被当作标记吞掉。
                    Text(
                        text = message.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                } else {
                    SelectionContainer {
                        MarkdownText(
                            markdown = if (message.streaming && message.text.isEmpty()) "…" else message.text,
                            color = contentColor,
                        )
                    }
                }
                // 发送失败的提示只做「重试」入口，不做自动补发。
                if (message.error != null && message.retryText != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { onRetry(message) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("重试", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** 思考过程，默认折叠。 */
@Composable
private fun ReasoningBlock(message: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) "思考过程" else "思考过程（点击展开）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 工具调用卡片：显示工具名、参数，输出可折叠。 */
@Composable
private fun ToolCardBlock(message: ChatMessage) {
    val tool = message.tool ?: return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = tool.name.ifBlank { "工具调用" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (tool.done) "完成" else "运行中",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tool.done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            if (tool.arguments.isNotBlank() && !expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tool.arguments.replace('\n', ' '),
                    style = CodeTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                if (tool.arguments.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("参数", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(tool.arguments, style = CodeTextStyle) }
                }
                val output = tool.output
                if (!output.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("输出", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Text(
                            text = output.take(MAX_TOOL_OUTPUT_CHARS),
                            style = CodeTextStyle,
                        )
                    }
                    if (output.length > MAX_TOOL_OUTPUT_CHARS) {
                        Text(
                            text = "（输出过长，已截断）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 大输出截断阈值，避免超长工具输出拖慢渲染。 */
private const val MAX_TOOL_OUTPUT_CHARS = 4000
