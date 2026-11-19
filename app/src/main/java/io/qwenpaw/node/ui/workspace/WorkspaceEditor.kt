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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
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
        backDescription = stringResource(R.string.workspace_edit_back),
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
            title = stringResource(R.string.workspace_edit_conflict_title),
            message = stringResource(R.string.workspace_edit_conflict_message),
            choices = listOf(
                stringResource(R.string.workspace_edit_reload),
                stringResource(R.string.workspace_edit_force_overwrite),
            ),
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
            title = stringResource(R.string.workspace_upload_conflict_title),
            message = stringResource(
                R.string.workspace_upload_conflict_message,
                upload.conflicts.joinToString(stringResource(R.string.workspace_list_separator)),
            ),
            choices = listOf(
                stringResource(R.string.workspace_upload_overwrite),
                stringResource(R.string.workspace_upload_skip),
            ),
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
        title = { Text(stringResource(R.string.workspace_upload)) },
        text = {
            Column {
                if (running) {
                    Text(
                        text = stringResource(
                            R.string.workspace_upload_progress,
                            upload.index,
                            upload.count,
                            upload.name,
                        ),
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
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}
