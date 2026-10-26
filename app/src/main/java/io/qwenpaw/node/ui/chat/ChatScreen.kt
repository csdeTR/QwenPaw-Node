package io.qwenpaw.node.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.voice.VoiceState
import kotlinx.coroutines.launch

/** 聊天页。 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    modelName: String? = null,
    voiceState: VoiceState = VoiceState.Idle,
    voicePartial: String = "",
    voiceAvailable: Boolean = false,
    imageLoader: ChatImageLoader? = null,
    connection: io.qwenpaw.node.data.remote.Connection? = null,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onApprove: (io.qwenpaw.node.data.model.ApprovalDto) -> Unit,
    onDeny: (io.qwenpaw.node.data.model.ApprovalDto) -> Unit,
    onRetry: (ChatMessage) -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onArchive: () -> Unit = {},
    onExport: () -> Unit = {},
    onSetThinking: (String) -> Unit = {},
    onSetGroup: (String?) -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var showContext by remember { mutableStateOf(false) }

    // 只有停留在底部时才跟随新消息，往上翻历史时不打断阅读。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 24
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChatTopBar(
                title = state.currentChatName,
                running = state.running,
                contextUsage = state.contextUsage,
                pinned = state.currentChatPinned,
                thinking = state.thinking,
                groups = state.groups,
                currentGroupId = state.currentChatGroupId,
                onBack = onBack,
                onRename = {
                    renameText = state.currentChatName
                    renaming = true
                },
                onDelete = { deleting = true },
                onTogglePin = onTogglePin,
                onArchive = onArchive,
                onExport = onExport,
                onContextClick = { showContext = true },
                onSetThinking = onSetThinking,
                onSetGroup = onSetGroup,
            )
        },
        bottomBar = {
            Column {
                state.pendingApprovals.firstOrNull()?.let { approval ->
                    ApprovalBanner(
                        approval = approval,
                        onApprove = { onApprove(approval) },
                        onDeny = { onDeny(approval) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                AnimatedVisibility(visible = state.error != null) {
                    ErrorBar(text = state.error.orEmpty(), onClose = onDismissError)
                }
                AnimatedVisibility(
                    visible = voiceState != VoiceState.Idle,
                ) {
                    VoiceSheet(
                        state = voiceState,
                        partial = voicePartial,
                        onCancel = onVoiceCancel,
                    )
                }
                ChatInputBar(
                    draft = state.draft,
                    attachments = state.attachments,
                    running = state.running,
                    modelName = modelName,
                    voiceAvailable = voiceAvailable,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onStop = onStop,
                    onAttach = onAttach,
                    onVoice = onVoiceStart,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onRemoveAttachment = onRemoveAttachment,
                    modifier = Modifier.imePadding(),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                // 用 lastOrNull 判断而非 isEmpty：加载中的空壳状态不应触发空态提示。
                state.messages.lastOrNull() == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "开始新的对话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "支持 Markdown、代码块与工具调用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            imageLoader = imageLoader,
                            connection = connection,
                            onImageClick = { viewingImage = it },
                            onRetry = onRetry,
                        )
                    }
                }
            }

            if (!atBottom && state.messages.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(state.messages.lastIndex)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "跳到最新",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                    renaming = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("取消") }
            },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除会话") },
            text = { Text("删除后无法恢复，确定要删除这个会话吗") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text("取消") }
            },
        )
    }

    // 图片全屏查看：点任意处关闭。
    viewingImage?.let { url ->
        ImageViewerDialog(
            url = url,
            loader = imageLoader,
            connection = connection,
            onDismiss = { viewingImage = null },
        )
    }

    if (showContext) {
        ContextDetailSheet(
            state = state,
            onDismiss = { showContext = false },
        )
    }
}

/** 页面级错误条，带关闭按钮。 */
@Composable
private fun ErrorBar(text: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                RoundedCornerShape(10.dp),
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭提示",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
