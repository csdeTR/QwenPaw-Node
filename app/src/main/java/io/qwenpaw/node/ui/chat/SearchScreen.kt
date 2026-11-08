package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.SearchHitDto
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 会话全文检索页。
 *
 * 关键词发送给配套插件，插件直接读各智能体的 `history.db`。中文查询在插件侧走
 * `LIKE`，英文走 FTS5——`unicode61` 分词器把连续汉字当成一个 token，FTS 对中文
 * 不可用。
 *
 * @param chatNames 对话 `id` 与 `session_id` 两种键 → 显示名。插件给的是
 *   `history.db` 的 `session_id`，与对话 `id` 不是同一个值，两个键都要能查到。
 * @param onOpenHit 点某条命中：打开它所属的对话，并滚动到这条消息。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    chatNames: Map<String, String>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenHit: (SearchHitDto) -> Unit,
) {
    // 输入框的本地状态。检索是显式提交（回车或点键盘搜索键），不做边打边查：
    // 每敲一个字就打一次服务端，长上下文实例上代价不可接受。
    var input by rememberSaveable { mutableStateOf(state.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSearch(input)
    }

    // 命中没有时间基准可依赖，列表靠服务端给的 created_at 排序，这里不再排序。

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 12.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = QwenIcons.arrowLeft,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        text = "全文检索",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索所有对话的内容") },
                    leadingIcon = {
                        Icon(
                            imageVector = QwenIcons.search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && state.items.isEmpty() -> LoadingBlock()
                state.error != null && state.items.isEmpty() -> HintBlock(state.error)
                state.items.isEmpty() -> HintBlock(
                    if (state.query.isEmpty()) "输入关键词后按回车开始检索。" else "没有找到包含该关键词的消息。",
                )
                else -> Results(
                    state = state,
                    chatNames = chatNames,
                    onLoadMore = onLoadMore,
                    onOpenHit = onOpenHit,
                )
            }
        }
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    chatNames: Map<String, String>,
    onLoadMore: () -> Unit,
    onOpenHit: (SearchHitDto) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "共 ${state.total} 条命中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        items(state.items, key = { it.sessionId + "|" + it.msgId }) { hit ->
            HitCard(hit = hit, chatName = chatNames[hit.sessionId], onClick = { onOpenHit(hit) })
        }
        item {
            if (state.nextCursor != null) {
                TextButton(
                    onClick = onLoadMore,
                    enabled = !state.loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("加载更多")
                }
            } else if (state.items.isNotEmpty()) {
                Text(
                    text = "已经到底了",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HitCard(hit: SearchHitDto, chatName: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chatName?.takeIf { it.isNotBlank() } ?: "会话 ${hit.sessionId.take(8)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = if (hit.role == "user") "我" else "助手",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            hit.createdAt?.take(10)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        val accent = MaterialTheme.colorScheme.primary
        Text(
            text = remember(hit.snippet, accent) { highlightSnippet(hit.snippet, accent) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * 把插件返回的 `[命中词]` 标成高亮。
 *
 * 插件用方括号而不是富文本，是为了让契约保持纯字符串；成对的方括号才转高亮，
 * 落单的按普通文字处理，避免正文里本来就有 `[` 时被误染。
 */
private fun highlightSnippet(snippet: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < snippet.length) {
        val open = snippet.indexOf('[', index)
        if (open < 0) {
            append(snippet.substring(index))
            break
        }
        val close = snippet.indexOf(']', open + 1)
        if (close < 0) {
            append(snippet.substring(index))
            break
        }
        append(snippet.substring(index, open))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
            append(snippet.substring(open + 1, close))
        }
        index = close + 1
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HintBlock(text: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
