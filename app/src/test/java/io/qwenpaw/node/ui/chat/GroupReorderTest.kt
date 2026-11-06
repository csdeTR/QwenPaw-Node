package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatGroupDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分组上移 / 下移的判定。
 *
 * 三条规则各对应一个用户可见的结论：
 * 1. 只在同权重块内交换——跨权重移动在界面上根本看不出来，点了像没反应；
 * 2. 首末位原样返回——界面据此把按钮置灰；
 * 3. 返回值必须包含**全部分组**——`PUT groups/order` 按传入顺序回写，漏掉的就成了
 *    「未提及」，相对位置会变得不可预测。
 */
class GroupReorderTest {

    private fun group(id: String, order: Int = 0, kind: String? = CUSTOM_KIND, pinned: Boolean = false) =
        ChatGroupDto(id = id, name = id, order = order, kind = kind, pinned = pinned)

    /** 自建分组之间互相上移：a 与 b 交换。 */
    @Test
    fun `custom groups swap within their block`() {
        val groups = listOf(
            group("a", order = 0),
            group("b", order = 1),
            group("c", order = 2),
        )
        assertEquals(listOf("b", "a", "c"), ChatGrouping.moveGroup(groups, "b", -1))
        assertEquals(listOf("a", "c", "b"), ChatGrouping.moveGroup(groups, "b", 1))
    }

    /** 自建分组的下界是「默认分组」：再往下就跨到别的权重块，必须原样返回。 */
    @Test
    fun `crossing into another weight is refused`() {
        val groups = listOf(
            group("a", order = 0),
            group("default", order = 1, kind = "default"),
        )
        assertEquals(listOf("a", "default"), ChatGrouping.moveGroup(groups, "a", 1))
        assertEquals(listOf("a", "default"), ChatGrouping.moveGroup(groups, "default", -1))
    }

    /** 块内首位上移、末位下移都原样返回。 */
    @Test
    fun `edges return unchanged order`() {
        val groups = listOf(group("a", 0), group("b", 1))
        assertEquals(listOf("a", "b"), ChatGrouping.moveGroup(groups, "a", -1))
        assertEquals(listOf("a", "b"), ChatGrouping.moveGroup(groups, "b", 1))
    }

    /** 单元素列表任何方向都原地不动。 */
    @Test
    fun `single group never moves`() {
        val groups = listOf(group("only"))
        assertEquals(listOf("only"), ChatGrouping.moveGroup(groups, "only", -1))
        assertEquals(listOf("only"), ChatGrouping.moveGroup(groups, "only", 1))
    }

    /** 未知 id 返回完整顺序，不抛异常也不丢项。 */
    @Test
    fun `unknown group keeps the whole list`() {
        val groups = listOf(group("a"), group("b"))
        assertEquals(listOf("a", "b"), ChatGrouping.moveGroup(groups, "nope", -1))
    }

    /** 返回的数组必须覆盖所有分组，包括内置分组。 */
    @Test
    fun `result covers every group`() {
        val groups = listOf(
            group("x", 0),
            group("y", 1),
            group("default", 2, kind = "default"),
            group("cron", 3, kind = "cron"),
        )
        val order = ChatGrouping.moveGroup(groups, "y", -1)
        assertEquals(4, order.size)
        assertEquals(groups.map { it.id }.toSet(), order.toSet())
    }

    /** 展示顺序按权重与 order 决定，不是原始数组顺序。 */
    @Test
    fun `display order drives the swap`() {
        // 数组里内置分组在前，但展示顺序把自建分组排到最前（自建 order 8 / 9 也按升序）。
        val groups = listOf(
            group("cron", 5, kind = "cron"),
            group("b", 8),
            group("a", 9),
        )
        // 展示顺序是 b、a、cron；把 a 上移即与 b 交换。
        assertEquals(listOf("a", "b", "cron"), ChatGrouping.moveGroup(groups, "a", -1))
    }

    /** 置顶的自建分组仍排在非置顶之前，且两者不互相跨块交换。 */
    @Test
    fun `pinned groups form their own block`() {
        val groups = listOf(
            group("p", 0, pinned = true),
            group("a", 1),
        )
        // a 上移的目标是置顶分组，跨块，拒绝。
        assertEquals(listOf("p", "a"), ChatGrouping.moveGroup(groups, "a", -1))
    }
}