package io.qwenpaw.node.ui.community

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 社区只读页状态：列表、分页、详情与评论。 */
data class CommunityUiState(
    val posts: JsonArray = JsonArray(emptyList()),
    val total: Int = 0,
    val page: Int = 1,
    val keyword: String = "",
    val postType: String = "all",
    val sort: String = "recommended",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val detail: JsonObject? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val comments: JsonArray = JsonArray(emptyList()),
    val commentsLoading: Boolean = false,
    val commentsError: String? = null,
) {
    val hasMore: Boolean get() = posts.size < total
}

/**
 * 社区文章与评论的数据层。
 *
 * 列表接口公开可读，未登录也能取到；登录只影响点赞、发帖等写操作，本版不做。
 * 分页按服务端 `total` 判断是否还有下一页，避免最后补一次空请求。
 */
class CommunityViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CommunityUiState())
    val state: StateFlow<CommunityUiState> = _state.asStateFlow()

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        fetch(page = 1, append = false)
    }

    fun refresh() {
        if (_state.value.loading || _state.value.loadingMore) return
        fetch(page = 1, append = false)
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        fetch(page = current.page + 1, append = true)
    }

    fun setKeyword(value: String) {
        _state.update { it.copy(keyword = value) }
        refresh()
    }

    fun setPostType(value: String) {
        _state.update { it.copy(postType = value) }
        refresh()
    }

    fun setSort(value: String) {
        _state.update { it.copy(sort = value) }
        refresh()
    }

    private fun fetch(page: Int, append: Boolean) {
        val conn = connection() ?: return
        val current = _state.value
        _state.update {
            it.copy(
                loading = !append,
                loadingMore = append,
                error = null,
            )
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.communityPosts(
                        conn = conn,
                        page = page,
                        keyword = current.keyword,
                        postType = current.postType,
                        sort = current.sort,
                    )
                }
            }.onSuccess { json ->
                val items = json["items"]?.jsonArray ?: JsonArray(emptyList())
                val total = json["total"]?.jsonPrimitive?.intOrNull ?: items.size
                _state.update { state ->
                    state.copy(
                        posts = if (append) {
                            JsonArray(state.posts + items)
                        } else {
                            items
                        },
                        total = total,
                        page = page,
                        loading = false,
                        loadingMore = false,
                        loaded = true,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        loaded = true,
                        error = ErrorText.of(e),
                    )
                }
            }
        }
    }

    fun openPost(postId: String) {
        val conn = connection() ?: return
        _state.update {
            it.copy(
                detail = null,
                detailLoading = true,
                detailError = null,
                comments = JsonArray(emptyList()),
                commentsLoading = true,
                commentsError = null,
            )
        }
        scope.launch {
            val detail = runCatching {
                withContext(Dispatchers.IO) { api.communityPost(conn, postId) }
            }
            val comments = runCatching {
                withContext(Dispatchers.IO) { api.communityComments(conn, postId) }
            }
            _state.update {
                it.copy(
                    detail = detail.getOrNull(),
                    detailLoading = false,
                    detailError = detail.exceptionOrNull()?.let(ErrorText::of),
                    comments = comments.getOrNull()?.get("items")?.jsonArray
                        ?: JsonArray(emptyList()),
                    commentsLoading = false,
                    commentsError = comments.exceptionOrNull()?.let(ErrorText::of),
                )
            }
        }
    }

    fun closePost() {
        _state.update {
            it.copy(
                detail = null,
                detailError = null,
                comments = JsonArray(emptyList()),
                commentsError = null,
            )
        }
    }
}
