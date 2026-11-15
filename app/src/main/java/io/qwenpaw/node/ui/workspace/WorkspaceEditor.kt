package io.qwenpaw.node.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.remote.UploadConflictPolicy
import io.qwenpaw.node.ui.common.ChoiceDialog
import io.qwenpaw.node.ui.common.PlainTextEditor

/**
 * 纯文本编辑页。
 *
 * 保存走乐观锁：`If-Match` 带打开文件时的 etag，服务端返回 409 时弹窗让用户
 * 在「重新加载」与「强制覆盖」之间选择。这里不持有状态，草稿与保存结果都在
 * [WorkspaceUiState.edit] 里。
 *
 * 外壳（顶栏、输入框、未保存确认）用 [PlainTextEditor]；409 的两个选项留在本文件，
 * 因为那是 `etag` 语义，只有文件编辑会遇到。
 */
@Composable
internal fun WorkspaceEditor(
    edit: EditState,
    actions: WorkspaceWriteActions,
) {
    PlainTextEditor(
        title = edit.title,
        backDescription = "返回预览",
        draft = edit.draft,
        dirty = edit.dirty,
        saving = edit.saving,
        saved = edit.saved,
        error = edit.error,
        onDraftChange = actions.updateDraft,
        onSave = actions.saveEdit,
        onExit = actions.cancelEdit,
    )

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
