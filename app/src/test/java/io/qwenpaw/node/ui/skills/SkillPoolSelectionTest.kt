package io.qwenpaw.node.ui.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 技能池批量选择与导入页搜索过滤的纯函数。 */
class SkillPoolSelectionTest {

    private val pool = listOf("alpha", "beta", "gamma")

    @Test
    fun `select all takes every loaded name`() {
        assertEquals(pool.toSet(), SkillSelection.selectAll(pool))
    }

    @Test
    fun `select all ignores blank names`() {
        assertEquals(setOf("alpha"), SkillSelection.selectAll(listOf("alpha", "", " ")))
    }

    @Test
    fun `select all on empty list is empty`() {
        assertTrue(SkillSelection.selectAll(emptyList()).isEmpty())
    }

    @Test
    fun `invert flips the selection inside the loaded list`() {
        assertEquals(setOf("beta"), SkillSelection.invert(setOf("alpha", "gamma"), pool))
        assertEquals(pool.toSet(), SkillSelection.invert(emptySet(), pool))
        assertTrue(SkillSelection.invert(pool.toSet(), pool).isEmpty())
    }

    @Test
    fun `invert drops names that left the loaded list`() {
        val inverted = SkillSelection.invert(setOf("alpha", "removed"), pool)
        assertEquals(setOf("beta", "gamma"), inverted)
        assertFalse("removed" in inverted)
    }

    @Test
    fun `invert on empty list is empty`() {
        assertTrue(SkillSelection.invert(setOf("alpha"), emptyList()).isEmpty())
    }

    @Test
    fun `clear empties the selection`() {
        assertTrue(SkillSelection.clear().isEmpty())
    }

    @Test
    fun `matches ignores case and surrounding blanks`() {
        assertTrue(SkillSelection.matches("", "alpha"))
        assertTrue(SkillSelection.matches("  ", "alpha"))
        assertTrue(SkillSelection.matches(" ALP ", "alpha"))
        assertTrue(SkillSelection.matches("团队", "团队工作区"))
        assertFalse(SkillSelection.matches("delta", "alpha"))
    }

    @Test
    fun `matches checks every provided field`() {
        assertTrue(SkillSelection.matches("ws", "Team", "ws-1"))
        assertFalse(SkillSelection.matches("ws", "Team", "id-1"))
    }

    @Test
    fun `filter names keeps order and honours the query`() {
        assertEquals(pool, SkillSelection.filterNames(pool, ""))
        assertEquals(listOf("beta"), SkillSelection.filterNames(pool, "BET"))
        assertTrue(SkillSelection.filterNames(pool, "zzz").isEmpty())
    }

    @Test
    fun `select then invert then clear leaves nothing selected`() {
        val all = SkillSelection.selectAll(pool)
        val none = SkillSelection.invert(all, pool)
        assertTrue(none.isEmpty())
        assertTrue(SkillSelection.clear().isEmpty())
    }
}
