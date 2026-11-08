package io.qwenpaw.node.ui.inbox

import io.qwenpaw.node.data.model.AgentDto
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
    /** 来源筛选，取值见 [INBOX_SOURCE_FILTERS]；null 表示全部。 */
    val sourceFilter: String? = null,
    /** 智能体筛选，null 表示全部。 */
    val agentFilter: String? = null,
    /** 智能体筛选项。加载失败时为空，下拉只剩「全部智能体」。 */
    val agentOptions: List<AgentDto> = emptyList(),
    val agentsLoaded: Boolean = false,
) {
    /** 是否处于任一筛选下。筛选生效时未读数只覆盖筛选范围。 */
    val filtered: Boolean get() = sourceFilter != null || agentFilter != null
}

/**
 * 收件箱。
 *
 * 分页走 `PagedList.ofOffset`——这是分页骨架的第一个真实调用方：
 * 服务端的 `/api/console/inbox/events` 只回 `{events, total, unread_count}`，
 * 没有 `has_more`，结束判定靠「已取条数达到 total」。
 *
 * 未读数由服务端在每次查询时一并返回，客户端不在本地推算：
 * 未读的定义（过滤条件、状态）在服务端，本地算容易与角标对不上。
 * 代价是**筛选生效时未读数只统计筛选范围**，与全局未读不同。
 *
 * 来源与智能体筛选都由服务端完成，切换筛选时重建 [PagedList] 而不是在本地过滤：
 * 本地过滤只能筛出已加载的 30 条，`total` 与未读数都会失真。
 */
class InboxViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    /** 智能体筛选项的来源。失败时返回空列表，筛选项退化为「全部智能体」。 */
    private val loadAgents: suspend () -> List<AgentDto> = { emptyList() },
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

    /**
     * 加载代次。
     *
     * 切换筛选会重建列表；旧请求晚于新请求返回时，代次对不上就丢弃结果，
     * 否则列表会被上一组筛选的数据覆盖。
     */
    private var loadGeneration: Int = 0

    private suspend fun fetchPage(
        conn: Connection,
        offset: Int,
        sourceType: String?,
        agentId: String?,
    ): OffsetPage<InboxEventDto> {
        val page = withContext(Dispatchers.IO) {
            api.inboxEvents(conn, PAGE_SIZE, offset, agentId = agentId, sourceType = sourceType)
        }
        fetchedUnread = page.unreadCount
        fetchedTotal = page.total
        return OffsetPage(page.events, page.total)
    }

    /**
     * 插件事件流推来的未读数。
     *
     * 只改角标，不动已加载的列表：事件流不携带条目内容，重新拉列表是进入页面时
     * 的事。数字不变时直接返回，避免每次心跳都触发一次重组。
     *
     * 事件流推的是**全局**未读，与筛选范围内的数字不同源；筛选生效时直接忽略，
     * 否则角标会在筛选过程中跳回全局值。
     */
    fun applyUnreadCount(count: Int) {
        if (_state.value.filtered) return
        if (_state.value.unreadCount == count) return
        _state.update { it.copy(unreadCount = count) }
    }

    /** 首次进入或手动刷新：清空重来。 */
    fun refresh() = reload()

    /** 切换来源筛选。值未变时直接返回，避免无谓重载。 */
    fun setSourceFilter(sourceType: String?) {
        if (_state.value.sourceFilter == sourceType) return
        _state.update { it.copy(sourceFilter = sourceType) }
        reload()
    }

    /** 切换智能体筛选。值未变时直接返回。 */
    fun setAgentFilter(agentId: String?) {
        if (_state.value.agentFilter == agentId) return
        _state.update { it.copy(agentFilter = agentId) }
        reload()
    }

    /**
     * 拉取智能体筛选项。
     *
     * 只在首次进入收件箱时拉一次；失败静默，`agentsLoaded` 置 true 后不再重试，
     * 下拉仍可用「全部智能体」。筛选不是主流程，不该为它弹错误。
     */
    fun loadAgentOptions() {
        if (_state.value.agentsLoaded) return
        _state.update { it.copy(agentsLoaded = true) }
        scope.launch {
            val agents = runCatching { withContext(Dispatchers.IO) { loadAgents() } }
                .getOrDefault(emptyList())
            _state.update { it.copy(agentOptions = agents) }
        }
    }

    private fun reload() {
        val conn = connection() ?: return
        val generation = ++loadGeneration
        val sourceType = _state.value.sourceFilter
        val agentId = _state.value.agentFilter
        val list = PagedList.ofOffset<InboxEventDto>(limit = PAGE_SIZE) { offset ->
            fetchPage(conn, offset, sourceType, agentId)
        }
        paged = list
        _state.update {
            it.copy(
                events = emptyList(),
                hasMore = true,
                total = 0,
                unreadCount = 0,
                loading = true,
                loaded = false,
                error = null,
            )
        }
        scope.launch {
            runCatching { list.refresh() }
                .onSuccess { result ->
                    if (generation != loadGeneration) return@onSuccess
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
                    if (generation != loadGeneration) return@onFailure
                    _state.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    /** 触底加载下一页。没有更多或正在加载时直接返回。 */
    fun loadMore() {
        val list = paged ?: return
        if (!_state.value.hasMore || _state.value.loadingMore) return
        val generation = loadGeneration
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            runCatching { list.loadNext() }
                .onSuccess { result ->
                    if (generation != loadGeneration) {
                        _state.update { it.copy(loadingMore = false) }
                        return@onSuccess
                    }
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
                    if (generation != loadGeneration) {
                        _state.update { it.copy(loadingMore = false) }
                        return@onFailure
                    }
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

    /** 换实例或换智能体时清空：旧事件归属上一个智能体，留着会误导。筛选一并清空。 */
    fun clear() {
        loadGeneration++
        paged = null
        fetchedUnread = 0
        fetchedTotal = 0
        _state.value = InboxUiState()
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
