package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatSpecDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 会话列表分组。
 *
 * 分桶全是纯函数，所以边界（跨日、缺时间、无分组 id）都能在 JVM 里验证，
 * 不必开模拟器去点。日期比较统一按本地时区，这也是旧 `timeLabel` 的偏差所在。
 */
class ChatGroupingTest {

    private fun chat(
        id: String,
        updatedAt: String? = null,
        pinned: Boolean = false,
        groupId: String? = null,
    ) = ChatSpecDto(
        id = id,
        name = id,
        updatedAt = updatedAt,
        pinned = pinned,
        groupId = groupId,
    )

    // ---------------- 时间分组 ----------------

    @Test
    fun `splits into today recent and earlier`() {
        val now = LocalDate.of(2026, 10, 29)
        val chats = listOf(
            chat("a", "2026-10-29T02:00:00Z"),
            chat("b", "2026-10-26T02:00:00Z"),
            chat("c", "2026-09-01T02:00:00Z"),
        )
        val sections = ChatGrouping.byTime(chats, now)
        assertEquals(listOf("today", "recent", "earlier"), sections.map { it.key })
        assertEquals(listOf("a"), sections[0].chats.map { it.id })
        assertEquals(listOf("b"), sections[1].chats.map { it.id })
        assertEquals(listOf("c"), sections[2].chats.map { it.id })
    }

    /** 空桶不输出，否则会出现只有表头的分区。 */
    @Test
    fun `empty buckets are skipped`() {
        val now = LocalDate.of(2026, 10, 29)
        val sections = ChatGrouping.byTime(listOf(chat("a", "2026-10-29T02:00:00Z")), now)
        assertEquals(1, sections.size)
        assertEquals("today", sections[0].key)
    }

    /** 置顶项单独成区并排最前，不参与时间分桶。 */
    @Test
    fun `pinned chats come first regardless of date`() {
        val now = LocalDate.of(2026, 10, 29)
        val chats = listOf(
            chat("old", "2026-01-01T02:00:00Z", pinned = true),
            chat("new", "2026-10-29T02:00:00Z"),
        )
        val sections = ChatGrouping.byTime(chats, now)
        assertEquals(listOf("pinned", "today"), sections.map { it.key })
        assertEquals(listOf("old"), sections[0].chats.map { it.id })
    }

    /** 时间缺失的会话不能凭空消失，归入「更早」。 */
    @Test
    fun `chat without timestamp falls into earlier`() {
        val sections = ChatGrouping.byTime(listOf(chat("x")), LocalDate.of(2026, 10, 29))
        assertEquals("earlier", sections.single().key)
    }

    /** 恰好 7 天前应当算「更早」：近 7 天含昨天到第 6 天。 */
    @Test
    fun `seven days ago is earlier`() {
        val now = LocalDate.of(2026, 10, 29)
        val sections = ChatGrouping.byTime(listOf(chat("a", "2026-10-22T02:00:00Z")), now)
        assertEquals("earlier", sections.single().key)
    }

    // ---------------- 来源分组 ----------------

    @Test
    fun `groups by source ordered by server order`() {
        val groups = listOf(
            ChatGroupDto(id = "cron", name = "定时任务", order = 3),
            ChatGroupDto(id = "default", name = "未分类", order = 1),
        )
        val chats = listOf(
            chat("a", groupId = "cron"),
            chat("b", groupId = "default"),
        )
        val sections = ChatGrouping.bySource(chats, groups)
        assertEquals(listOf("g:default", "g:cron"), sections.map { it.key })
        assertEquals(listOf("未分类", "定时任务"), sections.map { it.title })
    }

    /** 找不到分组的会话归入「未分组」并排在最后。 */
    @Test
    fun `unknown group id goes to ungrouped at the end`() {
        val groups = listOf(ChatGroupDto(id = "default", name = "未分类", order = 1))
        val chats = listOf(
            chat("a", groupId = "vanished"),
            chat("b", groupId = "default"),
            chat("c"),
        )
        val sections = ChatGrouping.bySource(chats, groups)
        assertEquals(listOf("g:default", "ungrouped"), sections.map { it.key })
        assertEquals(listOf("b"), sections[0].chats.map { it.id })
        assertEquals(setOf("a", "c"), sections[1].chats.map { it.id }.toSet())
    }

    /** 分组名为空时用 id 兜底，避免出现空白表头。 */
    @Test
    fun `blank group name falls back to id`() {
        val sections = ChatGrouping.bySource(
            listOf(chat("a", groupId = "custom-1")),
            listOf(ChatGroupDto(id = "custom-1", name = "", order = 2)),
        )
        assertEquals("custom-1", sections.single().title)
    }

    @Test
    fun `pinned group is ordered before others`() {
        val groups = listOf(
            ChatGroupDto(id = "a", name = "A", order = 1),
            ChatGroupDto(id = "b", name = "B", order = 9, pinned = true),
        )
        val sections = ChatGrouping.bySource(
            listOf(chat("x", groupId = "a"), chat("y", groupId = "b")),
            groups,
        )
        assertEquals(listOf("B", "A"), sections.map { it.title })
    }

    // ---------------- 模式派发 ----------------

    @Test
    fun `none mode returns a single untitled section`() {
        val sections = ChatGrouping.sections(
            listOf(chat("a"), chat("b")),
            GroupMode.NONE,
        )
        assertEquals(1, sections.size)
        assertTrue(sections.single().title.isEmpty())
        assertEquals(listOf("a", "b"), sections.single().chats.map { it.id })
    }

    @Test
    fun `none mode with no chats returns nothing`() {
        assertTrue(ChatGrouping.sections(emptyList(), GroupMode.NONE).isEmpty())
    }

    @Test
    fun `click cycles time source none`() {
        assertEquals(GroupMode.SOURCE, GroupMode.TIME.next())
        assertEquals(GroupMode.NONE, GroupMode.SOURCE.next())
        assertEquals(GroupMode.TIME, GroupMode.NONE.next())
    }

    // ---------------- 折叠 ----------------

    /** 折叠只清空条目、保留表头：整体删掉会让分组凭空消失，看不出折叠了什么。 */
    @Test
    fun `collapse empties chats but keeps headers`() {
        val sections = listOf(
            ChatSection("today", "今天", listOf(chat("a"), chat("b"))),
            ChatSection("earlier", "更早", listOf(chat("c"))),
        )
        val out = ChatGrouping.collapse(sections, setOf("today"))
        assertEquals(listOf("today", "earlier"), out.map { it.key })
        assertTrue(out[0].chats.isEmpty())
        assertEquals("今天", out[0].title)
        assertEquals(listOf("c"), out[1].chats.map { it.id })
    }

    @Test
    fun `collapse with empty set returns the same list`() {
        val sections = listOf(ChatSection("today", "今天", listOf(chat("a"))))
        assertEquals(sections, ChatGrouping.collapse(sections, emptySet()))
    }

    /** 集合里存在但本次列表里没有的 key 要被自然忽略，不报错也不插空分区。 */
    @Test
    fun `collapse ignores keys that are absent`() {
        val sections = listOf(ChatSection("today", "今天", listOf(chat("a"))))
        val out = ChatGrouping.collapse(sections, setOf("g:gone", "pinned"))
        assertEquals(1, out.size)
        assertEquals(listOf("a"), out.single().chats.map { it.id })
    }

    /** 折叠多个分区时互不影响。 */
    @Test
    fun `collapse handles multiple keys`() {
        val sections = listOf(
            ChatSection("a", "A", listOf(chat("1"))),
            ChatSection("b", "B", listOf(chat("2"))),
            ChatSection("c", "C", listOf(chat("3"))),
        )
        val out = ChatGrouping.collapse(sections, setOf("a", "c"))
        assertTrue(out[0].chats.isEmpty())
        assertEquals(listOf("2"), out[1].chats.map { it.id })
        assertTrue(out[2].chats.isEmpty())
    }

    /** 空的内置分组不输出表头；自建分组即使为空也要出现，否则刚建完看起来像失败。 */
    @Test
    fun `empty custom group is shown but empty builtin is hidden`() {
        val groups = listOf(
            ChatGroupDto(id = "default", name = "未分类", order = 1, kind = "default"),
            ChatGroupDto(id = "fresh", name = "测试", order = 2, kind = CUSTOM_KIND),
        )
        val sections = ChatGrouping.bySource(
            listOf(chat("a", groupId = "default")),
            groups,
        )
        assertEquals(listOf("g:default", "g:fresh"), sections.map { it.key })
        assertEquals(listOf("a"), sections[0].chats.map { it.id })
        assertTrue(sections[1].chats.isEmpty())
        assertEquals("测试", sections[1].title)
    }

    /** 折叠与来源分组配合：key 形如 `g:<id>`，与未分组 / 置顶区互不冲突。 */
    @Test
    fun `collapse works on source sections`() {
        val groups = listOf(
            ChatGroupDto(id = "default", name = "未分类", order = 1),
            ChatGroupDto(id = "custom", name = "自建", order = 2),
        )
        val sections = ChatGrouping.bySource(
            listOf(chat("a", groupId = "default"), chat("b", groupId = "custom")),
            groups,
        )
        val out = ChatGrouping.collapse(sections, setOf("g:default"))
        assertEquals(listOf("g:default", "g:custom"), out.map { it.key })
        assertTrue(out[0].chats.isEmpty())
        assertEquals(listOf("b"), out[1].chats.map { it.id })
    }
}