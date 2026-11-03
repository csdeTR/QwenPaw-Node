package io.qwenpaw.node.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 破坏性操作的统一确认弹窗。
 *
 * 与普通 AlertDialog 的差别只有一处，但是关键的一处：[impacts] 把「将影响哪些对象」
 * 逐条列出来。用户点删除之前需要知道删掉的是什么——是一个空会话，还是几百条
 * 带交付文件的记录，两者的心理成本完全不同。
 *
 * [undoable] 为真时才在按钮上写「撤销窗口」，用来区分「删了还能回来」与「删了就没了」。
 * 服务端的 DELETE 类端点没有回收站，因此这类调用方必须传 false，
 * 不能为了让文案好看而承诺一个做不到的撤销。
 *
 * [confirmDanger] 为 false 时确认按钮改用常规强调色。恢复默认一类的操作同样需要
 * 二次确认，但它不销毁数据，标成红色会让用户以为后果比实际更重。
 */
@Composable
fun DestructiveActionDialog(
    title: String,
    message: String,
    impacts: List<String> = emptyList(),
    confirmLabel: String = "删除",
    undoable: Boolean = false,
    confirmDanger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = QwenIcons.alertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        },
        title = {
            Text(text = title, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (impacts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (undoable) "将影响以下内容（可撤销）" else "将影响以下内容（不可撤销）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (undoable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    impacts.forEach { line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (confirmDanger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}