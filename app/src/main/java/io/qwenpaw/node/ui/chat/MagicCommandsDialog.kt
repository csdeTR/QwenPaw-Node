package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.qwenpaw.node.data.model.SlashCommandDto
import io.qwenpaw.node.ui.CommandCopy
import io.qwenpaw.node.ui.theme.LocalCaptionColor
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 魔法命令：一次列出全部可用的斜杠命令。
 *
 * 与输入框里打 `/` 的补全同源，区别是补全要敲出前缀、且只给名字。
 * 这里一次列全并附一句说明，适合「记得有这个功能、想不起命令名」的情况。
 * 单击把命令写进输入框并关闭；长按叠一层详情，给出更具体的作用与一条用法示例。
 */
@Composable
internal fun MagicCommandsDialog(
    commands: List<SlashCommandDto>,
    onPick: (SlashCommandDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var detail by remember { mutableStateOf<SlashCommandDto?>(null) }
    val groups = remember(commands) {
        groupByCategory(presentableCommands(commands, ""))
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "魔法命令",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // 长按这一层需要提示，否则没有可发现的入口。
                    Text(
                        text = "点按填入输入框，长按查看用法",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalCaptionColor.current,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QwenIcons.close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (groups.isEmpty()) {
                Text(
                    text = "没有读取到命令列表。可以在输入框直接输入斜杠命令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    groups.forEach { (category, items) ->
                        item(key = "category-$category") {
                            Text(
                                text = CommandCopy.category(category).ifBlank { "其他" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 18.dp,
                                    end = 18.dp,
                                    top = 12.dp,
                                    bottom = 2.dp,
                                ),
                            )
                        }
                        items(items, key = { it.name }) { command ->
                            CommandRow(
                                command = command,
                                onClick = { onPick(command) },
                                onLongClick = { detail = command },
                            )
                        }
                    }
                }
            }
        }
    }
    detail?.let { command ->
        CommandDetailDialog(command = command, onDismiss = { detail = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandRow(
    command: SlashCommandDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text = "/${command.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 部分命令（例如服务端新增的 /new）没有描述，此时只留命令名，不留空行。
        val description = CommandCopy.description(command.name)
            ?: command.description?.takeIf { it.isNotBlank() }
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 长按后的详情：比列表里那句更具体的作用，加一条可以直接照抄的用法示例。 */
@Composable
private fun CommandDetailDialog(command: SlashCommandDto, onDismiss: () -> Unit) {
    val description = CommandCopy.description(command.name)
        ?: command.description?.takeIf { it.isNotBlank() }
    val example = CommandCopy.example(command.name) ?: "/${command.name}"
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
        ) {
            Text(
                text = "/${command.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = CommandCopy.category(command.category).ifBlank { "其他" },
                style = MaterialTheme.typography.labelSmall,
                color = LocalCaptionColor.current,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "作用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = CommandCopy.detail(command.name) ?: description ?: "服务端未提供这条命令的说明。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "示例",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = example,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
