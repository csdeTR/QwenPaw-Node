package io.qwenpaw.node.ui.skills

import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 技能页文案与判定的单测。
 *
 * `formatSkillUpdated` 的断言必须钉死时区：本机是 `Asia/Shanghai`、CI 是 UTC，
 * 依赖默认时区的断言会在两边给出不同结果。
 */
class SkillCopyTest {

    private lateinit var original: TimeZone

    @Before
    fun setUp() {
        original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun `source label covers known values`() {
        assertEquals("自定义", SkillCopy.sourceLabel("customized"))
        assertEquals("内置", SkillCopy.sourceLabel("builtin"))
        assertEquals("shop", SkillCopy.sourceLabel("shop"))
        assertEquals("未知来源", SkillCopy.sourceLabel(""))
    }

    @Test
    fun `sync status label`() {
        assertEquals("已同步", SkillCopy.syncStatusLabel("synced"))
        assertEquals("待更新", SkillCopy.syncStatusLabel("outdated"))
        assertEquals("", SkillCopy.syncStatusLabel(""))
        assertEquals("weird", SkillCopy.syncStatusLabel("weird"))
    }

    @Test
    fun `frontmatter hint rejects missing block`() {
        assertNotNull(SkillCopy.frontmatterHint("# 只有正文\n"))
    }

    @Test
    fun `frontmatter hint rejects unclosed block`() {
        val text = "---\nname: demo\ndescription: 演示\n"
        assertNotNull(SkillCopy.frontmatterHint(text))
    }

    @Test
    fun `frontmatter hint reports missing name`() {
        val text = "---\ndescription: 演示\n---\n正文\n"
        assertEquals("frontmatter 缺少 name，服务端会拒绝保存", SkillCopy.frontmatterHint(text))
    }

    @Test
    fun `frontmatter hint reports missing description`() {
        val text = "---\nname: demo\n---\n正文\n"
        assertEquals("frontmatter 缺少 description，服务端会拒绝保存", SkillCopy.frontmatterHint(text))
    }

    @Test
    fun `frontmatter hint accepts valid block`() {
        val text = "---\nname: demo\ndescription: 演示\n---\n\n# 标题\n"
        assertNull(SkillCopy.frontmatterHint(text))
    }

    @Test
    fun `scan blocked text carries severity`() {
        val text = SkillCopy.scanBlockedText("blocked by policy", "high")
        assertEquals(true, text.contains("高"))
        assertEquals(true, text.contains("blocked by policy"))
    }

    @Test
    fun `scan blocked text without severity`() {
        val text = SkillCopy.scanBlockedText(null, null)
        assertEquals(false, text.contains("风险，"))
        assertEquals(true, text.startsWith("技能扫描未通过"))
    }

    @Test
    fun `updated label converts utc to local`() {
        // 服务端实测值：2026-11-01T01:08:08.599528Z，东八区应显示 09:08。
        assertEquals("2026.11.01 09:08", SkillCopy.updatedLabel("2026-11-01T01:08:08.599528Z"))
    }

    @Test
    fun `updated label honours explicit offset`() {
        // 带偏移的值按同一套格式输出，保持两种写法的展示一致。
        assertEquals("2026.11.01 09:08", SkillCopy.updatedLabel("2026-11-01T09:08:00+08:00"))
    }

    @Test
    fun `updated label keeps unknown format as is`() {
        // 没有时区的时间不能折算：折算等于替服务端编造精度。
        assertEquals("2026-11-01 01:08", SkillCopy.updatedLabel("2026-11-01 01:08"))
    }

    @Test
    fun `updated label of empty is empty`() {
        assertEquals("", SkillCopy.updatedLabel(""))
        assertEquals("", SkillCopy.updatedLabel("   "))
    }

    @Test
    fun `channels label`() {
        assertEquals("未限定", SkillCopy.channelsLabel(emptyList()))
        assertEquals("all", SkillCopy.channelsLabel(listOf("all")))
        assertEquals("console、dingtalk", SkillCopy.channelsLabel(listOf("console", "dingtalk")))
    }
}