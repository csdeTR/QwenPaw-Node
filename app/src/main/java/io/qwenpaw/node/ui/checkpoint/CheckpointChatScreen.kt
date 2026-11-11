package io.qwenpaw.node.ui.checkpoint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.ui.chat.AssistantCollapse
import io.qwenpaw.node.ui.chat.ChatImageLoader
import io.qwenpaw.node.ui.chat.ChatMessageList
import io.qwenpaw.node.ui.chat.ChatTopBar
import io.qwenpaw.node.ui.chat.ChatUiState
import io.qwenpaw.node.ui.chat.MessageAppearance
import io.qwenpaw.node.ui.chat.buildItems
import io.qwenpaw.node.ui.chat.buildTimeline

/**
 * 检查点的只读对话页。
 *
 * 排布与聊天页一致——同一个顶栏、同一套气泡与步骤卡片，只是没有输入条。
 * 底部固定一条「创建检查点」，是聊天页输入框所在的位置。
 *
 * 不能发消息是硬约束：本页不实例化 `ChatInputBar`，也就不存在发送、附件与语音入口。
 */
@Composable
fun CheckpointChatScreen(
    state: ChatUiState,
    /** 会话在检查点侧的标题。找不到对应对话时作为顶栏文案的兜底。 */
    sessionTitle: String,
    /** 会话在当前实例的对话列表里没有对应项。 */
    chatMissing: Boolean,
    creating: Boolean,
    error: String?,
    imageLoader: ChatImageLoader? = null,
    connection: Connection? = null,
    appearance: MessageAppearance = MessageAppearance(),
    /** 智能体输出的折叠方式，与聊天页保持一致。 */
    assistantCollapse: AssistantCollapse = AssistantCollapse.RESULT_COLLAPSED,
    renderUserMarkdown: Boolean = true,
    renderAssistantMarkdown: Boolean = true,
    onBack: () -> Unit,
    onSnapshot: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val items = remember(state.messages, assistantCollapse) {
        buildItems(state.messages, assistantCollapse)
    }
    val timeline = remember(items) { buildTimeline(items, 0) }

    // 进入时贴底：检查点关心的是「最近的对话状态」，不是从头读。
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) {
            listState.scrollToItem(timeline.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChatTopBar(
                title = state.currentChatName.ifBlank { sessionTitle },
                running = state.running,
                contextUsage = state.contextUsage,
                link = state.link,
                boundUrl = state.boundUrl,
                boundLabel = state.boundLabel,
                serverName = state.boundServerName,
                onBack = onBack,
                onRename = {},
                onDelete = {},
                onRefresh = onRefresh,
                readOnly = true,
            )
        },
        bottomBar = {
            SnapshotActionBar(creating = creating, error = error, onSnapshot = onSnapshot)
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                chatMissing -> CenteredHint("这条会话在当前实例的对话列表里不存在，无法显示历史记录。")
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                timeline.isEmpty() -> CenteredHint("这条会话没有可显示的消息。")
                else -> ChatMessageList(
                    state = state,
                    timeline = timeline,
                    listState = listState,
                    imageLoader = imageLoader,
                    connection = connection,
                    appearance = appearance,
                    renderUserMarkdown = renderUserMarkdown,
                    renderAssistantMarkdown = renderAssistantMarkdown,
                    // 只读：不传重试与原文入口，气泡上对应的按钮随之消失。
                )
            }
        }
    }
}

/** 底部的快照操作区，占据聊天页输入条的位置。 */
@Composable
private fun SnapshotActionBar(
    creating: Boolean,
    error: String?,
    onSnapshot: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = "只读查看，不会发送消息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSnapshot,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (creating) "创建中…" else "创建检查点")
            }
            error?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
