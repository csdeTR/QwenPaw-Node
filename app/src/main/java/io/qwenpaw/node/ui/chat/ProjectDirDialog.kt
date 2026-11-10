package io.qwenpaw.node.ui.chat

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.BrowseDirsDto
import io.qwenpaw.node.data.model.ProjectDirDto
import io.qwenpaw.node.ui.theme.LocalCaptionColor
import io.qwenpaw.node.ui.theme.QwenIcons
import kotlinx.coroutines.launch

/**
 * 智能体工作区设置。
 *
 * 这里填的是**服务端上的绝对路径**，与手机本地文件系统无关。工具在这个目录下
 * 读取和写入文件，因此改动会直接影响智能体能看到什么。
 *
 * 输入框与浏览器并存：知道路径的直接填，不知道的从 [onBrowse] 逐层点进去，
 * 省去在手机上凭记忆敲一长串路径。浏览器只列目录、不列文件。
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
    onBrowse: suspend (String) -> BrowseDirsDto? = { null },
) {
    val shownPath = current?.path?.takeIf { it.isNotBlank() }
        ?: current?.workspaceDir.orEmpty()
    var text by remember(current) { mutableStateOf(shownPath) }
    var browse by remember(current) { mutableStateOf<BrowseDirsDto?>(null) }
    var loading by remember(current) { mutableStateOf(false) }
    var failed by remember(current) { mutableStateOf(false) }
    val isDefault = current?.isWorkspaceDefault ?: true
    val scope = rememberCoroutineScope()

    fun load(path: String) {
        loading = true
        failed = false
        scope.launch {
            val result = onBrowse(path)
            loading = false
            if (result == null) {
                failed = true
            } else {
                browse = result
            }
        }
    }

    // 初次打开以输入框内容为起点，空则从用户主目录开始。
    LaunchedEffect(Unit) {
        load(shownPath.ifBlank { "~" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能体工作区") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("恢复工作区默认目录")
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "浏览目录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { browse?.parent?.let { load(it) } },
                        enabled = !loading && browse?.parent != null,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("上级", style = MaterialTheme.typography.labelMedium) }
                    TextButton(
                        onClick = { load(browse?.current ?: text.ifBlank { "~" }) },
                        enabled = !loading,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("刷新", style = MaterialTheme.typography.labelMedium) }
                }
                Text(
                    text = browse?.current ?: text.ifBlank { "~" },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalCaptionColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp),
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.Center),
                            strokeWidth = 2.dp,
                        )

                        failed -> Text(
                            text = "无法读取这个目录，可能不存在或没有权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        browse?.dirs.isNullOrEmpty() -> Text(
                            text = "这个目录下没有子目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalCaptionColor.current,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(browse?.dirs.orEmpty(), key = { it.path }) { dir ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { load(dir.path) }
                                        .padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = QwenIcons.folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = dir.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                browse?.current?.takeIf { it.isNotBlank() }?.let { path ->
                    TextButton(
                        onClick = { text = path },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("使用此目录") }
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
