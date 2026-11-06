package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重连时「历史 + 流式」按 id 合并的规则。
 *
 * 这里对应一个真实缺陷：此前用 `(history + appended).distinctBy { it.id }`，
 * `distinctBy` 保留先出现的历史副本，于是同 `msg_id` 时流式正文永远被丢掉，
 * 界面停在打开会话那一刻的内容上。判据是「同 id 时取哪一个」与「顺序是否保持」。
 */
class RunningStreamMergeTest {

    private fun msg(id: String, text: String, role: Role = Role.ASSISTANT) = ChatMessage(
        id = id,
        role = role,
        kind = ChatMessage.Kind.TEXT,
        text = text,
    )

    /** 同 id 时取流式新值——这条是缺陷本身。 */
    @Test
    fun `incoming wins on same id`() {
        val history = listOf(msg("m1", "打开时已有的内容"))
        val live = listOf(msg("m1", "打开时已有的内容 + 后续增量"))

        val merged = overlayById(history, live)

        assertEquals(1, merged.size)
        assertEquals("打开时已有的内容 + 后续增量", merged.single().text)
    }

    /** 新 id 追加到末尾，不能插到历史前面。 */
    @Test
    fun `new ids are appended`() {
        val history = listOf(msg("m1", "a"), msg("m2", "b"))
        val live = listOf(msg("m3", "c"))

        val merged = overlayById(history, live)

        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.id })
    }

    /** 历史顺序必须原样保留：流式增量里同 id 的位置可能不同，不能让它跳行。 */
    @Test
    fun `history order is preserved`() {
        val history = listOf(msg("m1", "a"), msg("m2", "b"), msg("m3", "c"))
        val live = listOf(msg("m3", "c2"), msg("m1", "a2"))

        val merged = overlayById(history, live)

        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.id })
        assertEquals("a2", merged[0].text)
        assertEquals("c2", merged[2].text)
    }

    /** 流式为空时原样返回，不做任何拷贝。 */
    @Test
    fun `empty incoming is a no-op`() {
        val history = listOf(msg("m1", "a"))
        assertEquals(history, overlayById(history, emptyList()))
    }

    /** 历史为空（首次进入新会话）时直接就是流式内容。 */
    @Test
    fun `empty history yields incoming`() {
        val live = listOf(msg("m1", "a"))
        assertEquals(live, overlayById(emptyList(), live))
    }
}