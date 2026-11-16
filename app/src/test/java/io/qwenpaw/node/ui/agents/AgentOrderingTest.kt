package io.qwenpaw.node.ui.agents

import io.qwenpaw.node.data.model.AgentDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 智能体排序与删除回退的纯逻辑。
 *
 * 服务端对顺序有硬约束（default 第一、置顶在前），客户端先按同一规则算，
 * 避免把必然 400 的请求发出去。
 */
class AgentOrderingTest {

    private fun agent(id: String, pinned: Boolean = false) =
        AgentDto(id = id, name = id, pinned = pinned)

    @Test
    fun `moves within pinned block`() {
        val list = listOf(
            agent("default", pinned = true),
            agent("a", pinned = true),
            agent("b", pinned = true),
            agent("c"),
        )
        assertEquals(listOf("default", "b", "a", "c"), AgentOrdering.move(list, "a", 1))
        assertEquals(listOf("default", "b", "a", "c"), AgentOrdering.move(list, "b", -1))
    }

    @Test
    fun `cannot cross pinned boundary`() {
        val list = listOf(
            agent("default", pinned = true),
            agent("a", pinned = true),
            agent("b"),
        )
        assertNull(AgentOrdering.move(list, "a", 1))
        assertNull(AgentOrdering.move(list, "b", -1))
    }

    @Test
    fun `default cannot move`() {
        val list = listOf(agent("default", pinned = true), agent("a"))
        assertNull(AgentOrdering.move(list, "default", 1))
        assertNull(AgentOrdering.move(list, "default", -1))
    }

    @Test
    fun `cannot move past edges`() {
        val list = listOf(agent("default", pinned = true), agent("a"), agent("b"))
        assertNull(AgentOrdering.move(list, "a", -2))
        assertNull(AgentOrdering.move(list, "b", 1))
    }

    @Test
    fun `deleted agent falls back to default`() {
        assertEquals("default", fallbackAgentId("work", "work"))
        assertEquals("other", fallbackAgentId("other", "work"))
        assertEquals("default", fallbackAgentId("default", "work"))
    }
}
