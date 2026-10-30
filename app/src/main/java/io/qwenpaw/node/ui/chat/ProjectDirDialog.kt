package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.ProjectDirDto
import io.qwenpaw.node.ui.theme.LocalCaptionColor

/**
 * 工作区目录设置。
 *
 * 这里填的是**服务端上的绝对路径**，与手机本地文件系统无关。工具在这个目录下
 * 读取和写入文件，因此改动会直接影响智能体能看到什么。
 *
 * 当前目录等于工作区默认目录时，`path` 可能为空，此时把 `workspace_dir` 显示出来，
 * 免得输入框空白让人不知道该填什么。
 */
@Composable
fun ProjectDirDialog(
    current: ProjectDirDto?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    val shownPath = current?.path?.takeIf { it.isNotBlank() }
        ?: current?.workspaceDir.orEmpty()
    var text by remember(current) { mutableStateOf(shownPath) }
    val isDefault = current?.isWorkspaceDefault ?: true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工作区目录") },
        text = {
            Column {
                Text(
                    text = "智能体在这个目录下读写文件。这里填的是服务端上的绝对路径，不是手机路径。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (isDefault) "当前为工作区默认目录" else "当前为自定义目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("/home/user/project") },
                )
                // 只有已经脱离默认目录时才需要这个动作，否则它是无意义的空操作。
                if (!isDefault) {
                    TextButton(
                        onClick = onReset,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("恢复工作区默认目录")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}