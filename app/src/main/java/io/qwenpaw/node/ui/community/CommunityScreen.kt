package io.qwenpaw.node.ui.community

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.pageContentInsets
import io.qwenpaw.node.ui.settings.Chip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 社区只读页：文章列表、详情与评论。写操作仍留在 Web 控制台。 */
@Composable
fun CommunityScreen(
    state: CommunityUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onKeyword: (String) -> Unit,
    onPostType: (String) -> Unit,
    onSort: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onClosePost: () -> Unit,
) {
    LaunchedEffect(Unit) { onEnsureLoaded() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = pageContentInsets(),
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.community_title),
                onBack = if (state.detail != null || state.detailLoading) onClosePost else onBack,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.detailLoading -> Center { CircularProgressIndicator() }

                state.detail != null -> DetailContent(
                    detail = state.detail,
                    comments = state.comments,
                    commentsLoading = state.commentsLoading,
                    commentsError = state.commentsError,
                )

                state.detailError != null -> Center {
                    Text(
                        text = state.detailError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> ListContent(
                    state = state,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMore,
                    onKeyword = onKeyword,
                    onPostType = onPostType,
                    onSort = onSort,
                    onOpenPost = onOpenPost,
                )
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ListContent(
    state: CommunityUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onKeyword: (String) -> Unit,
    onPostType: (String) -> Unit,
    onSort: (String) -> Unit,
    onOpenPost: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeyword,
            singleLine = true,
            label = { Text(stringResource(R.string.community_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "all" to R.string.community_type_all,
                "question" to R.string.community_type_question,
                "article" to R.string.community_type_article,
                "discussion" to R.string.community_type_discussion,
            ).forEach { (code, label) ->
                Chip(
                    text = stringResource(label),
                    selected = state.postType == code,
                    onClick = { onPostType(code) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "recommended" to R.string.community_sort_recommended,
                "latest" to R.string.community_sort_latest,
                "waiting" to R.string.community_sort_waiting,
            ).forEach { (code, label) ->
                Chip(
                    text = stringResource(label),
                    selected = state.sort == code,
                    onClick = { onSort(code) },
                )
            }
        }
        when {
            state.loading || !state.loaded -> Center { CircularProgressIndicator() }

            state.error != null -> Center {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }

            state.posts.isEmpty() -> Center {
                Text(
                    text = stringResource(R.string.community_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 6.dp,
                    bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.posts, key = { element ->
                    element.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }) { element ->
                    val post = element.jsonObject
                    PostCard(post) {
                        post["id"]?.jsonPrimitive?.contentOrNull?.let(onOpenPost)
                    }
                }
                if (state.hasMore) {
                    item {
                        if (state.loadingMore) {
                            Center { CircularProgressIndicator() }
                        } else {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.community_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: JsonObject, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = post.str("title"),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = post.str("summary")
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.community_stats,
                    post.int("like_count"),
                    post.int("comment_count"),
                    post.int("view_count"),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.community_author, post.str("author_name")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailContent(
    detail: JsonObject,
    comments: JsonArray,
    commentsLoading: Boolean,
    commentsError: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = detail.str("title"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.community_author, detail.str("author_name")),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.community_stats,
                detail.int("like_count"),
                detail.int("comment_count"),
                detail.int("view_count"),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Text(
                text = detail.str("body_text").ifBlank { detail.str("summary") },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.community_comments),
            style = MaterialTheme.typography.titleSmall,
        )
        when {
            commentsLoading -> Center { CircularProgressIndicator() }
            commentsError != null -> Text(
                text = commentsError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            comments.isEmpty() -> Text(
                text = stringResource(R.string.community_no_comments),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> comments.forEach { element ->
                val comment = element.jsonObject
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = comment.str("author_name"),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (comment["accepted"]?.jsonPrimitive?.contentOrNull == "true") {
                                Text(
                                    text = stringResource(R.string.community_accepted),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = comment.str("content"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: 0
