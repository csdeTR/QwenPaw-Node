package io.qwenpaw.node.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/** 来源标签：五个已知来源必须有中文名，未知来源原样返回。 */
class InboxLabelsTest {
    @Test
    fun `五种来源都有中文标签`() {
        assertEquals("定时任务", inboxSourceLabel("cron"))
        assertEquals("心跳", inboxSourceLabel("heartbeat"))
        assertEquals("记忆", inboxSourceLabel("memory"))
        assertEquals("技能更新", inboxSourceLabel("skill_autoupdate"))
        assertEquals("邮件", inboxSourceLabel("mail"))
    }

    @Test
    fun `筛选条包含全部五种来源且顺序固定`() {
        assertEquals(
            listOf("cron", "heartbeat", "memory", "skill_autoupdate", "mail"),
            INBOX_SOURCE_FILTERS,
        )
    }

    @Test
    fun `筛选按钮的来源文案`() {
        assertEquals("全部", sourceFilterLabel(null))
        assertEquals("全部", sourceFilterLabel(""))
        assertEquals("定时任务", sourceFilterLabel("cron"))
        assertEquals("技能更新", sourceFilterLabel("skill_autoupdate"))
        assertEquals("tool_guard", sourceFilterLabel("tool_guard"))
    }

    @Test
    fun `未知来源回退原值`() {
        assertEquals("tool_guard", inboxSourceLabel("tool_guard"))
        assertEquals("", inboxSourceLabel(""))
    }
}
