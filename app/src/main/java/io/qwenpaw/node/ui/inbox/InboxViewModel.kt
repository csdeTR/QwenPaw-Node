package io.qwenpaw.node.ui.inbox

import io.qwenpaw.node.data.model.InboxEventDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.OffsetPage
import io.qwenpaw.node.data.remote.PageLoad
import io.qwenpaw.node.data.remote.PagedList
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

/** 收件箱页的状态。 */
data class InboxUiState(
    val events: List<InboxEventDto> = emptyList(),
    val unreadCount: Int = 0,
    val total: Int = 0,
    val loading: Boolean = false,
    /** 加载下一页中。与首屏加载分开：翻页时不该整页转圈。 */
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * 收件箱。
 *
 * 分页走 `PagedList.ofOffset`——这是分页骨架的第一个真实调用方：
 * 服务端的 `/api/console/inbox/events` 只回 `{events, total, unread_count}`，
 * 没有 `has_more`，结束判定靠「已取条数达到 total」。
 *
 * 未读数由服务端在每次查询时一并返回，客户端不在本地推算：
 * 未读的定义（过滤条件、状态）在服务端，本地算容易与角标对不上。
 */
class InboxViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private var paged: PagedList<InboxEventDto>? = null

    /**
     * 每页查询顺带的计数。
     *
     * `PagedList` 只暴露条目与是否还有下一页，未读数和总数要单独留存；
     * 放在每次查询的返回值里而不是另发一次请求，能让角标与列表始终同一时刻。
     */
    private var fetchedUnread: Int = 0
    private var fetchedTotal: Int = 0

    private suspend fun fetchPage(conn: Connection, offset: Int): OffsetPage<InboxEventDto> {
        val page = withContext(Dispatchers.IO) { api.inboxEvents(conn, PAGE_SIZE, offset) }
        fetchedUnread = page.unreadCount
        fetchedTotal = page.total
        return OffsetPage(page.events, page.total)
    }

    /** 首次进入或手动刷新：清空重来。 */
    fun refresh() {
        val conn = connection() ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val list = PagedList.ofOffset<InboxEventDto>(limit = PAGE_SIZE) { offset ->
                fetchPage(conn, offset)
            }
            paged = list
            runCatching { list.refresh() }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            events = list.items,
                            hasMore = result == PageLoad.Appended,
                            unreadCount = fetchedUnread,
                            total = fetchedTotal,
                            loading = false,
                            loaded = true,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    /** 触底加载下一页。没有更多或正在加载时直接返回。 */
    fun loadMore() {
        val conn = connection() ?: return
        val list = paged ?: return
        if (!_state.value.hasMore || _state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            runCatching { list.loadNext() }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            events = list.items,
                            hasMore = result == PageLoad.Appended,
                            unreadCount = fetchedUnread,
                            total = fetchedTotal,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loadingMore = false, error = ErrorText.of(e)) }
                }
        }
    }

    /** 全部标记已读。未读角标随之归零。 */
    fun markAllRead() {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.markInboxRead(conn, all = true) } }
                .onSuccess {
                    _state.update { s ->
                        s.copy(events = s.events.map { it.copy(read = true) }, unreadCount = 0)
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    /** 标记单条已读。点开事件时调用，已经读过的直接返回。 */
    fun markRead(eventId: String) {
        val conn = connection() ?: return
        if (_state.value.events.firstOrNull { it.id == eventId }?.read != false) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.markInboxRead(conn, eventIds = listOf(eventId)) }
            }.onSuccess {
                _state.update { s ->
                    s.copy(
                        events = s.events.map { if (it.id == eventId) it.copy(read = true) else it },
                        unreadCount = (s.unreadCount - 1).coerceAtLeast(0),
                    )
                }
            }
            // 单条已读失败不弹错误：点开事件本身已经完成，角标滞后留在下次刷新。
        }
    }

    /** 删除单条。服务端没有回收站，调用方必须先做二次确认。 */
    fun delete(eventId: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteInboxEvent(conn, eventId) } }
                .onSuccess {
                    _state.update { s ->
                        val removed = s.events.firstOrNull { it.id == eventId }
                        val wasUnread = removed?.read == false
                        s.copy(
                            events = s.events.filterNot { it.id == eventId },
                            total = (s.total - 1).coerceAtLeast(0),
                            unreadCount = (s.unreadCount - if (wasUnread) 1 else 0).coerceAtLeast(0),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** 换实例或换智能体时清空：旧事件归属上一个智能体，留着会误导。 */
    fun clear() {
        paged = null
        fetchedUnread = 0
        fetchedTotal = 0
        _state.value = InboxUiState()
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}