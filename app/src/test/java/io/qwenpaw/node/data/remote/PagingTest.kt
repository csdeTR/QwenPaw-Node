package io.qwenpaw.node.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 分页骨架。
 *
 * 本文件是 0.1.3/0.1.4 里 `Paging.kt` 的**唯一调用方**：分页骨架的界面调用方是收件箱，
 * 而收件箱的分页行为最终仍由这里的四种情形（首页拼接、翻页续接、空页结束、重复游标）
 * 决定。0.1.4 之后本测试保留为回归，不要随调用方迁移而删除。
 */
class PagingTest {

    @Test
    fun `first page seeds items and keeps more`() = runTest {
        val calls = mutableListOf<Int>()
        val list = PagedList.ofOffset<Int>(limit = 20) { offset ->
            calls += offset
            OffsetPage((0 until 20).toList(), total = 45)
        }
        assertEquals(PageLoad.Appended, list.loadNext())
        assertEquals(20, list.items.size)
        assertTrue(list.hasMore)
        assertEquals(listOf(0), calls)
    }

    @Test
    fun `next page appends and advances offset`() = runTest {
        val calls = mutableListOf<Int>()
        val list = PagedList.ofOffset<Int>(limit = 20) { offset ->
            calls += offset
            when (offset) {
                0 -> OffsetPage((0 until 20).toList(), total = 45)
                20 -> OffsetPage((20 until 40).toList(), total = 45)
                else -> OffsetPage((40 until 45).toList(), total = 45)
            }
        }
        assertEquals(PageLoad.Appended, list.loadNext())
        assertEquals(PageLoad.Appended, list.loadNext())
        assertEquals(40, list.items.size)
        assertEquals(0, list.items.first())
        assertEquals(39, list.items.last())

        assertEquals(PageLoad.End, list.loadNext())
        assertEquals(45, list.items.size)
        assertFalse(list.hasMore)
        assertEquals(listOf(0, 20, 40), calls)
    }

    @Test
    fun `empty cursor page ends`() = runTest {
        var calls = 0
        val list = PagedList.ofCursor<Int> { cursor ->
            calls++
            when (cursor) {
                null -> CursorPage(listOf(1, 2), hasMore = true, nextCursor = "a")
                else -> CursorPage(emptyList(), hasMore = null, nextCursor = null)
            }
        }
        assertEquals(PageLoad.Appended, list.loadNext())
        assertEquals(PageLoad.End, list.loadNext())
        assertEquals(listOf(1, 2), list.items)
        assertFalse(list.hasMore)
        assertEquals(2, calls)
    }

    /** 游标不推进时立刻结束，且不再发第三次请求。 */
    @Test
    fun `duplicate cursor ends without another request`() = runTest {
        var calls = 0
        val list = PagedList.ofCursor<Int> { _ ->
            calls++
            CursorPage(listOf(calls), hasMore = true, nextCursor = "a")
        }
        assertEquals(PageLoad.Appended, list.loadNext())
        assertEquals(PageLoad.End, list.loadNext())
        assertEquals(2, list.items.size)
        assertFalse(list.hasMore)
        assertEquals(PageLoad.End, list.loadNext())
        assertEquals(2, calls)
    }

    /** `has_more=false` 压过非空游标：字段矛盾时以布尔为准。 */
    @Test
    fun `has more false wins over a non empty next cursor`() = runTest {
        val page = cursorPage(CursorPage(listOf(1), hasMore = false, nextCursor = "b"))
        assertFalse(page.hasMore)
        assertNull(page.next)
    }

    @Test
    fun `blank response is treated as end`() = runTest {
        val list = PagedList.ofOffset<Int>(limit = 20) { _ -> OffsetPage(listOf(1), total = 1) }
        assertEquals(PageLoad.End, list.loadNext())

        val blank = PagedList.ofOffset<Int>(limit = 20) { _ ->
            throw ApiException(204, "服务端返回了空响应")
        }
        assertEquals(PageLoad.End, blank.loadNext())
        assertTrue(blank.items.isEmpty())
        assertFalse(blank.hasMore)
        assertFalse(blank.loading)
    }

    /** 没有 total 时，只有取满一页才认为可能还有下一页。 */
    @Test
    fun `short page ends when total is absent`() = runTest {
        val list = PagedList.ofOffset<Int>(limit = 20) { _ ->
            OffsetPage((0 until 7).toList(), total = null)
        }
        assertEquals(PageLoad.End, list.loadNext())
        assertFalse(list.hasMore)
    }

    @Test
    fun `count page ends on short page`() = runTest {
        val list = PagedList.ofLimit<Int>(limit = 50) { loaded ->
            if (loaded == 0) CountPage((0 until 50).toList()) else CountPage((0 until 10).toList())
        }
        assertEquals(PageLoad.Appended, list.loadNext())
        assertEquals(50, list.items.size)
        assertEquals(PageLoad.End, list.loadNext())
        assertEquals(60, list.items.size)
        assertFalse(list.hasMore)
    }

    /** 失败时状态原样保留，并且可以重试成功。 */
    @Test
    fun `failed fetch keeps state and is retryable`() = runTest {
        var fail = true
        val list = PagedList.ofOffset<Int>(limit = 20) { offset ->
            if (fail) throw java.io.IOException("boom")
            // total 等于已取条数，因此这一页就是最后一页。
            OffsetPage(listOf(offset + 1), total = offset + 1)
        }
        try {
            list.loadNext()
            fail("异常应原样上抛")
        } catch (e: java.io.IOException) {
            assertEquals("boom", e.message)
        }
        assertTrue(list.items.isEmpty())
        assertTrue(list.hasMore)
        assertFalse(list.loading)

        fail = false
        assertEquals(PageLoad.End, list.loadNext())
        assertEquals(listOf(1), list.items)
    }

    /** 并发重入当场返回 Busy，不会连发两次请求。 */
    @Test
    fun `concurrent loadNext is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val list = PagedList.ofOffset<Int>(limit = 20) { _ ->
            calls++
            gate.await()
            OffsetPage(listOf(1), total = 1)
        }
        val first = async { list.loadNext() }
        // 让第一个协程进到 fetch 内部并挂起在 gate 上。
        while (calls == 0) kotlinx.coroutines.yield()
        assertEquals(PageLoad.Busy, list.loadNext())
        gate.complete(Unit)
        assertEquals(PageLoad.End, first.await())
        assertEquals(1, calls)
    }

    @Test
    fun `refresh resets to first page`() = runTest {
        val calls = mutableListOf<Int>()
        val list = PagedList.ofOffset<Int>(limit = 20) { offset ->
            calls += offset
            OffsetPage((offset until offset + 20).toList(), total = 100)
        }
        list.loadNext()
        list.loadNext()
        assertEquals(40, list.items.size)

        assertEquals(PageLoad.Appended, list.refresh())
        assertEquals(20, list.items.size)
        assertEquals(0, list.items.first())
        assertEquals(listOf(0, 20, 0), calls)
        assertTrue(list.hasMore)
    }
}