package io.qwenpaw.node.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * 多选一的中性对话框：标题 + 说明 + N 个并列选项 + 一个取消。
 *
 * 与 [DestructiveActionDialog] 的分工是「是否破坏性」：删除、清空一类用那一个，
 * 因为它要逐条列出影响范围；这里用于两三个并列、无破坏性的分叉，
 * 例如「显示原文 / 不显示」。
 *
 * 所有按钮放在同一行：`AlertDialog` 的 `confirmButton` 与 `dismissButton` 是两个固定槽位，
 * 把选项塞进 `confirmButton`、取消塞进 `dismissButton` 会让「不显示」与「取消」
 * 分列两端，读起来像是两种不同程度的确认。这里改为全部同排，语义才并列。
 *
 * 点击选项回调下标，由调用方决定后续；对话框自身不持有状态，关闭时机也交给调用方。
 */
@Composable
fun ChoiceDialog(
    title: String,
    choices: List<String>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissLabel: String = "取消",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.SemiBold) },
        text = if (message == null) {
            null
        } else {
            { Text(text = message, style = MaterialTheme.typography.bodyMedium) }
        },
        confirmButton = {
            Row {
                choices.forEachIndexed { index, label ->
                    TextButton(onClick = { onPick(index) }) { Text(label) }
                }
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            }
        },
    )
}