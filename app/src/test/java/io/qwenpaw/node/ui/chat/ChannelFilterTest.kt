package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatSpecDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 频道筛选的候选计算与切片。 */
class ChannelFilterTest {

    private fun chat(id: String, channel: String) = ChatSpecDto(id = id, name = id, channel = channel)

    @Test
    fun `candidates dedupe and sort`() {
        val chats = listOf(chat("a", "console"), chat("b", "console"), chat("c", "discord"))
        assertEquals(listOf("console", "discord"), channelCandidates(chats))
    }

    /** 候选不能从切片结果算：选中 console 后仍要保留 discord 这个选项。 */
    @Test
    fun `candidates do not collapse when a channel is selected`() {
        val all = listOf(chat("a", "console"), chat("b", "discord"))
        val visible = filterByChannel(all, "console")
        assertEquals(listOf("console", "discord"), channelCandidates(all))
        assertEquals(1, channelCandidates(visible).size)
    }

    @Test
    fun `single channel hides the control`() {
        assertFalse(shouldShowChannelFilter(listOf("console")))
        assertTrue(shouldShowChannelFilter(listOf("console", "discord")))
        assertFalse(shouldShowChannelFilter(emptyList()))
    }

    @Test
    fun `filter keeps only the chosen channel`() {
        val chats = listOf(chat("a", "console"), chat("b", "discord"), chat("c", "console"))
        assertEquals(listOf("a", "c"), filterByChannel(chats, "console").map { it.id })
        assertEquals(3, filterByChannel(chats, null).size)
    }

    @Test
    fun `blank channel is dropped`() {
        val chats = listOf(chat("a", ""), chat("b", "console"))
        assertEquals(listOf("console"), channelCandidates(chats))
    }

    @Test
    fun `stale selection is not valid`() {
        assertTrue(channelStillValid(null, listOf("console")))
        assertTrue(channelStillValid("console", listOf("console", "discord")))
        assertFalse(channelStillValid("discord", listOf("console")))
    }
}