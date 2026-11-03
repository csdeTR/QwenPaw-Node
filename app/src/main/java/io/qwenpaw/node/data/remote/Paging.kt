package io.qwenpaw.node.data.remote

import kotlinx.coroutines.sync.Mutex

/**
 * 分页骨架。
 *
 * 服务端共有三种分页形态：`limit`/`offset`（收件箱）、`cursor`（工作区目录）、
 * 只有 `limit` 没有游标（备份、日志）。这里把三种形态归一成同一套「取下一页 /
 * 是否还有更多 / 追加到已有列表」，UI 只看 [Page.items] 与 [Page.hasMore]，
 * 不需要知道游标长什么样。
 *
 * **上一版 `ui/common/Paging.kt` 已于第 38 节审计时删除**：当时没有调用方也没有单测，
 * 留下来只会让人误判功能已接入。本文件是数据层重开，与第一个真实调用方（收件箱）
 * 同轮落地，并有 [PagingTest] 覆盖。
 *
 * 首个调用方的字段约定（均已对照服务端源码）：
 * - `ofOffset`：`GET /api/console/inbox/events` → `{events, total, unread_count}`，
 *   **响应里没有 `has_more`**，结束只能靠 `total` 或空页判断
 *   （`qwenpaw/app/routers/console.py` 的 `get_inbox_events`）。
 * - `ofCursor`：`GET /api/workspace/tree` → `{entries, has_more, next_cursor}`
 *   （`qwenpaw/services/workspace_files.py` 的 `list_directory`）。
 *
 * 线程约定：[PagedList] 不是线程安全对象，也不是 Compose 可观察对象。
 * 只允许在同一个协程里调 [PagedList.loadNext]，读完后由调用方自行写进 `StateFlow`；
 * 若直接读它的字段，界面不会重组。
 */

/** 归一化后的一页。`next` 为 null 表示已到尽头，UI 不应读取它。 */
data class Page<T>(
    val items: List<T>,
    val hasMore: Boolean,
    val next: PageCursor? = null,
)

/** 下一页的定位信息。三种形态共用，一页最多只用其中一种。 */
sealed interface PageCursor {
    /** `limit`/`offset` 形态：下一页从该 offset 起。 */
    data class Offset(val value: Int) : PageCursor

    /** `cursor` 形态：服务端给出的不透明游标，原样回传。 */
    data class Cursor(val value: String) : PageCursor

    /** 只有 `limit` 的形态：已取条数，下一页靠它推进。 */
    data class Count(val value: Int) : PageCursor
}

/** `limit`/`offset` 端点的原始响应。没有 `has_more`，只有总数。 */
data class OffsetPage<T>(
    val items: List<T>,
    val total: Int? = null,
)

/** `cursor` 端点的原始响应。`has_more` 为布尔时以它为准。 */
data class CursorPage<T>(
    val items: List<T>,
    val hasMore: Boolean? = null,
    val nextCursor: String? = null,
)

/** 只有 `limit` 的端点的原始响应。 */
data class CountPage<T>(
    val items: List<T>,
)

/** 一次 [PagedList.loadNext] 的结果。 */
enum class PageLoad {
    /** 已追加新一页。 */
    Appended,

    /** 上一页已是最后一页，本次没有发起请求。 */
    End,

    /** 正在加载中，本次调用被忽略。 */
    Busy,
}

/**
 * 一个可逐页追加的列表。
 *
 * 由 [ofOffset] / [ofCursor] / [ofLimit] 构造，不要直接实例化。
 */
class PagedList<T> private constructor(
    /** 取一页：入参是下一页的定位信息（首页为 null）。 */
    private val fetchPage: suspend (PageCursor?) -> Page<T>,
) {
    /** 已追加的条目，顺序与页序一致。 */
    var items: List<T> = emptyList()
        private set

    /** 是否还有下一页。 */
    var hasMore: Boolean = true
        private set

    /** 是否正在加载。 */
    var loading: Boolean = false
        private set

    private var next: PageCursor? = null
    private var started = false

    // 双护栏：loading 供外部读，Mutex 挡住同一时刻的重入，避免两页同时追加。
    private val gate = Mutex()

    /**
     * 取下一页并追加。
     *
     * 失败时不改动 [items] / [hasMore] / [next]，异常原样抛出，调用方可直接重试。
     * 唯一的例外是空响应：`QwenPawApi.decode` 对空 body 抛 `ApiException(204)`，
     * 这里归为「空页 = 结束」，因为干净实例上「0 条事件」不该弹错误。
     */
    suspend fun loadNext(): PageLoad {
        // tryLock 而不是 withLock：并发重入要当场返回 Busy，不能排队等前一次结束，
        // 否则同一个列表对象被两处同时驱动时会连发两次请求。
        if (!gate.tryLock()) return PageLoad.Busy
        try {
            if (started && !hasMore) return PageLoad.End
            loading = true
            val page = fetchSafe(next)
            started = true
            items = items + page.items
            hasMore = page.hasMore
            next = page.next
            return if (page.hasMore) PageLoad.Appended else PageLoad.End
        } finally {
            loading = false
            gate.unlock()
        }
    }

    /** 清空并从第一页重来。失败时保留旧内容，避免刷新失败把列表清空。 */
    suspend fun refresh(): PageLoad {
        if (!gate.tryLock()) return PageLoad.Busy
        try {
            loading = true
            val page = fetchSafe(null)
            started = true
            items = page.items
            hasMore = page.hasMore
            next = page.next
            return if (page.hasMore) PageLoad.Appended else PageLoad.End
        } finally {
            loading = false
            gate.unlock()
        }
    }

    private suspend fun fetchSafe(cursor: PageCursor?): Page<T> = try {
        fetchPage(cursor)
    } catch (e: ApiException) {
        // 204 在 decode 里与「200 空 body」同义：没有内容。按结束处理，不当作错误。
        if (e.code == 204) Page(emptyList(), hasMore = false, next = null) else throw e
    }

    companion object {
        /** `limit`/`offset` 形态。fetch 收到下一页的 offset（首页为 0）。 */
        fun <T> ofOffset(
            limit: Int,
            fetch: suspend (offset: Int) -> OffsetPage<T>,
        ): PagedList<T> = PagedList { cursor ->
            val offset = (cursor as? PageCursor.Offset)?.value ?: 0
            settle(offsetPage(fetch(offset), offset, limit), cursor)
        }

        /** `cursor` 形态。fetch 收到下一页游标（首页为 null）。 */
        fun <T> ofCursor(
            fetch: suspend (cursor: String?) -> CursorPage<T>,
        ): PagedList<T> = PagedList { cursor ->
            val raw = (cursor as? PageCursor.Cursor)?.value
            settle(cursorPage(fetch(raw)), cursor)
        }

        /** 只有 `limit` 的形态。fetch 收到已取条数与固定 limit。 */
        fun <T> ofLimit(
            limit: Int,
            fetch: suspend (loaded: Int) -> CountPage<T>,
        ): PagedList<T> = PagedList { cursor ->
            val loaded = (cursor as? PageCursor.Count)?.value ?: 0
            settle(countPage(fetch(loaded), loaded, limit), cursor)
        }
    }
}

/**
 * `limit`/`offset` 的一页：没有 `has_more` 字段，只能靠 `total` 或「取满」推断。
 *
 * `total` 存在时以它为准确值；缺失时退化为「取满 limit 才可能还有下一页」。
 */
internal fun <T> offsetPage(raw: OffsetPage<T>, offset: Int, limit: Int): Page<T> {
    val nextOffset = offset + raw.items.size
    val hasMore = if (raw.total != null) {
        nextOffset < raw.total
    } else {
        raw.items.size >= limit
    }
    return Page(raw.items, hasMore, if (hasMore) PageCursor.Offset(nextOffset) else null)
}

/** `cursor` 的一页：`has_more` 为布尔时以它为准，缺省才退化为「有游标就还有」。 */
internal fun <T> cursorPage(raw: CursorPage<T>): Page<T> {
    val hasMore = raw.hasMore ?: (raw.nextCursor != null)
    val cursor = raw.nextCursor?.takeIf { hasMore }?.let { PageCursor.Cursor(it) }
    // has_more 明确为 false 时即便带着游标也一律结束：两个字段互相矛盾时以布尔为准。
    return Page(raw.items, hasMore && cursor != null, cursor)
}

/** 只有 `limit` 的一页：短页即结束。 */
internal fun <T> countPage(raw: CountPage<T>, loaded: Int, limit: Int): Page<T> {
    val hasMore = raw.items.size >= limit
    val taken = loaded + raw.items.size
    return Page(raw.items, hasMore, if (hasMore) PageCursor.Count(taken) else null)
}

/**
 * 三条公共护栏，最后执行。
 *
 * 1. 空页且无游标视为结束：服务端已经给不出内容了。
 * 2. 游标与上一页相同视为结束：服务端没推进游标，继续请求只会死循环。
 *    本页已取到的条目**保留**——游标不推进是服务端缺陷，不是丢弃数据的理由。
 */
internal fun <T> settle(page: Page<T>, prev: PageCursor?): Page<T> {
    if (page.items.isEmpty() && page.next == null) return page.copy(hasMore = false)
    if (page.next != null && page.next == prev) return page.copy(hasMore = false, next = null)
    return page
}