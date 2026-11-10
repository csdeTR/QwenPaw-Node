package io.qwenpaw.node.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.data.remote.UploadConflictPolicy
import io.qwenpaw.node.ui.common.ChoiceDialog
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 纯文本编辑页。
 *
 * 保存走乐观锁：`If-Match` 带打开文件时的 etag，服务端返回 409 时弹窗让用户
 * 在「重新加载」与「强制覆盖」之间选择。这里不持有状态，草稿与保存结果都在
 * [WorkspaceUiState.edit] 里。
 */
@Composable
internal fun WorkspaceEditor(
    edit: EditState,
    actions: WorkspaceWriteActions,
) {
    var confirmDiscard by remember { mutableStateOf(false) }

    fun requestExit() {
        if (edit.saving) return
        if (edit.dirty) confirmDiscard = true else actions.cancelEdit()
    }

    BackHandler { requestExit() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon = QwenIcons.arrowLeft, description = "返回预览", onClick = ::requestExit)
            Spacer(Modifier.width(4.dp))
            Text(
                text = edit.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (edit.saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            } else if (edit.saved && !edit.dirty) {
                Text(
                    text = "已保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(
                onClick = actions.saveEdit,
                enabled = edit.dirty && !edit.saving,
            ) { Text("保存") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        edit.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        BasicTextField(
            value = edit.draft,
            onValueChange = actions.updateDraft,
            enabled = !edit.saving,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    if (confirmDiscard) {
        ChoiceDialog(
            title = "放弃未保存的修改？",
            message = "当前草稿还没有保存，离开后会丢失。",
            choices = listOf("放弃修改"),
            onPick = {
                confirmDiscard = false
                actions.cancelEdit()
            },
            onDismiss = { confirmDiscard = false },
            dismissLabel = "继续编辑",
        )
    }

    if (edit.conflict) {
        ChoiceDialog(
            title = "文件已在别处修改",
            message = "磁盘上的内容已不是打开时的版本。重新加载会丢弃当前草稿；" +
                "强制覆盖会保存草稿并覆盖外部改动。",
            choices = listOf("重新加载", "强制覆盖"),
            onPick = { index ->
                when (index) {
                    0 -> actions.reloadConflict()
                    else -> actions.forceOverwrite()
                }
            },
            onDismiss = actions.dismissConflict,
        )
    }
}

/** 上传进度与结果。运行中可以取消，等待冲突时切换到覆盖/跳过。 */
@Composable
internal fun UploadDialog(
    upload: UploadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onConflict: (UploadConflictPolicy) -> Unit,
) {
    if (upload.phase == UploadPhase.WAITING_CONFLICT) {
        ChoiceDialog(
            title = "目标目录已有同名文件",
            message = "以下文件已存在：${upload.conflicts.joinToString("、")}。" +
                "覆盖会替换服务端内容，跳过则保留原文件。",
            choices = listOf("覆盖", "跳过"),
            onPick = { index ->
                onConflict(
                    if (index == 0) UploadConflictPolicy.OVERWRITE else UploadConflictPolicy.SKIP,
                )
            },
            onDismiss = onCancel,
        )
        return
    }

    val running = upload.phase == UploadPhase.RUNNING
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("上传文件") },
        text = {
            Column {
                if (running) {
                    Text(
                        text = "正在上传 ${upload.index}/${upload.count}：${upload.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                upload.message?.let { message ->
                    if (!running) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = onCancel) { Text("取消") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
